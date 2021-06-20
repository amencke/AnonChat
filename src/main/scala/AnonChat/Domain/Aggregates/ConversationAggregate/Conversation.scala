package com.aqualung.anonchat
package AnonChat.Domain.Aggregates.ConversationAggregate

import AnonChat.Domain.Aggregates.ConversationAggregate.Session._
import AnonChat.Domain.Entities._

import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationLong, FiniteDuration}

object Session {

  sealed trait SessionCommand

  final case class GetSession(
      conversationID: ConversationID,
      requester: UserID,
      replyTo: ActorRef[SessionEvent]
  ) extends SessionCommand

  final case class SessionTimeout(conversationID: ConversationID) extends SessionCommand

//  final case class EndConversation(withUser: UserID, replyTo: ActorRef[SessionEvent])
//      extends AdminCommand
//
//  final case class GetMessagesSince(messageID: MessageID, replyTo: ActorRef[SessionEvent])
//      extends AdminCommand

  private[Aggregates] final case class PublishSessionMessage(userID: UserID, message: String)
      extends SessionCommand

  sealed trait SessionEvent

  final case class SessionGranted(sessionHandle: ActorRef[PostMessage]) extends SessionEvent

  final case class MessagePosted(byUser: UserID, message: String) extends SessionEvent

  sealed trait ConversationCommand

  final case class PostMessage(fromUserID: UserID, message: String) extends ConversationCommand

  private[Aggregates] final case class NotifyClient(message: MessagePosted)
      extends ConversationCommand

  final case object ConversationTimeout extends ConversationCommand

  def apply(): Behavior[SessionCommand] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new AdminBehavior(context, timers)
      }
    }

  class AdminBehavior(context: ActorContext[SessionCommand], timers: TimerScheduler[SessionCommand])
      extends AbstractBehavior[SessionCommand](context) {

    private val sessions =
      scala.collection.mutable.Map[ConversationID, ActorRef[ConversationCommand]]()

    override def onMessage(msg: SessionCommand): Behavior[SessionCommand] = {
      msg match {
        case GetSession(conversationID, requester, replyTo) =>
          val session = sessions.getOrElseUpdate(
            conversationID,
            context.spawn(
              ConversationBehavior(conversationID, context.self, replyTo),
              name = URLEncoder.encode(conversationID.toString(), StandardCharsets.UTF_8.name)
            )
          )
          replyTo ! SessionGranted(session)
          context.log.info(s"Granted ${requester} a session.")
          sessions += (conversationID -> session)
          this
        case PublishSessionMessage(userID, message) =>
          context.log.info(s"Publishing message from ${userID}: ${message}")
          val notification = NotifyClient(MessagePosted(userID, message))
          sessions.filter { _._1 != userID } foreach { tup =>
            tup._2 ! notification
            println(s"Notified ${tup._1}: ${message}")
          }
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
  def apply(
      conversationID: ConversationID,
      session: ActorRef[SessionCommand],
      client: ActorRef[SessionEvent]
  ): Behavior[ConversationCommand] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new ConversationBehavior(conversationID, timers, context, session, client)
      }
    }
}

private class ConversationBehavior(
    conversationID: ConversationID,
    timers: TimerScheduler[ConversationCommand],
    context: ActorContext[ConversationCommand],
    conversation: ActorRef[SessionCommand],
    client: ActorRef[SessionEvent]
) extends AbstractBehavior[ConversationCommand](context) {

  private val sessionTimeout: FiniteDuration =
    context.system.settings.config
      .getDuration("conversation.session.timeout", TimeUnit.MILLISECONDS)
      .millis

  private case object TimerKey
  private def idle(): ConversationBehavior = {
    timers.startSingleTimer(TimerKey, ConversationTimeout, sessionTimeout)
    this
  }

  override def onMessage(msg: ConversationCommand): Behavior[ConversationCommand] =
    msg match {
      case PostMessage(fromUserID, message) =>
        context.log.info(s"${fromUserID} posted: ${message}")
        conversation ! PublishSessionMessage(fromUserID, message)
        idle()
      case NotifyClient(message) =>
        client ! message
        idle()
      case ConversationTimeout =>
        conversation ! SessionTimeout(conversationID)
        Behaviors.stopped
    }
}
