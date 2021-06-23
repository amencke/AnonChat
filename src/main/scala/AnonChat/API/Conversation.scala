package com.aqualung.anonchat
package AnonChat.API

import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.{BadRequest, InternalServerError, OK}
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import AnonChat.Domain.Aggregates.ConversationAggregate.SessionHandler._
import AnonChat.Domain.Aggregates.ConversationAggregate.PersistentEventSourcedBehavior.ConversationHistory
import AnonChat.Domain.Entities.{ConversationID, UserID}

import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait ConversationRestEntities
case class PostMessageRequest(userID: UserID, message: String) extends ConversationRestEntities
case class PostMessageResponse(message: String)                extends ConversationRestEntities
case class PostMessageError(message: String)                   extends ConversationRestEntities
case class GetHistoryRequest(requester: UserID)                extends ConversationRestEntities
case class GetHistoryResponse(history: List[(UserID, String)]) extends ConversationRestEntities

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val postMessageRequestFormat       = jsonFormat2(PostMessageRequest)
  implicit val postMessageResponseFormat      = jsonFormat1(PostMessageResponse)
  implicit val postMessageErrorResponseFormat = jsonFormat1(PostMessageError)
  implicit val getHistoryRequestFormat        = jsonFormat1(GetHistoryRequest)
  implicit val getHistoryResponseFormat       = jsonFormat1(GetHistoryResponse)
}

class ConversationRoutes(sessionHandler: ActorRef[SessionHandlerCommand])(implicit
    val system: ActorSystem[_]
) extends JsonSupport {

  implicit val executionContext         = system.executionContext
  private implicit val timeout: Timeout = 3.seconds

  def getSession(conversationID: ConversationID, userID: UserID): Future[SessionEvent] =
    sessionHandler.ask(GetSession(conversationID, userID, _))

  def postMessage(conversationID: ConversationID, sender: UserID, message: String)(
      session: ActorRef[SessionCommand]
  ): Unit =
    session ! PostMessage(conversationID, sender, message)

  def getHistory(conversationID: ConversationID, requester: UserID)(
      session: ActorRef[GetHistory]
  ): Future[ConversationHistory] =
    session.ask(GetHistory(conversationID, requester, _))

  val version = "v1"

  lazy val conversationRoutes: Route = {
    concat(
      pathPrefix(version / "conversation" / LongNumber) { conversationID =>
        post {
          entity(as[PostMessageRequest]) {
            pmr =>
              val maybeSessionGranted: Future[SessionEvent] =
                getSession(conversationID, pmr.userID)

              onComplete(maybeSessionGranted) {
                case Success(SessionGranted(session)) =>
                  postMessage(conversationID, pmr.userID, pmr.message)(session)
                  complete(OK, PostMessageResponse(pmr.message))
                case Failure(ex) =>
                  complete(InternalServerError -> ex)
                case _ => complete(InternalServerError, "An unknown error occurred")
              }
          }
        }
      },
      pathPrefix(version / "conversation" / LongNumber) { conversationID =>
        get {
          entity(as[GetHistoryRequest]) {
            ghr =>
              val maybeSessionGranted: Future[SessionEvent] =
                getSession(conversationID, ghr.requester)

              onComplete(maybeSessionGranted) {
                case Success(SessionGranted(session)) =>
                  val maybeConversationState =
                    getHistory(conversationID, ghr.requester)(session)
                  onComplete(maybeConversationState) {
                    case Success(
                          ConversationHistory(
                            data: scala.collection.immutable.Map[ConversationID, List[
                              (UserID, String)
                            ]]
                          )
                        ) =>
                      complete(OK, GetHistoryResponse(data(conversationID)))
                    case Failure(_) =>
                      complete(BadRequest, "Invalid conversation ID supplied")
                    case _ =>
                      complete(InternalServerError)
                  }
                case Failure(ex) =>
                  complete(InternalServerError -> ex)
                case _ => complete(InternalServerError, "An unknown error occurred")
              }
          }
        }
      }
    )
  }
}
