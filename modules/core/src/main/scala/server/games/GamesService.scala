package server.games

import cats.data.EitherT
import cats.implicits._
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import fs2.concurrent.{SignallingRef, Topic}
import server.games.Games.{Game, GameEvent, GameState, GameType, GameTypes}
import server.common.EntityId
import server.common.Errors._
import server.common.Errors.WSErrors.WrongIncomingParams
import server.common.Finance.Amount
import server.games.Cards.CardDeck
import server.games.Games.GameEventTargets.TargetAll
import server.players.Player
import server.wallets.WalletService

sealed trait GamesStore[F[+_]] {
  def getGame(id: EntityId): F[Option[Game[F]]]
  def getGameList: F[List[Game[F]]]
  def getGameListByType(gameType: GameType): F[List[Game[F]]]
  def addGame(game: Game[F]): F[Unit]
  def removeGame(game: Game[F]): F[Unit]
}

sealed trait GamesService[F[+_]] {
  def joinGame
  (
    game: Game[F],
    player: Player,
    params: JoinGameOptions
  ): F[Either[AppError, JoinGameResult]]
  def leaveGame
  (
    game: Game[F],
    player: Player,
    params: LeaveGameOptions
  ): F[Either[AppError, LeaveGameResult]]
  def createGame[C](gameType: GameType, config: C): F[Game[F]]
  def createStore: F[GamesStore[F]]
  def addStake(game: Game[F], player: Player, options: AddStakeOptions): F[Either[AppError, GameState]]
  def addCard(game: Game[F], player: Player, options: AddCardOptions): F[Either[AppError, GameState]]
}

trait JoinGameOptions {
  def seatId: Option[EntityId]
}

object JoinGameOptions {
  def apply(_seatId: Option[EntityId]): JoinGameOptions = new JoinGameOptions {
    override val seatId: Option[EntityId] = _seatId
  }
}

trait LeaveGameOptions {
  def seatId: Option[EntityId]
}

object LeaveGameOptions {
  def apply(_seatId: Option[EntityId]): LeaveGameOptions = new LeaveGameOptions {
    override val seatId: Option[EntityId] = _seatId
  }
}

sealed trait JoinGameResult
case class BlackJackJoinResult(gameId: EntityId, playerId: EntityId, seatId: EntityId) extends JoinGameResult

sealed trait LeaveGameResult
case class BlackJackLeaveResult(gameId: EntityId, playerId: EntityId, seatId: EntityId) extends LeaveGameResult

case class AddStakeOptions(amount: Amount, handId: Option[EntityId] = None)
case class AddCardOptions(handId: Option[EntityId] = None)


object GamesService {

  def make[F[+_] : Concurrent : Timer](walletService: WalletService[F]) =
    new GamesServiceImpl[F](walletService)

  class GamesServiceImpl[F[+_] : Concurrent : Timer](walletService: WalletService[F])
    extends GamesService[F] {
    import BlackJack._
    import GameTypes._

    def getGameType(game: Game[F]): GameType = game match {
      case _: BlackJackGame[F] => BlackJackType
    }

    override def joinGame
    (
      game: Game[F],
      player: Player,
      params: JoinGameOptions
    ): F[Either[AppError, JoinGameResult]] =
      getGameType(game) match {
        case BlackJackType =>
          (for {
            seatId <- EitherT.fromOption[F](params.seatId, WrongIncomingParams)
            _ <- EitherT[F, AppError, Unit](
              game.asInstanceOf[BlackJackGame[F]].join(player, seatId)
            )
          } yield BlackJackJoinResult(game.id, player.id, seatId)).value
      }

    override def leaveGame
    (
      game: Game[F],
      player: Player,
      params: LeaveGameOptions
    ): F[Either[AppError, LeaveGameResult]] =
      getGameType(game) match {
        case BlackJackType =>
          (for {
            seatId <- EitherT.fromOption[F](params.seatId, WrongIncomingParams)
            _ <- EitherT[F, AppError, Unit](
              game.asInstanceOf[BlackJackGame[F]].leave(player, seatId)
            )
          } yield BlackJackLeaveResult(game.id, player.id, seatId)).value
      }

    override def createGame[C]
    (
      gameType: GameType,
      config: C,
    ): F[Game[F]] = {
      for {
        id          <- EntityId.of[F]
        topic       <- Topic[F, GameEvent[Any]](GameEvent(TargetAll, ())) // @TODO
        interrupter <- SignallingRef[F, Boolean](false)
        gameState   <- Ref.of[F, BlackJackGameState](BlackJackGameState(config.asInstanceOf[BlackJackConfig]))
        deck        <- CardDeck.make[F]
        game = gameType match {
          case BlackJackType =>
            val calculatorConfig = new BlackJack.CalculatorConfig {
              override val maxPoints: Points = 21
            }

            new BlackJackGame[F](
              id,
              walletService,
              config.asInstanceOf[BlackJackConfig],
              BlackJack.Calculator(calculatorConfig),
              topic,
              interrupter,
              gameState,
              deck
            )
        }
      } yield game
    }

    override def createStore: F[GamesStore[F]] = for {
      games <- Ref.of[F, Set[Game[F]]](Set.empty)
      store = new GamesStore[F] {
        override def getGame(id: EntityId): F[Option[Game[F]]] =
          games.get.map(_.find(_.id == id))

        override def getGameList: F[List[Game[F]]] =
          games.get.map(_.toList)

        override def getGameListByType(gameType: GameType): F[List[Game[F]]] =
          games.get.map(_.filter(getGameType(_) == gameType).toList)

        override def addGame(game: Game[F]): F[Unit] =
          games.modify { state =>
            (state + game, ())
          }

        override def removeGame(game: Game[F]): F[Unit] =
          games.modify { state =>
            (state - game, ())
          }
      }
    } yield store

    override def addStake(game: Game[F], player: Player, options: AddStakeOptions): F[Either[AppError, GameState]] =
      game match {
        case gameInstance: BlackJackGame[F] =>
          options.handId match {
            case Some(handId) =>
              (for {
                _ <- EitherT(gameInstance.addStake(handId, player, options.amount))
                state <- EitherT.liftF[F, AppError, GameState](gameInstance.getState)
              } yield state).value

            case None =>
              Concurrent[F].pure(WrongIncomingParams.asLeft)
          }
      }

    override def addCard(game: Game[F], player: Player, options: AddCardOptions): F[Either[AppError, GameState]] =
      game match {
        case gameInstance: BlackJackGame[F] =>
          options.handId match {
            case Some(handId) =>
              gameInstance.addCard(handId, player)

            case None =>
              Concurrent[F].pure(WrongIncomingParams.asLeft)
          }
      }
  }

}
