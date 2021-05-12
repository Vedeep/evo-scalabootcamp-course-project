package server.websocket

import cats.{Applicative, MonadError}
import cats.effect.Sync
import cats.syntax._
import cats.implicits._
import fs2.concurrent.Queue
import io.circe.Decoder
import org.http4s.websocket.WebSocketFrame
import server.players.Player

trait WebSocketRouter[F[_]] {
  def execute(message: String): F[Unit]
}

trait WebSocketSession[F[_]] {
  def player: Player
  def queue: Queue[F, WebSocketFrame]
}

object WebSocketRouter {
  import WSCommands._
  import Errors._

  import io.circe.jawn.decode
  import org.http4s.circe.CirceEntityCodec._
  import io.circe.generic.auto._
  import io.circe.syntax._

  private def decodeMessage[T : Decoder](message: String): Either[WSError, T] =
    decode[T](message).leftMap(e => WrongIncomingParams)

  private def parseMessage(message: String)(implicit D: Decoder[WSCommand]): Either[WSError, WSCommand] =
    decodeMessage[Command](message).flatMap { c =>
      c.command match {
        case "GetTables"  => decodeMessage[GetTables](message)
        case _            => Left(CommandIsNotFound)
      }
    }

  private def getErrorMessage(error: WSError): String = error match {
    case WrongIncomingParams => "Wrong incoming params"
    case CommandIsNotFound => "Command is not found"
    case UserIsNotFound => "User is not found"
  }

  private def formatError(error: WSError): WebSocketError =
    WebSocketError(getErrorMessage(error))

  def make[F[_] : Sync](session: WebSocketSession[F]): WebSocketRouter[F] = new WebSocketRouter[F] {
    override def execute(message: String): F[Unit] = {
      parseMessage(message) match {
        case Right(command) => command match {
          case GetTables(id, command) =>
              session.queue.enqueue1(WebSocketFrame.Text("Response"))

//          case _ => Applicative[F].pure(WebSocketFrame.Text(formatError(CommandIsNotFound).asJson.noSpaces))
        }

//        case Left(e) => Applicative[F].pure(WebSocketFrame.Text(formatError(e).asJson.noSpaces))
      }
    }
  }

  def makeSession[F[_]](player: Player, queue: Queue[F, WebSocketFrame]): WebSocketSession[F] = new WebSocketSession[F] {
    override def player: Player = player

    override def queue: Queue[F, WebSocketFrame] = queue
  }
}

sealed trait WSCommand {
  def id: Int
  def command: String
}

object WSCommands {
  case class Command(id: Int, command: String) extends WSCommand
  case class Ping(id: Int, command: String) extends WSCommand
  case class GetTables(id: Int, command: String) extends WSCommand
}

trait WSError
case class WebSocketError(message: String, `type`: String = "error")

object Errors {
  case object WrongIncomingParams extends WSError
  case object CommandIsNotFound extends WSError
  case object UserIsNotFound extends WSError
}
