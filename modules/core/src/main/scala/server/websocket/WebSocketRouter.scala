package server.websocket

import cats.data.{EitherT, OptionT}
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Fiber, Sync}
import cats.syntax.all._
import cats.effect.syntax.concurrent._
import io.circe.Json
import org.typelevel.log4cats.Logger
import server.common.Finance.Amount
import server.database.WalletBalance
import server.games.{AddCardOptions, AddStakeOptions, BlackJackJoinResult, BlackJackLeaveResult, JoinGameResult, LeaveGameOptions, LeaveGameResult}
import server.players.PlayerService
import server.wallets.WalletService
import fs2.Stream
import fs2.concurrent.Queue
import io.circe.{Decoder, Encoder}
import org.http4s.websocket.WebSocketFrame
import server.common.EntityId
import server.common.EntityId.implicits._
import server.players.Player
import server.common.Errors.{AppError, WSError, getErrorMessage}
import server.common.Errors.WSErrors._
import server.common.Errors.GameErrors._
import server.games.BlackJack.{BlackJackGameState, BlackJackGameStateRound}
import server.games.Games._
import server.games.{GamesService, GamesStore, JoinGameOptions}
import server.parser.JsonParser

trait WebSocketRouter[F[_]] {
  def execute(message: String, session: WebSocketSession[F]): F[Unit]
}

trait WebSocketSession[F[_]] {
  def player: Player
  def queue: Queue[F, WebSocketFrame]
  def send[A : Encoder](message: A): F[Unit]
  def reply[A : Encoder](id: Int, `type`: WSResponseType, result: A): F[Unit]
  def replyError(id: Int, error: AppError): F[Unit]
  def joinGame(result: JoinGameResult): F[Unit]
  def leaveGame(result: LeaveGameResult): F[Unit]
  def addSubscription(fiber: Fiber[F, Unit]): F[EntityId]
  def removeSubscription(subid: EntityId): F[Unit]
  def destroy(): F[Unit]
}

object WebSocketRouter {
  import WSCommands._
  import WSResponseStatuses._
  import WSResponseStatuses.implicits._
  import WSResponseTypes.implicits._
  import server.common.Finance.implicits._

  import io.circe.jawn.decode
  import io.circe.generic.auto._
  import io.circe.syntax._

  private def decodeMessage[T : Decoder](message: String): Either[WSError, T] =
    decode[T](message).leftMap(e => WrongIncomingParams)

  private def parseMessage(message: String)(implicit D: Decoder[WSCommand]): Either[WSError, WSCommand] =
    decodeMessage[Command](message).flatMap { c =>
      c.command match {
        case "Ping"         => decodeMessage[Ping](message).leftMap(_ => WrongIncomingParams)
        case "GetUserInfo"  => decodeMessage[GetUserInfo](message).leftMap(_ => WrongIncomingParams)
        case "GetGames"     => decodeMessage[GetGames](message).leftMap(_ => WrongIncomingParams)
        case "JoinGame"     => decodeMessage[JoinGame](message).leftMap(_ => WrongIncomingParams)
        case "LeaveGame"    => decodeMessage[LeaveGame](message).leftMap(_ => WrongIncomingParams)
        case "AddStake"     => decodeMessage[AddStake](message).leftMap(_ => WrongIncomingParams)
        case "AddCard"      => decodeMessage[AddCard](message).leftMap(_ => WrongIncomingParams)
        case "GetGame"      => decodeMessage[GetGame](message).leftMap(_ => WrongIncomingParams)
        case "Subscribe"    => decodeMessage[Subscribe](message).leftMap(_ => WrongIncomingParams)
        case "Unsubscribe"  => decodeMessage[Unsubscribe](message).leftMap(_ => WrongIncomingParams)
        case _              => Left(CommandIsNotFound)
      }
    }

  def make[F[+_] : Sync : Concurrent]
  (
    playerService: PlayerService[F],
    walletService: WalletService[F],
    gamesService: GamesService[F],
    gamesStore: GamesStore[F]
  ): WebSocketRouter[F] = new WebSocketRouter[F] {
    private def getUserInfo(reqId: Int, session: WebSocketSession[F]): F[Unit] =
      (for {
        balance <- EitherT(walletService.getBalance(session.player.wallet.id))
        _       <- EitherT.liftF[F, AppError, Unit](
          session.reply(
            reqId,
            WSResponseTypes.UserInfo,
            JsonParser.encodePlayerInfo(
              session.player.copy(
                wallet = session.player.wallet.copy(
                  balance = WalletBalance(balance)
                )
              )
            )
          )
        )
      } yield ()).value.void

    private def getGames(reqId: Int, session: WebSocketSession[F]): F[Unit] = for {
      games <- gamesStore.getGameList.flatMap(_.traverse(_.getInfo))
      _ <- session.reply(reqId, WSResponseTypes.Games, games.map(JsonParser.encodeGameInfo))
    } yield ()

    private def getGame(reqId: Int, gameId: EntityId, session: WebSocketSession[F]): F[Unit] =
      gamesStore.getGame(gameId).flatMap {
        case Some(game) => game.getState.flatMap {
          case state: BlackJackGameState =>
            session.reply(reqId, WSResponseTypes.Game, JsonParser.encodeGameState(state))

          case _ =>
            session.replyError(reqId, WrongIncomingParams)
        }

        case None =>
          session.replyError(reqId, GameIsNotFound)
      }

    private def joinGame(reqId: Int, session: WebSocketSession[F], gameId: EntityId, seatId: EntityId): F[Unit] =
      (for {
        game    <- EitherT.fromOptionF(gamesStore.getGame(gameId), GameIsNotFound)
        options = JoinGameOptions(Some(seatId))
        result  <- EitherT[F, AppError, JoinGameResult](gamesService.joinGame(game, session.player, options))
        _       <- EitherT.liftF[F, AppError, Unit](session.joinGame(result))
      } yield result).value.flatMap {
        case Right(result) => session.reply(reqId, WSResponseTypes.JoinedGame, JsonParser.encodeJoinGameResult(result))
        case Left(e)       => session.replyError(reqId, e)
      }

    private def leaveGame(reqId: Int, session: WebSocketSession[F], gameId: EntityId, seatId: EntityId): F[Unit] =
      (for {
        game    <- EitherT.fromOptionF(gamesStore.getGame(gameId), GameIsNotFound)
        options = LeaveGameOptions(Some(seatId))
        result  <- EitherT[F, AppError, LeaveGameResult](gamesService.leaveGame(game, session.player, options))
        _       <- EitherT.liftF[F, AppError, Unit](session.leaveGame(result))
      } yield result).value.flatMap {
        case Right(result) => session.reply(reqId, WSResponseTypes.JoinedGame, JsonParser.encodeLeaveGameResult(result))
        case Left(e)       => session.replyError(reqId, e)
      }

    private def onGameEvent(reqId: Int, session: WebSocketSession[F])(gameEvent: GameEvent[Any]): Stream[F, Unit] =
      Stream.eval {
        gameEvent.data match {
          case state: BlackJackGameState => session.reply(
            reqId,
            WSResponseTypes.Game,
            JsonParser.encodeGameState(state)
          )
          case _ => Concurrent[F].pure()
        }
      }

    private def subscribeGame(reqId: Int, session: WebSocketSession[F], gameId: EntityId): F[Unit] =
      (for {
        game    <- EitherT.fromOptionF[F, AppError, Game[F]](gamesStore.getGame(gameId), GameIsNotFound)
        sub     <- EitherT.liftF[F, AppError, Fiber[F, Unit]](game.subscribe(session.player)
          .flatMap(onGameEvent(reqId, session)).compile.drain.start)
        subId   <- EitherT.liftF[F, AppError, EntityId](session.addSubscription(sub))
        state   <- EitherT.liftF[F, AppError, GameState](game.getState)
        _       <- EitherT.liftF[F, AppError, Unit](session.reply[EntityId](reqId, WSResponseTypes.Subscribed, subId))
        _       <- EitherT.liftF[F, AppError, Unit](
                    session.reply(reqId, WSResponseTypes.Game, JsonParser.encodeGameState(state))
                  )
      } yield ()).leftSemiflatMap(session.replyError(reqId, _)).value.void

    private def unsubscribeGame(reqId: Int, session: WebSocketSession[F], subId: EntityId): F[Unit] =
      for {
        _ <- session.removeSubscription(subId)
        _ <- session.reply(reqId, WSResponseTypes.Unsubscribed, ())
      } yield ()

    private def addStake(reqId: Int, session: WebSocketSession[F], gameId: EntityId, handId: EntityId, amount: Amount) =
      (for {
        game   <- EitherT(gamesStore.getGame(gameId).map(_.toRight(GameIsNotFound)))
        result <- EitherT(gamesService.addStake(game, session.player, AddStakeOptions(amount, handId.some)))
      } yield result).value.flatMap {
        case Right(result) => session.reply(reqId, WSResponseTypes.StakeAdded, JsonParser.encodeGameState(result))
        case Left(e)       => session.replyError(reqId, e)
      }

    private def addCard(reqId: Int, session: WebSocketSession[F], gameId: EntityId, handId: EntityId): F[Unit] =
      (for {
        game   <- EitherT(gamesStore.getGame(gameId).map(_.toRight(GameIsNotFound)))
        result <- EitherT(gamesService.addCard(game, session.player, AddCardOptions(handId.some)))
      } yield result).value.flatMap {
        case Right(result) => session.reply(reqId, WSResponseTypes.StakeAdded, JsonParser.encodeGameState(result))
        case Left(e)       => session.replyError(reqId, e)
      }

    override def execute(message: String, session: WebSocketSession[F]): F[Unit] = {
      val parsed = parseMessage(message)
      println(parsed)
      parsed match {
        case Right(command) => command match {
          case Ping(id, _) => session.reply(id, WSResponseTypes.Pong, None)

          case GetUserInfo(id, _) => getUserInfo(id, session)

          case GetGames(id, _) => getGames(id, session)

          case GetGame(id, _, GetGameParams(gameId)) => getGame(id, gameId, session)

          case JoinGame(id, _, JoinGameParams(gameId, seatId)) => joinGame(id, session, gameId, seatId)

          case LeaveGame(id, _, LeaveGameParams(gameId, seatId)) => leaveGame(id, session, gameId, seatId)

          case AddStake(id, _, AddStakeParams(gameId, handId, amount)) => addStake(id, session, gameId, handId, amount)

          case AddCard(id, _, AddCardParams(gameId, handId)) => addCard(id, session, gameId, handId)

          case Subscribe(id, _, SubscribeParams(gameId)) => subscribeGame(id, session, gameId)

          case Unsubscribe(id, _, UnsubscribeParams(subId)) => unsubscribeGame(id, session, subId)

          case _ => session.replyError(command.id, CommandIsNotFound)
        }

        case Left(e) => session.replyError(0, e)
      }
    }
  }

  def makeSession[F[+_] : Sync : Logger : Concurrent]
  (
    gamesService: GamesService[F],
    gamesStore: GamesStore[F],
    _player: Player,
    _queue: Queue[F, WebSocketFrame]
  ): F[WebSocketSession[F]] = {
    for {
      subscriptions <- Ref.of[F, Map[EntityId, Fiber[F, Unit]]](Map.empty)
      games         <- Ref.of[F, List[JoinGameResult]](Nil)

      session = new WebSocketSession[F] {
        override def player: Player = _player

        override def queue: Queue[F, WebSocketFrame] = _queue

        override def send[A: Encoder](message: A): F[Unit] = queue.enqueue1(WebSocketFrame.Text(message.asJson.noSpaces))

        override def reply[A : Encoder](id: Int, `type`: WSResponseType, result: A): F[Unit] =
          send(
            WSResponseWrapper[A](
              Some(id),
              Ok,
              `type`,
              result.some
            )
          )

        override def replyError(id: Int, error: AppError): F[Unit] =
          send(
            WSResponseWrapper[String](
              Some(id),
              Error,
              WSResponseTypes.Error,
              None,
              getErrorMessage(error).some
            )
          )

        override def addSubscription(fiber: Fiber[F, Unit]): F[EntityId] =
          for {
            subId <- EntityId.of[F]
            _     <- subscriptions.update { subs =>
              subs + (subId -> fiber)
            }
            subs  <- subscriptions.get
            _     <- Logger[F].info(s"Player subscriptions: ${subs.keys.mkString(",")}")
          } yield subId

        override def removeSubscription(subId: EntityId): F[Unit] =
          for {
            _     <- subscriptions.update { subs =>
                        subs - subId
                      }
            subs  <- subscriptions.get
            _     <- Logger[F].info(s"Player subscriptions: ${subs.keys.mkString(",")}")
          } yield ()

        override def destroy: F[Unit] = {
          for {
            subs   <- subscriptions.get
            _      <- subs.values.toList.traverse(_.cancel)
            _      <- subscriptions.update(_ => Map.empty)
            pgames <- games.get
            _      <- pgames.traverse {
              case BlackJackJoinResult(gameId, _, seatId) =>
                (for {
                  game <- OptionT(gamesStore.getGame(gameId))
                  _    <- OptionT[F, LeaveGameResult](
                    gamesService.leaveGame(game, player, LeaveGameOptions(Some(seatId))).map(_.toOption)
                  )
                } yield ()).value
            }
            _      <- games.update(_ => List.empty[JoinGameResult])
          } yield ()
        }

        override def joinGame(result: JoinGameResult): F[Unit] =
          games.update { state =>
            result :: state
          }

        override def leaveGame(result: LeaveGameResult): F[Unit] =
          games.update { state =>
            state.filterNot {
              case BlackJackJoinResult(gameId, _, seatId) =>
                result match {
                  case BlackJackLeaveResult(gameId2, _, seatId2) =>
                    gameId == gameId2 && seatId == seatId2

                  case _ => false
                }
            }
          }

      }
    } yield session
  }
}

sealed trait WSCommand {
  def id: Int
  def command: String
}

object WSCommands {
  case class Command(id: Int, command: String) extends WSCommand
  case class Ping(id: Int, command: String) extends WSCommand
  case class GetUserInfo(id: Int, command: String) extends WSCommand
  case class GetGames(id: Int, command: String) extends WSCommand
  case class GetGameParams(gameId: EntityId)
  case class GetGame(id: Int, command: String, params: GetGameParams) extends WSCommand
  case class JoinGameParams(gameId: EntityId, seatId: EntityId)
  case class JoinGame(id: Int, command: String, params: JoinGameParams) extends WSCommand
  case class LeaveGameParams(gameId: EntityId, seatId: EntityId)
  case class LeaveGame(id: Int, command: String, params: LeaveGameParams) extends WSCommand
  case class SubscribeParams(gameId: EntityId)
  case class Subscribe(id: Int, command: String, params: SubscribeParams) extends WSCommand
  case class UnsubscribeParams(subId: EntityId)
  case class Unsubscribe(id: Int, command: String, params: UnsubscribeParams) extends WSCommand
  case class AddStakeParams(gameId: EntityId, handId: EntityId, amount: Amount)
  case class AddStake(id: Int, command: String, params: AddStakeParams) extends WSCommand
  case class AddCardParams(gameId: EntityId, handId: EntityId)
  case class AddCard(id: Int, command: String, params: AddCardParams) extends WSCommand
}

sealed trait WSResponseStatus
object WSResponseStatuses {
  case object Ok extends WSResponseStatus {
    override def toString: String = "ok"
  }
  case object Error extends WSResponseStatus {
    override def toString: String = "error"
  }

  object implicits {
    implicit val statusEncoder: Encoder[WSResponseStatus] = status => Json.fromString(status.toString)
  }
}

sealed trait WSResponseType
object WSResponseTypes {
  case object Error extends WSResponseType
  case object Pong extends WSResponseType
  case object UserInfo extends WSResponseType
  case object Games extends WSResponseType
  case object Game extends WSResponseType
  case object JoinedGame extends WSResponseType
  case object Subscribed extends WSResponseType
  case object Unsubscribed extends WSResponseType
  case object StakeAdded extends WSResponseType
  case object CardAdded extends WSResponseType

  object implicits {
    implicit val encodeResponseType: Encoder[WSResponseType] =
      respType => Json.fromString(
        respType
        .getClass
        .getSimpleName
        .replaceAll("[^A-z]+", "")
      )
  }
}

sealed trait WSResponse[T] {
  def id: Option[Int]
  def status: WSResponseStatus
  def `type`: WSResponseType
  def result: Option[T]
  def error: Option[String]
}

case class WSResponseWrapper[T] private
(
  id: Option[Int],
  status: WSResponseStatus,
  `type`: WSResponseType,
  result: Option[T],
  error: Option[String] = None
) extends WSResponse[T]

object WSResponses {
//  case class Pong(id: Option[Int], status: WSResponseStatus, result: Unit) extends WSResponse[Unit]
}




