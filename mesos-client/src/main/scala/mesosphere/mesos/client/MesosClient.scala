package mesosphere.mesos.client

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.alpakka.recordio.scaladsl.RecordIOFraming
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.google.protobuf
import com.typesafe.scalalogging.StrictLogging
import mesosphere.mesos.client.MesosStreamSupport._
import mesosphere.mesos.conf.MesosConf
import org.apache.mesos.v1.mesos._
import org.apache.mesos.v1.scheduler.scheduler.Call.{Accept, Acknowledge, Decline, Kill, Message, Reconcile, Revive}
import org.apache.mesos.v1.scheduler.scheduler.{Call, Event}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class MesosClient(
    conf: MesosConf,
    frameworkInfo: FrameworkInfo)(
    implicit val system: ActorSystem,
    implicit val materializer: ActorMaterializer,
    implicit val executionContext: ExecutionContext
) extends MesosApi with StrictLogging {

  private val overflowStrategy = akka.stream.OverflowStrategy.backpressure

  val context = new AtomicReference[ConnectionContext](ConnectionContext(conf))
  val contextPromise = Promise[ConnectionContext]()

  /** It is declared lazy to decouple instantiation of the MesosClient instance from connection initialization. */
  lazy val source: Source[Event, NotUsed] = {

    val body = subscribe(frameworkInfo).toByteArray

    val request = HttpRequest(
      HttpMethods.POST,
      uri = Uri("/api/v1/scheduler"),
      entity = HttpEntity(ProtobufMediaType, body),
      headers = List(headers.Accept(ProtobufMediaType)))

    def log[T](prefix: String): Flow[T, T, NotUsed] = Flow[T].map{ e => logger.info(s"$prefix$e"); e }

    def httpConnection(host: String, port: Int): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection(host, port)

    /**
      * Inbound traffic is handled via a persistent connection to the `/api/v1/scheduler` endpoint.
      * Each message is encoded in RecordIO format, which essentially prepends to a single record
      * (either JSON or serialized protobuf) its length in bytes: [<length>\n<json string|protobuf bytes>]
      *
      * http://mesos.apache.org/documentation/latest/scheduler-http-api/#recordio-response-format-1
      */
    val recordIoScanner: Flow[ByteString, ByteString, NotUsed] = RecordIOFraming.scanner()

    val connectionHandler: Flow[HttpResponse, HttpResponse, NotUsed] = Flow[HttpResponse].map { response =>
      response.status match {
        case StatusCodes.OK =>
          logger.info(s"Connected successfully to ${context.get().url}");
          val streamId = response.headers
            .find(h => h.is(MesosStreamIdHeaderName.toLowerCase))
            .getOrElse(throw new IllegalStateException(s"Missing MesosStreamId header in ${response.headers}"))

          // At this point we successfully connected to mesos leader so context should have correct leader's host
          // and port either from the config or set on the redirect.
          val ctx = context.updateAndGet(c => c.copy(mesosStreamId = streamId.value()))
          contextPromise.success(ctx)

          response
        case StatusCodes.TemporaryRedirect =>
          val leader = new URI(response.header[headers.Location].get.value())
          // Schedulers are expected to make HTTP requests to the leading master. If requests are made to a
          // non-leading master a “HTTP 307 Temporary Redirect” will be received with the “Location” header
          // pointing to the leading master.
          // We throw a MesosRedicrectException here with the new leader address and handle it in the
          // `recoverWithRetries` stage by building a flow that reconnects to the new leader.
          context.updateAndGet(c => c.copy(host = leader.getHost, port = leader.getPort))
          throw new MesosRedicrectException(leader)
        case _ =>
          throw new IllegalArgumentException(s"Mesos server error: $response")
      }
    }

    val dataBytesExtractor: Flow[HttpResponse, ByteString, NotUsed] = Flow[HttpResponse].flatMapConcat(resp => resp.entity.dataBytes)

    val eventDeserializer: Flow[ByteString, Event, NotUsed] = Flow[ByteString].map(bytes => Event.parseFrom(bytes.toArray))

    val ctx = context.get()

    Source.single(request)
      .via(log(s"Connecting to mesos master: ${ctx.url}"))
      .via(httpConnection(ctx.host, ctx.port))
      .via(log("HttpResponse: "))
      .via(connectionHandler)
      .recoverWithRetries(conf.redirectRetires(), {
        case MesosRedicrectException(leader) =>
          logger.warn(s"New mesos leader available at $leader")

          // Build a new flow with a request to the new leader.
          Source.single(request)
            .via(log(s"Connecting to the new leader: $leader"))
            .via(httpConnection(leader.getHost, leader.getPort))
            .via(log("HttpResponse: "))
            .via(connectionHandler)
      })
      .idleTimeout(conf.idleTimeout().seconds)
      .buffer(conf.sourceBufferSize(), overflowStrategy)
      .via(dataBytesExtractor)
      .via(recordIoScanner)
      .via(eventDeserializer)
      .via(log("Received mesos Event: "))
  }

  override lazy val (killSwitch: UniqueKillSwitch, mesosSource: Source[Event, NotUsed]) = source
    .viaMat(KillSwitches.single)(Keep.right)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()


  // A sink for mesos events. We use `Http().singleRequest()` method thus creating a new HTTP request every time.
  private val sink: Sink[(ConnectionContext, Call), Future[Done]] = Sink.foreach[(ConnectionContext, Call)]{
    case (ctx, call) =>

      logger.info(s"Sending: $call to ${ctx.url}")

      val request = HttpRequest(
        HttpMethods.POST,
        uri = Uri(s"http://${ctx.url}/api/v1/scheduler"),
        entity = HttpEntity(ProtobufMediaType, call.toByteArray),
        headers = List(MesosStreamIdHeader(ctx.mesosStreamId)))

      Http().singleRequest(request).map{ response =>
        logger.info(s"Response: $response")
        response.discardEntityBytes()
      }
  }

  // Attach a MergeHub Source to mesos sink. This will materialize to a corresponding Sink.
  private val hub: RunnableGraph[Sink[Call, NotUsed]] =
    MergeHub.source[Call](perProducerBufferSize = 16)
      .mapAsync(1)(event => contextPromise.future.map(ctx => (ctx, event)))
      .to(sink)

  // By running/materializing the consumer we get back a Sink, and hence now have access to feed elements into it.
  // This Sink can be materialized any number of times, and every element that enters the Sink will be consumed by
  // our consumer.
  override val mesosSink: Sink[Call, NotUsed] = hub.run()
}

trait MesosApi {

  /**
    * First call to this method will initialize the connection to mesos and return a `Source[String, NotUser]` with
    * mesos `Event`s. All subsequent calls will return the previously created event source.
    * The connection is initialized with a `POST /api/v1/scheduler` with the framework info in the body. The request
    * is answered by a `SUBSCRIBED` event which contains `MesosStreamId` header. This is reused by all later calls to
    * `/api/v1/scheduler`.
    * Multiple subscribers can attach to returned `Source[String, NotUsed]` to receive mesos events. The stream will
    * be closed either on connection error or connection shutdown e.g.:
    * ```
    * client.source.runWith(Sink.ignore).onComplete{
    *  case Success(res) => logger.info(s"Stream completed: $res")
    *  case Failure(e) => logger.error(s"Error in stream: $e")
    * }
    * ```
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1
    */
  def mesosSource: Source[Event, NotUsed]

  /**
    * Sink for mesos calls. Multiple publishers can materialize this sink to send mesos `Call`s. Every `Call` is sent
    * using a new HTTP connection.
    * Note: a scheduler can't send calls to mesos without subscribing first (see [MesosClient.source] method). Calls
    * published to sink without a successful subscription will be buffered and will have to wait for subscription
    * connection. Always call `source()` first.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    */
  def mesosSink: Sink[Call, NotUsed]

  /**
    * A kill switch for the mesos source. Calling `shutdown()` or `abort()` on it will close the connection to mesos.
    * Note that depending on `failoverTimeout` provided with SUBSCRIBED call mesos could start killing tasks and
    * executors started by the framework. Make sure to set `failoverTimeout` appropriately. See `teardown()` method
    * for another way to shutdown a framework.
    */
  val killSwitch: UniqueKillSwitch

  /** ***************************************************************************
    * Helper methods to create mesos `Call`s
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    * ***************************************************************************
    */

  /** This is the first step in the communication process between the scheduler and the master. This is also to be
    * considered as subscription to the “/scheduler” event stream. To subscribe with the master, the scheduler sends
    * an HTTP POST with a SUBSCRIBE message including the required FrameworkInfo. Note that if
    * `subscribe.framework_info.id` is not set, master considers the scheduler as a new one and subscribes it by
    * assigning it a FrameworkID. The HTTP response is a stream in RecordIO format; the event stream begins with a
    * SUBSCRIBED event.
    *
    * Note: this method is used by mesos client to establish connection to mesos master and is not supposed to be called
    * directly by the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1
    */
  protected def subscribe(frameworkInfo: FrameworkInfo): Call = {
    Call(
      frameworkId = frameworkInfo.id,
      subscribe = Some(Call.Subscribe(frameworkInfo)),
      `type` = Some(Call.Type.SUBSCRIBE)
    )
  }

  /** Sent by the scheduler when it wants to tear itself down. When Mesos receives this request it will
    * shut down all executors (and consequently kill tasks). It then removes the framework and closes all
    * open connections from this scheduler to the Master.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#teardown
    */
  def teardown(frameworkId: FrameworkID): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.TEARDOWN)
    )
  }

  /** Sent by the scheduler when it accepts offer(s) sent by the master. The ACCEPT request includes the type
    * of operations (e.g., launch task, launch task group, reserve resources, create volumes) that the scheduler
    * wants to perform on the offers. Note that until the scheduler replies (accepts or declines) to an offer,
    * the offer’s resources are considered allocated to the offer’s role and to the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#accept
    */
  def accept(frameworkId: FrameworkID, accepts: Accept): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.ACCEPT),
      accept = Some(accepts))
  }

  /** Sent by the scheduler to explicitly decline offer(s) received. Note that this is same as sending an ACCEPT
    * call with no operations.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#decline
    */
  def decline(frameworkId: FrameworkID, offerIds: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.DECLINE),
      decline = Some(Decline(offerIds = offerIds, filters = filters))
    )
  }

  /** Sent by the scheduler to remove any/all filters that it has previously set via ACCEPT or DECLINE calls.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#revive
    */
  def revive(frameworkId: FrameworkID, roles: Option[String] = None): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.REVIVE),
      revive = Some(Revive(role = roles))
    )
  }

  /** Suppress offers for the specified roles. If `roles` is empty the `SUPPRESS` call will suppress offers for all
    * of the roles the framework is currently subscribed to.
    *
    * http://mesos.apache.org/documentation/latest/upgrades/#1-2-x-revive-suppress
    */
  def suppress(frameworkId: FrameworkID, roles: Option[String] = None): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.SUPPRESS),
      suppress = Some(Call.Suppress(roles))
    )
  }

  /** Sent by the scheduler to kill a specific task. If the scheduler has a custom executor, the kill is forwarded
    * to the executor; it is up to the executor to kill the task and send a TASK_KILLED (or TASK_FAILED) update.
    * If the task hasn’t yet been delivered to the executor when Mesos master or agent receives the kill request,
    * a TASK_KILLED is generated and the task launch is not forwarded to the executor. Note that if the task belongs
    * to a task group, killing of one task results in all tasks in the task group being killed. Mesos releases the
    * resources for a task once it receives a terminal update for the task. If the task is unknown to the master,
    * a TASK_LOST will be generated.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#kill
    */
  def kill(frameworkId: FrameworkID, taskId: TaskID, agentId: Option[AgentID] = None, killPolicy: Option[KillPolicy]): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.KILL),
      kill = Some(Kill(taskId = taskId, agentId = agentId, killPolicy = killPolicy))
    )
  }

  /** Sent by the scheduler to shutdown a specific custom executor. When an executor gets a shutdown event, it is
    * expected to kill all its tasks (and send TASK_KILLED updates) and terminate.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#shutdown
    */
  def shutdown(frameworkId: FrameworkID, executorId: ExecutorID, agentId: AgentID):Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.SHUTDOWN),
      shutdown = Some(Call.Shutdown(executorId = executorId, agentId = agentId))
    )
  }

  /** Sent by the scheduler to acknowledge a status update. Note that with the new API, schedulers are responsible
    * for explicitly acknowledging the receipt of status updates that have status.uuid set. These status updates
    * are retried until they are acknowledged by the scheduler. The scheduler must not acknowledge status updates
    * that do not have `status.uuid` set, as they are not retried. The `uuid` field contains raw bytes encoded in Base64.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#acknowledge
    */
  def acknowledge(frameworkId: FrameworkID, agentId: AgentID, taskId: TaskID, uuid: protobuf.ByteString): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.ACKNOWLEDGE),
      acknowledge = Some(Acknowledge(agentId, taskId, uuid))
    )
  }

  /** Sent by the scheduler to query the status of non-terminal tasks. This causes the master to send back UPDATE
    * events for each task in the list. Tasks that are no longer known to Mesos will result in TASK_LOST updates.
    * If the list of tasks is empty, master will send UPDATE events for all currently known tasks of the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#reconcile
    */
  def reconcile(frameworkId: FrameworkID, tasks: Seq[Reconcile.Task]): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.RECONCILE),
      reconcile = Some(Reconcile(tasks))
    )
  }

  /** Sent by the scheduler to send arbitrary binary data to the executor. Mesos neither interprets this data nor
    * makes any guarantees about the delivery of this message to the executor. data is raw bytes encoded in Base64
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#message
    */
  def message(frameworkId: FrameworkID, agentId: AgentID, executorId: ExecutorID, message: ByteString): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.MESSAGE),
      message = Some(Message(agentId, executorId, protobuf.ByteString.copyFrom(message.toByteBuffer)))
    )
  }

  /** Sent by the scheduler to request resources from the master/allocator. The built-in hierarchical allocator
    * simply ignores this request but other allocators (modules) can interpret this in a customizable fashion.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#request
    */
  def request(frameworkId: FrameworkID, requests: Seq[Request]): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.REQUEST),
      request = Some(Call.Request(requests = requests))
    )
  }

  /** Accepts an inverse offer. Inverse offers should only be accepted if the resources in the offer can be safely
    * evacuated before the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def acceptInverseOffers(frameworkId: FrameworkID, offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.ACCEPT_INVERSE_OFFERS),
      acceptInverseOffers = Some(Call.AcceptInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }

  /** Declines an inverse offer. Inverse offers should be declined if
    * the resources in the offer might not be safely evacuated before
    * the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def declineInverseOffers(frameworkId: FrameworkID, offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = Some(frameworkId),
      `type` = Some(Call.Type.DECLINE_INVERSE_OFFERS),
      declineInverseOffers = Some(Call.DeclineInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }

}

// TODO: Add more integration tests
// TODO: Add README.md

object MesosClient extends StrictLogging {

  /** Run Foo framework that:
    *  - successfully subscribes
    *  - declines first offer it gets
    *
    *  Not much, but shows the basic idea. Good to test against local mesos.
    *
    */
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val frameworkInfo = FrameworkInfo(
      user = "foo",
      name = "Example FOO Framework",
      roles = Seq("test"),
      capabilities = Seq(FrameworkInfo.Capability(`type` = Some(FrameworkInfo.Capability.Type.MULTI_ROLE)))
    )

    val conf = new MesosConf(List("--master", s"127.0.0.1:5050"))
    val client = new MesosClient(conf, frameworkInfo)

    val frameworkIDP = Promise[FrameworkID]()
    val frameworkIDF = frameworkIDP.future

    client.mesosSource.runWith(Sink.foreach { event =>

      if (event.`type`.get == Event.Type.SUBSCRIBED) {  // Save frameworkdId received
        frameworkIDP.success(event.subscribed.get.frameworkId)
      }
      else if (event.`type`.get == Event.Type.OFFERS) { // Test case: decline first offer received
        val offerId = event.offers.get.offers.head.id
        logger.info(s"Declining offer with $offerId")

        val frameworkID = Await.result(frameworkIDF, Duration.Inf)  // Should be instant and in the same thread since frameworkIDF is already completed
        val decline = client.decline(
          frameworkId = frameworkID,
          offerIds = Seq(offerId),
          filters = Some(Filters(Some(5.0f)))
        )
        Source.single(decline).runWith(client.mesosSink)
      }

    }).onComplete{
      case Success(res) =>
        logger.info(s"Stream completed: $res"); system.terminate()
      case Failure(e) => logger.error(s"Error in stream: $e"); system.terminate()
    }
  }
}
