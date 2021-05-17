package server.games

import cats.data.EitherT
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._

import scala.concurrent.duration._
import server.players.Player
import server.common.Errors.{AppError, BlackJackError}
import server.common.Errors.BlackJackErrors._

import scala.annotation.tailrec
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import server.common.EntityId
import server.games.Games._
import server.games.Games.GameStatuses._
import server.games.Games.GameEventTargets._
import server.games.Cards._
import server.common.Finance._
import server.database.TransactionTypes
import server.database.TransactionTypes.Withdrawal
import server.wallets.{WalletService, WalletTransaction, WalletTransactionAmount, WalletTransactionResult}

import java.time.Instant

/**
 * Задача:
 *
 * Сервер для игры в блэк-джек
 *
 * Технологии:
 * Http4s, Akka, Doobie
 * H2 (prod postgres), Casandra (prod kafka)
 *
 * Flow:
 * 1. Пользователь авторизуется и получает Jwt Token (Rest + AuthService)
 * 2. Пользователь подключается по WebSocket используюя JwtToken
 * 3. Пользователь получает список столов через WebSocket: GetTableList -> Response(tables)
 * 4. Пользователь получает данные стола: GetTable(id) -> Response(..., places)
 * 5. Пользователь начинает играть за столом: JoinTable -> Response(placeId, handId)
 * 6. Пользователь получает ответ или ошибку (в случае если стол занят или нет денег на счету)
 * 7. Сервер отправляет сообщение о начале раунда: RoundStart
 * 8. Пользователь должен сделать ставку в течении 15 секунд
 * (фронт автоматически отправляет сообщение): SeatBet
 * сервер ждет 20+- секунд или все сообщения SeatBet
 * 9. Сервер отправляет сообщения пользователям о начале раздачи с сумой их ставки: BetSeatd
 * 10. Сервер отправляет сообщение о раздаче карт всем пользователям: CardAdded(handId, card)
 * 11. Сервер отправляет сообщения пользователям с возможными действийми: -> PossibleActions
 * Возможные действия: Hit, Stand, Split
 * 12. Пользователь выбирает действие в течении 30 секунд: doAction?...
 * 13. Сервер реагирует на действие, обнуляет счетчик, ждет дальнейшего действия
 * 14. Если игрок выбирает Stand он больше не может получаеть действия в этом раунде
 * 15. Если не осталось больше возможных действий или вышло время, тогда время сервер заканчивает раунд
 * 16. Сервер рассчитывает выигрыш пользователей и начисляет на баланс
 * 17. Также сервер отправляет сообщение о завершении раунда: -> RoundEnded(balance)
 *
 * WS Requests:
 * GetTableList - получение списка столов
 * GetTable(id) - информация о столе
 * JoinTable(id, placeId) -> Response(handId) - подключение к столу
 * SeatBet(handId, amount)
 * Hit - добавить карту
 * Stand - не добавлять больше карт
 * Split - разбить стаку на 2
 *
 * WS Responses:
 * TableList(tables) - список столов
 * TableData(...) - информация о столе
 * TableJoined - подключился к столу
 * TableLeft - отключился от стола
 * RoundStarted - сообщение о начале раунда (всем пользователям)
 * BetSeatd - ставка принята (всем пользователям)
 * CardAdded(handId, card, points) - в руку добавлена карта
 * HandState(possibleActions, ...) - состояине руки, возможные действия
 * RoundEnded(balance,...) - раунд завершен, новый баланс игрока
 *
 * Services:
 * WalletService(WalletRepo, TransactionRepo) - работа с кошельком
 * UserService - данные пользователя
 * GamesService - список столов, создание
 *
 *
 */

object BlackJack {
  type Points = Int

  /**
   * 1. Players joins a game
   * 2. Dealer begin a round (AutoDealer can checks it every 10 seconds)
   * 3. Players do a bids
   * 4. Dealer add any cards
   * 5. Player can: get more cards / split a hand / freeze a hand
   * 6. Dealer send a round complete
   * 7. Players get any results based on calculator result
   *
   */

  /**
   * Round
   */

  /**
   * Split:
   * 1. Player can split bet when he has duplicate cards
   * 2. 2 ways: free split (bet amount devide by 2) or split (amount equals previous bet withdraw from player wallet)
   * 3. When player split a bet than he will have 2 hands (it same as 2 player on a table)
   */

  trait BlackJackConfig {
    def seatCount: Int
    def playerSeatsLimit: Int
    def roundStateSeconds: Int
    def roundIntervalSeconds: Int
  }

  private[games] class BlackJackGame[F[+_] : Concurrent : Timer]
  (
    val id: EntityId,
    private val walletService: WalletService[F],
    private val config: BlackJackConfig,
    private val calculator: Calculator,
    private val topic: Topic[F, GameEvent[Any]],
    private val interrupter: SignallingRef[F, Boolean],
    private val state: Ref[F, BlackJackGameState],
    private val deck: CardDeck[F],
  ) extends Game[F]
  {
    private def getPlayerSeats(seats: Map[EntityId, Option[Player]], player: Player): List[EntityId] =
      seats.filter {
        case (_, p) => p.fold(false)(_ == player)
      }.keys.toList

    private def getPlayerSeatCount(seats: Map[EntityId, Option[Player]], player: Player): Int =
      getPlayerSeats(seats, player).size

    private def isRoundStarted(round: Option[BlackJackGameStateRound]): Boolean =
      round.exists(_.state != BlackJackRoundStates.Ended)

    private def isSeatsEmpty(seats: Map[EntityId, Option[Player]]): Boolean =
      !seats.values.exists(_.nonEmpty)

    override def start: F[Unit] = for {
      _ <- Timer[F].sleep(500.milliseconds).flatMap { _ =>
        state.get
      }.flatMap { state =>
        if (isRoundStarted(state.currentRound)) {
          Concurrent[F].handleError(doRound) { e => e.printStackTrace()}
        } else {
          if (state.nextRoundTime.isEmpty)
            scheduleRound
          else if (state.nextRoundTime.exists(_.isBefore(Instant.now())))
            startRound.map(println)
          else
            Concurrent[F].pure()
        }
      }.foreverM[Unit]
    } yield ()

    override def stop: F[Unit] = for {
      _ <- interrupter.set(true)
    } yield ()

    private def sendState: F[Unit] =
      state.get.flatMap { state =>
        topic.publish1(GameEvent(TargetAll, state))
      }

    private def scheduleRound: F[Unit] =
      state.updateMaybe { state =>
        if (!isRoundStarted(state.currentRound) && state.seats.values.exists(_.nonEmpty)) {
          state.copy(
            nextRoundTime = Instant.now().plusSeconds(config.roundIntervalSeconds).some
          ).some
        } else {
          None
        }
      }.flatMap(Concurrent[F].whenA(_)(sendState))

    private def startRound: F[Either[BlackJackError, BlackJackGameStateRound]] =
      state.modifyOr { current =>
        if (isSeatsEmpty(current.seats))
          Left(TableIsEmpty)
        else if (isRoundStarted(current.currentRound))
          Left(RoundAlreadyStarted)
        else {
          val hands: List[Hand] = current.seats
            .foldLeft[List[Hand]](Nil)((acc, seat) => {
              seat match {
                case (seatId, Some(player)) => Hand(seatId, player) :: acc
                case _ => acc
              }
            })

          val handsActions = hands.foldLeft(Map.empty[Hand, List[BlackJackAction]]) { (acc, hand) =>
            acc + (hand -> List(BlackJackActions.AddStake))
          }

          val nextState = Instant.now().plusSeconds(config.roundStateSeconds)

          val round = BlackJackGameStateRound(
            hands = hands,
            handActions = handsActions,
            nextStateTime = nextState
          )

          Right(current.copy(
            nextRoundTime = None,
            currentRound = Some(round)
          ) -> round)
        }
      }.flatMap {
        case result @ Right(_) => sendState *> deck.shuffle *> Concurrent[F].pure(result)
        case error  @ Left(_)  => Concurrent[F].pure(error)
      }

    private def doRound: F[Unit] =
      state.get.flatMap { current =>
        current.currentRound match {
          case Some(round) if round.nextStateTime.isBefore(Instant.now()) =>
            round.state match {
              case BlackJackRoundStates.Stakes => setRoundStateAsCards
              case BlackJackRoundStates.Cards  => endRound
              case _ => Concurrent[F].pure()
            }

          case _ => Concurrent[F].pure()
        }
      }

    private def endRound: F[Unit] =
      stopAcceptingActions *>
      state.get.flatMap { current =>
        current.currentRound match {
          case Some(round) if round.state != BlackJackRoundStates.Ended =>
            for {
              dealerCards <- getDealerCards(round.dealerCards)
              handCards   <- Concurrent[F].delay(round.handCards.filterNot(_._2.isEmpty).toList)
              results = calculator.calculateAll(
                dealerCards,
                handCards
              )
              _ <- state.update { oldState =>
                oldState.copy(
                  currentRound = round.copy(
                    dealerCards = if (handCards.nonEmpty) dealerCards else round.dealerCards,
                    results = if (handCards.nonEmpty) results else round.results,
                    state = BlackJackRoundStates.Ended
                  ).some
                )
              }
              _ <- enrollResults
              _ <- sendState
            } yield ()

          case _ => Concurrent[F].pure()
        }
      }

    private def getCards(count: Int): F[List[Card]] = deck.getCards(count)

    private def getDealerCards(cards: List[Card]): F[List[Card]] = {
      val maxPoints = calculator.getMaxPoints

      (for {
        newCards <- getCards(1).map(_ ++ cards)
        points   <- Concurrent[F].delay(calculator.getWinPoints(newCards))
      } yield (newCards, points)).flatMap {
        case (newCards, points) =>
          if ((points + 6) < maxPoints) getDealerCards(newCards) else Concurrent[F].pure(newCards)
      }
    }

    private def stopAcceptingStakes: F[Unit] =
      state.update { current =>
        current.currentRound match {
          case Some(round) =>
            current.copy(
              currentRound = round.copy(
                handActions = round.handActions.map { ha =>
                  val (hand, actions) = ha
                  (hand, actions.filterNot(_ == BlackJackActions.AddStake))
                }
              ).some
            )

          case None => current
        }
      }

    private def stopAcceptingActions: F[Unit] =
      state.update { current =>
        current.currentRound match {
          case Some(round) =>
            current.copy(
              currentRound = Some(
                round.copy(handActions = Map.empty)
              )
            )

          case None => current
        }
      }

    private def stopAcceptingHandActions(handId: EntityId, playerId: EntityId): F[List[BlackJackAction]] =
      state.modify { current =>
        current.currentRound match {
          case Some(round) =>
            val hand = round.hands.find(hand => hand.id == handId && hand.holder.id == playerId)
            val handActions = hand.flatMap(round.handActions.get)

            (hand, handActions) match {
              case (Some(hand), Some(actions)) =>
                (current.copy(
                  currentRound = round.copy(
                    handActions = round.handActions + (hand -> Nil)
                  ).some
                ), actions)

              case _ => (current, Nil)
            }

          case None => (current, Nil)
        }
      }

    private def startAcceptingHandActions(handId: EntityId): F[Unit] =
      state.update { current =>
        current.currentRound match {
          case Some(round) =>
            val hand = round.hands.find(_.id == handId)

            hand match {
              case Some(hand) =>
                val newActions = getHandActions(current, hand)

                current.copy(
                  currentRound = round.copy(
                    handActions = round.handActions + (hand -> newActions)
                  ).some
                )

              case None => current
            }

          case None => current
        }
      }

    private def extendRoundState(): F[Unit] =
      state.update { current =>
        current.currentRound match {
          case Some(round) => current.copy(
            currentRound = round.copy(nextStateTime = Instant.now().plusSeconds(config.roundStateSeconds)).some
          )
          case None => current
        }
      }

    private def getActiveHands: F[List[Hand]] =
      state.get.map { current =>
        current.currentRound match {
          case Some(round) => round.hands.filter(round.stakes.contains)
          case None => Nil
        }
      }

    private def getHandCards(hands: List[Hand]): F[Map[Hand, List[Card]]] =
      for {
        cards <- getCards(hands.size * 2)
        result <- Concurrent[F].delay(hands.zipWithIndex.foldLeft[Map[Hand, List[Card]]](Map.empty) { (acc, item) =>
          val (hand, index) = item
          acc + (hand -> cards.slice(index * 2, index * 2 + 2))
        })
      } yield result

    private def setRoundStateAsCards: F[Unit] =
      (stopAcceptingStakes *> getActiveHands).flatMap { hands =>
        if (hands.isEmpty)
          endRound
        else
          for {
            handCards   <- getHandCards(hands)
            dealerCards <- getCards(1)
            results     <- Concurrent[F].delay(calculator.calculateAll(dealerCards, handCards.toList, isFinally = false))
            nextTime    <- Concurrent[F].delay(Instant.now().plusSeconds(config.roundStateSeconds))
            _           <- state.update { current =>
              current.currentRound match {
                case Some(round) =>
                  val newState = current.copy(
                    currentRound = Some(
                      round.copy(
                        state = BlackJackRoundStates.Cards,
                        nextStateTime = nextTime,
                        dealerCards = dealerCards,
                        handCards = handCards,
                        results = results
                      )
                    )
                  )

                  newState.copy(
                    currentRound = newState.currentRound.map(
                      _.copy(
                        handActions = round.hands.foldLeft[Map[Hand, List[BlackJackAction]]](Map.empty) { (acc, hand) =>
                          acc + (hand -> getHandActions(newState, hand))
                        }
                      )
                    )
                  )

                case None => current
              }
            }
            _ <- sendState // @TODO
          } yield ()
      }

    private def enrollResults: F[Unit] =
      state.get.flatMap { current =>
        current.currentRound match {
          case Some(round) =>
            for {
              results <- Concurrent[F].pure(
                round.results.filter(r => r.hand.nonEmpty && r.result.nonEmpty && !r.result.contains(Results.Loss))
              )
              _       <- results.traverse { result =>
                val hand = result.hand.get
                round.stakes.get(hand) match {
                  case Some(Amount(stake)) if stake > 0 =>
                    walletService.doTransaction(
                      hand.holder.wallet,
                      WalletTransaction(
                        TransactionTypes.Deposit,
                        WalletTransactionAmount(result.result match {
                          case Some(Results.Won)    => stake * 2
                          case Some(Results.Equals) => stake
                        })
                      )
                    )
                  case _ => Concurrent[F].pure()
                }
              }
            } yield ()

          case None => Concurrent[F].pure()
        }
      }


    def join(player: Player, seatId: EntityId): F[Either[BlackJackError, Unit]] =
      state.modifyOr[BlackJackError, Unit] { current =>
        for {
          seat     <- current.seats.get(seatId).toRight(SeatIsNotFound)
          _        <- seat.toLeft().leftMap(_ => SeatIsBusy)
          _        <- Either.cond(getPlayerSeatCount(current.seats, player) < config.playerSeatsLimit, (), PlayerSeatsExceed)
          newSeats <- (current.seats + (seatId -> player.some)).asRight
          newState <- current.copy(seats = newSeats).asRight
        } yield newState -> ()
      }.flatMap {
        case result @ Right(_) => sendState *> Concurrent[F].pure(result)
        case error  @ Left(_)  => Concurrent[F].pure(error)
      }

    def leave(player: Player, seatId: EntityId): F[Either[BlackJackError, Unit]] =
      state.modifyOr[BlackJackError, Unit] { current =>
        for {
          seat     <- current.seats.get(seatId).toRight(SeatIsNotFound)
          _        <- seat.toRight(NotPlayerSeat).filterOrElse(_ == player, NotPlayerSeat)
          newSeats <- (current.seats + (seatId -> None)).asRight
          newState <- current.copy(seats = newSeats).asRight
        } yield newState -> ()
      }.flatMap {
        case result @ Right(_) => sendState *> Concurrent[F].pure(result)
        case error  @ Left(_)  => Concurrent[F].pure(error)
      }

    private def getHandActions(state: BlackJackGameState, hand: Hand): List[BlackJackAction] = {
      state.currentRound match {
        case Some(round) => round.state match {
          case BlackJackRoundStates.Stakes =>
            if (!round.stakes.contains(hand))
              List(BlackJackActions.AddStake)
            else
              Nil

          case BlackJackRoundStates.Cards if round.stakes.contains(hand) =>
            if (round.handActions.getOrElse(hand, Nil).contains(BlackJackActions.StandCards))
              Nil
            else
              round.results.find(_.hand.contains(hand)) match {
                case Some(result) if result.points >= calculator.getMaxPoints =>
                  Nil
                case _ => List(BlackJackActions.AddCard, BlackJackActions.StandCards)
              }

          case _ => Nil
        }

        case None => Nil
      }
    }

    private def getRoundAndHand
    (
      state: BlackJackGameState,
      handId: EntityId
    ): Either[BlackJackError, (BlackJackGameStateRound, Hand)] =
      for {
        round <- Either.fromOption(state.currentRound, RoundNotStarted)
        hand  <- Either.fromOption(round.hands.find(_.id == handId), HandIsNotFound)
      } yield (round, hand)

    def addStake(handId: EntityId, player: Player, amount: Amount): F[Either[AppError, Unit]] = {
      stopAcceptingHandActions(handId, player.id).flatMap { actions =>
        if (actions.contains(BlackJackActions.AddStake)) {
          sendState *>
          (for {
            roundAndHand <- EitherT[F, AppError, (BlackJackStateRound, Hand)](
              state.get.map(getRoundAndHand(_, handId))
            )
            (_, hand) = roundAndHand

            _ <- EitherT.liftF[F, AppError, Unit](Concurrent[F].pure(println("before trans")))
            _ <- EitherT[F, AppError, WalletTransactionResult](
              walletService.doTransaction(hand.holder.wallet, WalletTransaction(
                Withdrawal,
                WalletTransactionAmount(amount.value)
              )
            ))
            _ <- EitherT.liftF[F, AppError, Unit](Concurrent[F].pure(println("after trans")))
            _ <- EitherT[F, AppError, Unit](state.modifyOr { current =>
              for {
                data <- getRoundAndHand(current, handId)
                (round, hand) = data
              } yield (current.copy(
                currentRound = round.copy(
                  stakes = round.stakes + (hand -> amount)
                ).some
              ), ())
            })
          } yield ()).value
        } else {
          Concurrent[F].pure(ActionIsNotAllowed.asLeft)
        }
      }.flatMap { res =>
        startAcceptingHandActions(handId) *>
        sendState *>
        Concurrent[F].pure(res)
      }
    }

    private def addHandCards(hand: Hand, cards: List[Card]): F[Either[BlackJackError, Unit]] =
      state.modifyOr { current =>
        current.currentRound match {
          case Some(round) =>
            val newCards = round.handCards.getOrElse(hand, Nil) ++ cards
            val newHandCards = round.handCards + (hand -> newCards)
            val results = calculator.calculateAll(round.dealerCards, newHandCards.toList, isFinally = false)

            (current.copy(
              currentRound = round.copy(
                handCards = newHandCards,
                results = results
              ).some
            ), ()).asRight


          case None => RoundNotStarted.asLeft
        }
      }

    def addCard(handId: EntityId, player: Player): F[Either[BlackJackError, BlackJackGameState]] =
      stopAcceptingHandActions(handId, player.id).flatMap { actions =>
        if (actions.contains(BlackJackActions.AddCard))
          state.get.flatMap { current =>
            current.currentRound match {
              case Some(round) =>
                if (round.state != BlackJackRoundStates.Cards)
                  Concurrent[F].pure(ActionIsNotAllowed.asLeft)
                else
                  round.hands.find(_.id == handId) match {
                    case Some(hand) =>
                      (for {
                        cards <- EitherT.liftF[F, BlackJackError, List[Card]](getCards(1))
                        _     <- EitherT[F, BlackJackError, Unit](addHandCards(hand, cards))
                        state <- EitherT.liftF[F, BlackJackError, BlackJackGameState](state.get)
                      } yield  state).value

                    case None => Concurrent[F].pure(HandIsNotFound.asLeft)
                  }

              case None => Concurrent[F].pure(RoundNotStarted.asLeft)
            }
          }
        else
          Concurrent[F].pure(ActionIsNotAllowed.asLeft)
      }.flatMap {
        case result @ Right(_) =>
          startAcceptingHandActions(handId) *>
            extendRoundState *>
            sendState *>
            Concurrent[F].pure(result)

        case error  @ Left(_)  =>
          startAcceptingHandActions(handId) *>
            Concurrent[F].pure(error)
      }

    override def subscribe(subscriber: Player): Stream[F, GameEvent[Any]] =
      topic
        .subscribe(100)
        .interruptWhen(interrupter)
        .filter { event =>
          event.target match {
            case TargetAll => true
            case TargetPlayer(id) => subscriber.id == id
          }
        }

    override def getState: F[BlackJackGameState] =
      state.get

    override def getInfo: F[BlackJackGameInfo] =
      for {
        state <- state.get
        playerCount <- Concurrent[F].pure(state.seats.values.filter(_.isDefined))
      } yield BlackJackGameInfo(id, playerCount.size, config.seatCount, GameTypes.BlackJackType)
  }

  case class BlackJackGameInfo(id: EntityId, playerCount: Int, maxPlayerCount: Int, gameType: GameType)
    extends GameInfo

  trait BlackJackStateRound {
    def dealerCards: List[Card]
    def hands: List[Hand]
    def handCards: Map[Hand, List[Card]]
    def stakes: Map[Hand, Amount]
    def handActions: Map[Hand, List[BlackJackAction]]
  }

  trait BlackJackState extends GameState {
    def seats: Map[EntityId, Option[Player]]
    def status: GameStatus
    def currentRound: Option[BlackJackStateRound]
  }

  case class BlackJackGameStateRound
  (
    hands: List[Hand],
    nextStateTime: Instant,
    dealerCards: List[Card] = Nil,
    handCards: Map[Hand, List[Card]] = Map.empty,
    stakes: Map[Hand, Amount] = Map.empty,
    handActions: Map[Hand, List[BlackJackAction]] = Map.empty,
    state: BlackJackRoundState = BlackJackRoundStates.Stakes,
    results: List[CalculatorResult] = Nil
  ) extends BlackJackStateRound

  case class BlackJackGameState
  (
    seats: Map[EntityId, Option[Player]],
    status: GameStatus,
    currentRound: Option[BlackJackGameStateRound],
    nextRoundTime: Option[Instant] = None
  ) extends BlackJackState

  object BlackJackGameState {
    def apply(config: BlackJackConfig): BlackJackGameState = {
      val seats = (1 to config.seatCount).foldLeft[Map[EntityId, Option[Player]]](Map.empty) { (acc, _) =>
        acc + (EntityId() -> None)
      }

      BlackJackGameState(seats, New, None)
    }
  }

  trait BlackJackAction
  object BlackJackActions {
    case object AddStake extends BlackJackAction {
      override def toString: String = "add-stake"
    }
    case object AddCard extends BlackJackAction {
      override def toString: String = "add-card"
    }
    case object StandCards extends BlackJackAction {
      override def toString: String = "stand-cards"
    }
  }

  trait BlackJackRoundState
  object BlackJackRoundStates {
    case object Stakes extends BlackJackRoundState {
      override def toString: String = "stakes"
    }
    case object Cards  extends BlackJackRoundState {
      override def toString: String = "cards"
    }
    case object Ended  extends BlackJackRoundState {
      override def toString: String = "ended"
    }
  }

  trait CalculatorConfig {
    def maxPoints: Points
  }

  // @TODO private
  trait Calculator {
    import CardValues._

    def config: CalculatorConfig

    private def getCardPoints(card: Card): List[Points] = card.value match {
      case Two    => List(2)
      case Three  => List(3)
      case Four   => List(4)
      case Five   => List(5)
      case Six    => List(6)
      case Seven  => List(7)
      case Eight  => List(8)
      case Nine   => List(9)
      case Ten    => List(10)
      case Jack   => List(10)
      case Queen  => List(10)
      case King   => List(10)
      case Ace    => List(1, 11)
    }

    def getCardsPoints(cards: List[Card]): List[Points] = {
      @tailrec
      def recursive(result: List[Points], cards: List[Card]): List[Points] = {
        cards match {
          case card :: xs => recursive(getCardPoints(card).flatMap { p =>
            result.map(_ + p)
          }, xs)
          case Nil => result
        }
      }

      recursive(result = List(0), cards).distinct
    }

    def getWinPoints(cards: List[Card]): Points = {
      val points = getCardsPoints(cards)
      val winPoints = points.filterNot(_ > config.maxPoints)

      if (winPoints.nonEmpty)
        winPoints.max
      else
        points.min
    }

    def getMaxPoints: Points = config.maxPoints

    def calculateAll
    (
      dealerHand: List[Card],
      playersHands: List[(Hand, List[Card])],
      isFinally: Boolean = true
    ): List[CalculatorResult] = {
      val dealerPoints = getWinPoints(dealerHand)

      CalculatorResult(dealerPoints, None) :: (for {
        (hand, cards) <- playersHands
        points = getWinPoints(cards)
      } yield CalculatorResult(points, points match {
        case _ if !isFinally => None
        case p if p <= config.maxPoints && dealerPoints <= config.maxPoints =>
          if (p < dealerPoints) Results.Loss.some
          else if (p == dealerPoints) Results.Equals.some
          else Results.Won.some
        case p if p <= config.maxPoints && dealerPoints > config.maxPoints =>
          Results.Won.some
        case _ =>
          Results.Loss.some
      }, hand.some))
    }
  }

  object Calculator {
    def apply(_config: CalculatorConfig): Calculator = new Calculator {
      override val config: CalculatorConfig = _config
    }
  }

  trait CalculatorResult {
    def points: Points
    def result: Option[Result]
    def hand: Option[Hand]
  }

  object CalculatorResult {
    import Results._

    def apply(_points: Points, _result: Option[Result], _hand: Option[Hand] = None): CalculatorResult = new CalculatorResult {
      val points: Points = _points
      val result: Option[Result] = _result
      val hand: Option[Hand] = _hand
    }
  }

  trait Result
  object Results {
    case object Loss extends Result {
      override def toString: String = "loss"
    }
    case object Won extends Result {
      override def toString: String = "won"
    }
    case object Equals extends Result {
      override def toString: String = "equals"
    }
  }
}

trait BlackJackAction {
  def actionType: BlackJackActionType
  def handId: EntityId
}

trait BlackJackActionType
object BlackJackActionTypes {
  case object Join extends BlackJackActionType
  case object Leave extends BlackJackActionType
  case object AddCard extends BlackJackActionType
  case object Hit extends BlackJackActionType
  case object Stand extends BlackJackActionType
}
//
//trait Stall {
//  def cardDeck: CardDeck
//  def hands: Seq[Hand]
//}

trait Seat {
  def id: EntityId
  def holder: Player
}

object Seat {
  def apply(seatId: EntityId, player: Player): Seat = new Seat {
    override def id: EntityId = seatId

    override def holder: Player = player
  }
}

trait Hand {
  def id: EntityId
  def seatId: EntityId
  def holder: Player
  def main: Boolean
}

object Hand {
  def apply(_seatId: EntityId, _player: Player, _main: Boolean = true): Hand = {
    new Hand {
      override val id: EntityId = EntityId()

      override val holder: Player = _player

      override val main: Boolean = _main

      override def seatId: EntityId = _seatId
    }
  }
}

trait SeatHolder {
  def player: Player
}

object SeatHolder {
  def apply(_player: Player): SeatHolder = new SeatHolder {
    val player: Player = _player
  }
}

trait HolderType
object HolderTypes {
  case object PlayerHolder extends HolderType
  case object DealerHolder extends HolderType
}


trait Dealer {}


