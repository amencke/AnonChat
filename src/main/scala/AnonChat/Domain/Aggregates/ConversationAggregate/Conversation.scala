package com.aqualung.anonchat
package AnonChat.Domain.Aggregates.ConversationAggregate

import AnonChat.Domain.Aggregates.ConversationAggregate.SessionHandler._
import AnonChat.Domain.Entities._
import AnonChat.Domain.Aggregates.ConversationAggregate.PersistentEventSourcedBehavior.{
  ConversationCleared,
  ConversationHistory
}
import AnonChat.Server.system

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import AnonChat.Domain.Aggregates.ConversationAggregate.ConversationBehavior._

import akka.pattern.StatusReply

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success}

object SessionHandler {

  sealed trait SessionHandlerCommand

  final case class GetSession(
      conversationID: ConversationID,
      requester: UserID,
      replyTo: ActorRef[SessionEvent]
  ) extends SessionHandlerCommand

  final case class SessionTimeout(conversationID: ConversationID) extends SessionHandlerCommand

  private[Aggregates] final case class PublishSessionMessage(
      conversationID: ConversationID,
      sender: UserID,
      message: String
  ) extends SessionHandlerCommand

  sealed trait SessionEvent

  final case class SessionGranted(sessionHandle: ActorRef[SessionCommand]) extends SessionEvent

  final case class MessagePosted(conversationID: ConversationID, sender: UserID, message: String)
      extends SessionEvent

  final case class ConversationDeleted(conversationID: ConversationID) extends SessionEvent

  private[Aggregates] final case class NotifyClient(message: MessagePosted) extends SessionCommand

  final case object ConversationTimeout extends SessionCommand

  def apply(): Behavior[SessionHandlerCommand] =
    Behaviors.setup { context =>
      new SessionHandlerBehavior(context)
    }

  class SessionHandlerBehavior(
      context: ActorContext[SessionHandlerCommand]
  ) extends AbstractBehavior[SessionHandlerCommand](context) {

    private val sessions =
      scala.collection.mutable.Map[ConversationID, ActorRef[SessionCommand]]()

    override def onMessage(msg: SessionHandlerCommand): Behavior[SessionHandlerCommand] = {
      msg match {
        case GetSession(conversationID, requester, replyTo) =>
          val session = sessions.getOrElseUpdate(
            conversationID,
            context.spawn(
              ConversationBehavior(conversationID, context.self, replyTo),
              name = URLEncoder.encode(
                s"conversation-session-${conversationID.toString()}",
                StandardCharsets.UTF_8.name
              )
            )
          )
          replyTo ! SessionGranted(session)
          context.log.info(s"Granted user ${requester} a session.")
          sessions += (conversationID -> session)
          this
        case PublishSessionMessage(conversationID, sender, message) =>
          context.log.info(
            s"Publishing to conversation ${conversationID}:\n" +
              s"User ${sender} posted message: ${message}"
          )
          val notification = NotifyClient(MessagePosted(conversationID, sender, message))
          sessions(conversationID) ! notification
          this
        case SessionTimeout(conversationID) =>
          sessions -= conversationID
          context.log.info(s"Session timed out for conversation ${conversationID}")
          this
      }
    }
  }
}

object ConversationBehavior {
  // This naming is wrong. This object is really a session, since the REST API needs access to the behaviours
  // in this class, and an instance of class is what is granted by what is currently called "SessionHandler"

  sealed trait SessionCommand

  final case class PostMessage(conversationID: ConversationID, sender: UserID, message: String)
      extends SessionCommand

  final case class GetHistory(
      conversationID: ConversationID,
      requester: UserID,
      replyTo: ActorRef[ConversationHistory]
  ) extends SessionCommand

  final case class DeleteConversation(
      conversationID: ConversationID,
      replyTo: ActorRef[StatusReply[ConversationCleared]]
  ) extends SessionCommand

  def apply(
      conversationID: ConversationID,
      sessionHandler: ActorRef[SessionHandlerCommand],
      client: ActorRef[SessionEvent]
  ): Behavior[SessionCommand] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new ConversationBehavior(conversationID, timers, context, sessionHandler, client)
      }
    }
}

private class ConversationBehavior(
    conversationID: ConversationID,
    timers: TimerScheduler[SessionCommand],
    context: ActorContext[SessionCommand],
    sessionHandler: ActorRef[SessionHandlerCommand],
    client: ActorRef[SessionEvent]
) extends AbstractBehavior[SessionCommand](context) {

  private val sessionTimeout: FiniteDuration =
    context.system.settings.config
      .getDuration("conversation.session.timeout", TimeUnit.MILLISECONDS)
      .millis
  private val requestTimeout: FiniteDuration = 3.seconds
  implicit val executionContext              = system.executionContext
  private implicit val timeout: Timeout      = 3.seconds
  //private val sessionTimeout: FiniteDuration = 300.seconds

  private case object TimerKey
  private def idle(): ConversationBehavior = {
    timers.startSingleTimer(TimerKey, ConversationTimeout, sessionTimeout)
    this
  }

  /*
  The point of using persistence with event sourcing here is that conversation history is cached, and
  therefore we wouldn't need to make expensive database calls and replay all conversation events each
  time a request is handled. That is still required when the first request comes in, but after that,
  everything we need to service subsequent requests is available in memory. So each `ConversationBehavior`
  actor has it's own `PersistentEventSourcedBehavior`, and the lifetime of both is controlled by
  configuration provided for the parent (ConversationBehavior) actor.
   */
  val persistentEventSourcedActor = context.spawn(
    PersistentEventSourcedBehavior(conversationID),
    s"event-sourced-actor-${conversationID}"
  )

  override def onMessage(msg: SessionCommand): Behavior[SessionCommand] =
    msg match {
      case PostMessage(conversationID, sender, message) =>
        context.log.info(s"${sender} posted to conversation ${conversationID}: ${message}")
        persistentEventSourcedActor ! PostMessage(conversationID, sender, message)
        idle()
      case GetHistory(conversationID, requester, replyTo) =>
        context.log.info(s"Retrieving history of conversation ${conversationID} for ${requester}")
        val maybeHistory: Future[ConversationHistory] =
          persistentEventSourcedActor.ask(GetHistory(conversationID, requester, _))
        maybeHistory.onComplete {
          case Success(conversationHistory) =>
            replyTo ! conversationHistory
          case Failure(_) =>
            replyTo ! ConversationHistory(Map.empty[ConversationID, List[(UserID, String)]])
          case _ =>
        }
        Await.ready(maybeHistory, requestTimeout)
        idle()
      case DeleteConversation(conversationID, replyTo) =>
        context.log.info(s"Deleting history of conversation ${conversationID}")
        val maybeDeleted =
          persistentEventSourcedActor.askWithStatus(DeleteConversation(conversationID, _))
        maybeDeleted onComplete {
          case Success(res) => replyTo ! StatusReply.Success(res)
          case Failure(res) => replyTo ! StatusReply.error(res)
        }
        Await.ready(maybeDeleted, requestTimeout)
        idle()

      case NotifyClient(message) =>
        context.log.info(
          s"Conversation actor received message for conversation ${message.conversationID}, notifying participants"
        )
        // There is no client, because the client is a non-actor requesting via an `ask`,
        // so this message goes to dead letters
        client ! message
        idle()
      case ConversationTimeout =>
        sessionHandler ! SessionTimeout(conversationID)
        // This will also stop the event sourced actor
        Behaviors.stopped
    }
}
