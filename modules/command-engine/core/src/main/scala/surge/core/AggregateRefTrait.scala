// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.core

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import io.opentracing.{ Span, Tracer }
import org.slf4j.{ Logger, LoggerFactory }
import surge.exceptions.{ SurgeTimeoutException, SurgeUnexpectedException }
import surge.internal.config.TimeoutConfig
import surge.internal.persistence.cqrs.CQRSPersistentActor
import surge.internal.utils.SpanSupport
import surge.internal.utils.SpanExtensions._
import surge.tracing.TracedMessage

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Generic reference to an aggregate that handles proxying messages to the actual aggregate
 * actor responsible for a particular aggregate id.  A single reference represents a proxy for
 * a single business logic aggregate actor.  Any commands you send to an instance of an AggregateRef
 * will be forwarded to the same business logic aggregate actor responsible for the same aggregateId
 * as the AggregateRef is responsible for.
 *
 * @tparam AggId The type of the aggregate id for the underlying business logic aggregate
 * @tparam Agg The type of the business logic aggregate being proxied to
 * @tparam Cmd The command type that the business logic aggregate handles
 * @tparam Event The event type that the business logic aggregate generates and can handle to update state
 */
trait AggregateRefTrait[AggId, Agg, Cmd, Event] extends SpanSupport {

  val aggregateId: AggId
  val region: ActorRef
  val tracer: Tracer

  private val askTimeoutDuration = TimeoutConfig.AggregateActor.askTimeout
  private implicit val timeout: Timeout = Timeout(askTimeoutDuration)

  private val log: Logger = LoggerFactory.getLogger(getClass)

  private def interpretActorResponse(span: Span): Any => Either[Throwable, Option[Agg]] = {
    case success: CQRSPersistentActor.CommandSuccess[Agg] =>
      span.finish()
      Right(success.aggregateState)
    case error: CQRSPersistentActor.CommandError =>
      span.error(error.exception)
      span.finish()
      Left(error.exception)
    case other =>
      val errorMsg = s"Unable to interpret response from aggregate - this should not happen: $other"
      log.error(errorMsg)
      Left(SurgeUnexpectedException(new IllegalStateException(errorMsg)))
  }

  /**
   * Asynchronously fetch the current state of the aggregate that this reference is proxying to.
   * @return A future of either None (the aggregate has no state) or some aggregate state for the
   *         aggregate with the aggregate id of this reference
   */
  protected def queryState(implicit ec: ExecutionContext): Future[Option[Agg]] = {
    (region ? CQRSPersistentActor.GetState(aggregateId.toString)).mapTo[CQRSPersistentActor.StateResponse[Agg]].map(_.aggregateState)
  }

  /**
   * Asynchronously send a command envelope to the aggregate business logic actor this reference is
   * talking to. Retries a given number of times if sending the command envelope to the business logic
   * actor fails.
   *
   * @param envelope The command envelope to send to this aggregate actor
   * @param retriesRemaining Number of retry attempts remaining, defaults to 0 for no retries.
   * @param ec Implicit execution context to use for transforming the raw actor response into a
   *           better typed response.
   * @return A future of either validation errors from the business logic aggregate or the updated
   *         state of the business logic aggregate after handling the command and applying any events
   *         that resulted from the command.
   */
  protected def askWithRetries(
    envelope: CQRSPersistentActor.CommandEnvelope[Cmd],
    retriesRemaining: Int = 0)(implicit ec: ExecutionContext): Future[Either[Throwable, Option[Agg]]] = {
    val askSpan = createSpan("send_command_to_aggregate").setTag("aggregateId", aggregateId.toString)
    (region ? TracedMessage(tracer, envelope, askSpan)).map(interpretActorResponse(askSpan)).recoverWith {
      case _: Throwable if retriesRemaining > 0 =>
        log.warn("Ask timed out to aggregate actor region, retrying request...")
        askWithRetries(envelope, retriesRemaining - 1)
      case a: AskTimeoutException =>
        val msg = s"Ask timed out after $askTimeoutDuration to aggregate actor with id ${envelope.aggregateId} executing command " +
          s"${envelope.command.getClass.getName}. This is typically a result of other parts of the engine performing incorrectly or " +
          s"hitting exceptions"
        askSpan.error(a)
        askSpan.finish()
        Future.successful[Either[Throwable, Option[Agg]]](Left(SurgeTimeoutException(msg)))
      case e: Throwable =>
        askSpan.error(e)
        askSpan.finish()
        Future.successful[Either[Throwable, Option[Agg]]](Left(SurgeUnexpectedException(e)))
    }
  }

  protected def applyEventsWithRetries(
    envelope: CQRSPersistentActor.ApplyEventEnvelope[Event],
    retriesRemaining: Int = 0)(implicit ec: ExecutionContext): Future[Option[Agg]] = {
    val askSpan = createSpan("send_events_to_aggregate").setTag("aggregateId", aggregateId.toString)
    (region ? TracedMessage(tracer, envelope, askSpan)).map(interpretActorResponse(askSpan)).flatMap {
      case Left(exception) => Future.failed(exception)
      case Right(state)    => Future.successful(state)
    }
  }
}
