package com.aqualung.anonchat.AnonChat.API

import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.aqualung.anonchat.AnonChat.Domain.Aggregates.ConversationAggregate.Session
import com.aqualung.anonchat.AnonChat.Domain.Aggregates.ConversationAggregate.Session.{
  ConversationCommand,
  PostMessage,
  SessionCommand,
  SessionEvent,
  SessionGranted,
  GetSession
}
import com.aqualung.anonchat.AnonChat.Domain.Entities.{ConversationID, UserID}
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}

sealed trait ConversationRestEntities
case class PostMessageRequest(userID: UserID, message: String) extends ConversationRestEntities
case class PostMessageResponse(message: String)                extends ConversationRestEntities
case class PostMessageError(message: String)                   extends ConversationRestEntities

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val messageRequestFormat       = jsonFormat2(PostMessageRequest)
  implicit val messageResponseFormat      = jsonFormat1(PostMessageResponse)
  implicit val messageErrorResponseFormat = jsonFormat1(PostMessageError)
}

class ConversationRoutes(session: ActorRef[SessionCommand])(implicit val system: ActorSystem[_])
    extends JsonSupport {

  implicit val executionContext         = system.executionContext
  private implicit val timeout: Timeout = 3.seconds

  def getSession(conversationID: ConversationID, userID: UserID): Future[SessionEvent] =
    session.ask(GetSession(conversationID, userID, _))

  def postMessage(fromUserID: UserID, message: String)(implicit
      session: ActorRef[ConversationCommand]
  ): Unit =
    session ! PostMessage(fromUserID, message)

  val version = "v1"

  lazy val conversationRoutes: Route = {
    concat(
      pathPrefix(version / "conversation" / LongNumber) { conversationID =>
        post {
          entity(as[PostMessageRequest]) {
            mr =>
              val maybeSessionGranted: Future[SessionEvent] =
                getSession(conversationID, mr.userID)

              onComplete(maybeSessionGranted) {
                case Success(SessionGranted(sessionHandle)) =>
                  sessionHandle ! PostMessage(mr.userID, mr.message)
                  complete(OK, PostMessageResponse(mr.message))
                case Failure(ex) =>
                  complete(InternalServerError -> ex)
              }
          }
        }
      }
    )
  }
}
