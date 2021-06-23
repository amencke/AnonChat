package com.aqualung.anonchat
package AnonChat

import AnonChat.API.ConversationRoutes
import AnonChat.Domain.Aggregates.ConversationAggregate.SessionHandler

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http

import scala.concurrent.ExecutionContext
import scala.io.StdIn

object Server extends App {
  implicit val system: ActorSystem[_] = {
    ActorSystem(SessionHandler(), "whatever")
  }
  val conversationSessionActor =
    system.systemActorOf(SessionHandler(), name = "conversation-session-handler")
  implicit val executionContext: ExecutionContext = system.executionContext
  val routes                                      = new ConversationRoutes(conversationSessionActor).conversationRoutes
  val bindingFuture                               = Http().newServerAt("localhost", 8080).bind(routes)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind())                 // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
