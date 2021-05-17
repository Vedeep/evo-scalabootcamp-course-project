package server.games

import fs2.Stream
import io.circe.{Decoder, Encoder, Json}
import server.common.EntityId
import server.players.Player

object Games {

  trait Game[F[+_]] {
    def id: EntityId

    def start: F[Unit]

    def stop: F[Unit]

    def getState: F[GameState]

    def getInfo: F[GameInfo]

    def subscribe(subscriber: Player): Stream[F, GameEvent[Any]]
  }

  case class GameEvent[A](
    target: GameEventTarget,
    data: A
  )

  trait GameEventTarget
  object GameEventTargets {
    case class TargetPlayer(id: EntityId) extends GameEventTarget
    case object TargetAll extends GameEventTarget
  }

  trait GameState

  trait GameInfo {
    def id: EntityId

    def playerCount: Int

    def maxPlayerCount: Int

    def gameType: GameType
  }

  sealed trait GameType

  object GameTypes {

    case object BlackJackType extends GameType {
      override def toString: String = "black-jack"
    }

    object implicits {
      implicit val gameTypeEncoder: Encoder[GameType] = (a: GameType) => Json.fromString(a.toString)
    }

  }

  trait GameStatus

  object GameStatuses {

    case object New extends GameStatus {
      override def toString: String = "new"
    }

    case object Started extends GameStatus {
      override def toString: String = "started"
    }

    case object Ended extends GameStatus {
      override def toString: String = "ended"
    }

    object implicits {
      implicit val gameStatusEncoder: Encoder[GameStatus] =
        status => Json.fromString(status.toString)

      implicit val gameStatusDecoder: Decoder[GameStatus] = Decoder.decodeString.emap {
        case "new" => Right(New)
        case "started" => Right(Started)
        case "ended" => Right(Ended)
        case _ => Left("Unknown game status")
      }
    }

  }

}
