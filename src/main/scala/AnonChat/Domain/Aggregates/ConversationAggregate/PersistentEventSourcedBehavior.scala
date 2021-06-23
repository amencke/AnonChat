package com.aqualung.anonchat
package AnonChat.Domain.Aggregates.ConversationAggregate

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.PersistenceId
import AnonChat.Domain.Aggregates.ConversationAggregate.SessionHandler.{
  GetHistory,
  MessagePosted,
  PostMessage,
  SessionCommand,
  SessionEvent
}
import AnonChat.Domain.Entities.{ConversationID, UserID}

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.collection.{immutable, mutable}

object PersistentEventSourcedBehavior {

  // The messaging protocol should probably be encapsulated inside this class. SessionHandlerBehavior is a shim
  // that (for the time being) just forwards messages here, where business logic is applied and domain events are
  // generated. Hence, this class shares a messaging protocol with that class.

  case class ConversationInMemoryState(
      data: mutable.Map[ConversationID, List[(UserID, String)]]
  )

  /*
  These 2 classes contain the same data. The important distinction is that `ConversationHistory` is
  immutable and can be shared. Exposing the mutable `ConversationInMemoryState` could allow other parts
  of the codebase to mutate the contents of the conversation history.
   */
  case class ConversationHistory(
      data: immutable.Map[ConversationID, List[(UserID, String)]]
  )

  val commandHandler: (
      ConversationInMemoryState,
      SessionCommand,
      ActorContext[SessionCommand]
  ) => Effect[SessionEvent, ConversationInMemoryState] = { (state, command, context) =>
    command match {
      case PostMessage(conversationID, sender, message) =>
        context.log.info(
          s"Received a command: PostMessage(conversationID=${conversationID}, sender=${sender}, message=${message})"
        )
        Effect.persist(MessagePosted(conversationID, sender, message))
      case GetHistory(conversationID, requester, replyTo) =>
        context.log.info(
          s"Received a command: GetHistory(conversationID=${conversationID}, requester=${requester}))"
        )
        val conversationHistory: Map[ConversationID, List[(UserID, String)]] = Map(
          conversationID -> state.data(conversationID)
        )
        context.log.info(
          s"Returning history: ${conversationHistory}"
        )
        replyTo ! ConversationHistory(conversationHistory)
        Effect.none
      case _ => throw new IllegalStateException()
    }
  }

  val eventHandler: (
      ConversationInMemoryState,
      SessionEvent,
      ActorContext[SessionCommand]
  ) => ConversationInMemoryState = { (state, event, context) =>
    event match {
      case MessagePosted(conversationID, sender, message) =>
        context.log.info(
          s"Publishing domain event: MessagePosted(${conversationID}, ${sender}, ${message})"
        )
        val _ = state.data.getOrElseUpdate(conversationID, List[(UserID, String)]())
        state.data(conversationID) = List((sender, message)) ++ state.data(conversationID)
        context.log.info(
          s"Current state: ${state}"
        )
        state
      case _ => throw new IllegalStateException()
    }
  }

  def apply(conversationID: ConversationID): Behavior[SessionCommand] =
    Behaviors.setup[SessionCommand] { context =>
      EventSourcedBehavior[SessionCommand, SessionEvent, ConversationInMemoryState](
        persistenceId = PersistenceId.ofUniqueId(conversationID.toString()),
        emptyState = ConversationInMemoryState(
          mutable.Map[ConversationID, List[(UserID, String)]]()
        ),
        commandHandler = { (state, command) => commandHandler(state, command, context) },
        eventHandler = { (state, event) => eventHandler(state, event, context) }
      )
    }
}
