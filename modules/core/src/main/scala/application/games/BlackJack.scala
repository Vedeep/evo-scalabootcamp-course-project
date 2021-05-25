package application.games

import cats.data.EitherT
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._

import scala.concurrent.duration._
import application.players.Player
import application.common.Errors.{AppError, BlackJackError, WalletError}
import application.common.Errors.BlackJackErrors._

import scala.annotation.tailrec
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import application.common.EntityId
import application.games.Games._
import application.games.Games.GameStatuses._
import application.games.Games.GameEventTargets._
import application.games.Cards._
import application.common.Finance._
import application.wallets.TransactionTypes.Withdrawal
import application.wallets.{TransactionTypes, WalletService, WalletTransaction, WalletTransactionAmount, WalletTransactionResult}

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
    private val topic: Topic[F, GameEvent],
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
            startRound
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

    private def getHands(state: BlackJackGameState): F[List[Hand]] = {
      def recursive(seats: List[(EntityId, Option[Player])], hands: List[Hand]): F[List[Hand]] =
        seats match {
          case Nil => Concurrent[F].pure(hands)
          case (seatId, Some(player)) :: xs => Hand[F](seatId, player).flatMap(hand => recursive(xs, hand :: hands))
          case _ :: xs => recursive(xs, hands)
        }

      recursive(state.seats.toList, Nil);
    }

    private def getDefaultHandActions(hands: List[Hand]): Map[Hand, List[BlackJackAction]] =
      hands.foldLeft(Map.empty[Hand, List[BlackJackAction]]) { (acc, hand) =>
        acc + (hand -> List(BlackJackActions.AddStake))
      }

    private def startRound: F[Either[BlackJackError, BlackJackGameStateRound]] = {
      EitherT(state.get.map { current =>
        if (isSeatsEmpty(current.seats))
          Left(TableIsEmpty)
        else if (isRoundStarted(current.currentRound))
          Left(RoundAlreadyStarted)
        else
          Right(current)
      }).semiflatMap { current =>
        for {
          hands <- getHands(current)
          handsActions = getDefaultHandActions(hands)
          nextState <- Concurrent[F].delay(Instant.now().plusSeconds(config.roundStateSeconds))
          round = BlackJackGameStateRound(
            hands = hands,
            handActions = handsActions,
            nextStateTime = nextState
          )
          _ <- state.update(_.copy(
            nextRoundTime = None,
            currentRound = Some(round)
          ))
          _ <- sendState
          _ <- deck.shuffle
        } yield round
      }
    }.value

    private def doRound: F[Unit] =
      EitherT(getCurrentRound).semiflatMap { round =>
        if (round.nextStateTime.isBefore(Instant.now())) {
          round.state match {
            case BlackJackRoundStates.Stakes => setRoundStateAsCards
            case BlackJackRoundStates.Cards  => endRound
            case _ => Concurrent[F].pure()
          }
        } else if (round.state == BlackJackRoundStates.Cards && allHandsStood(round)) {
          endRound
        } else if (round.state == BlackJackRoundStates.Stakes && allSeatsPlaced(round)) {
          setRoundStateAsCards
        } else
          Concurrent[F].pure()
      }.value.void

    private def endRound: F[Unit] =
      stopAcceptingActions *>
        EitherT(getCurrentRound).semiflatMap { round =>
          for {
            handCards   <- Concurrent[F].delay(round.handCards.filterNot(_._2.isEmpty).toList)
            dealerCards <- getDealerCards(round.dealerCards, handCards)
            results = calculator.calculateAll(
              dealerCards,
              handCards
            )
            _ <- updateCurrentRound { round =>
              round.copy(
                dealerCards = if (handCards.nonEmpty) dealerCards else round.dealerCards,
                results = if (handCards.nonEmpty) results else round.results,
                state = BlackJackRoundStates.Ended
              )
            }
            _ <- Concurrent[F].start(enrollResults)
            _ <- sendState
          } yield ()
        }.value.void

    private def getCards(count: Int): F[List[Card]] = deck.getCards(count)

    private def getDealerCards(cards: List[Card], handCards: List[(Hand, List[Card])], min: Option[Points] = None): F[List[Card]] = {
      val minPoints = min.getOrElse[Points] {
        calculator
          .calculateAll(cards, handCards)
          .filterNot(r => r.hand.isEmpty || r.points > calculator.getMaxPoints)
          .minByOption(_.points)
          .map(_.points)
          .getOrElse(0)
      }

      (for {
        newCards <- getCards(1).map(_ ++ cards)
        points   <- Concurrent[F].delay(calculator.getWinPoints(newCards))
      } yield (newCards, points)).flatMap {
        case (newCards, points) =>
          if (minPoints > points) getDealerCards(newCards, handCards, minPoints.some)
          else Concurrent[F].pure(newCards)
      }
    }

    private def stopAcceptingStakes: F[Unit] =
      updateCurrentRound { round =>
        round.copy(
          handActions = round.handActions.map { ha =>
            val (hand, actions) = ha
            (hand, actions.filterNot(_ == BlackJackActions.AddStake))
          }
        )
      }.void

    private def stopAcceptingActions: F[Unit] =
      updateCurrentRound { round =>
        round.copy(handActions = Map.empty)
      }.void

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
      updateCurrentRound { round =>
        (for {
          hand <- round.hands.find(_.id == handId)
          newActions = getHandActions(round, hand)
        } yield round.copy(
          handActions = round.handActions + (hand -> newActions)
        )).getOrElse(round)
      }.void

    private def extendRoundState: F[Unit] =
      updateCurrentRound { round =>
        round.copy(
          nextStateTime = Instant.now().plusSeconds(config.roundStateSeconds)
        )
      }.void

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

    private def updateCurrentRound(f: BlackJackGameStateRound => BlackJackGameStateRound): F[Either[BlackJackError, BlackJackGameState]] =
      state.modifyOr { oldState =>
        oldState.currentRound match {
          case Some(round) =>
            val newState = oldState.copy(currentRound = f(round).some)
            (newState, newState).asRight

          case None        => RoundNotStarted.asLeft
        }
      }

    private def getCurrentRound: F[Either[BlackJackError, BlackJackGameStateRound]] =
      state.get.map { state =>
        state.currentRound match {
          case Some(round) if round.state != BlackJackRoundStates.Ended => round.asRight
          case _ => RoundNotStarted.asLeft
        }
      }

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
            result      <- updateCurrentRound { round =>
              val newRound = round.copy(
                state = BlackJackRoundStates.Cards,
                nextStateTime = nextTime,
                dealerCards = dealerCards,
                handCards = handCards,
                results = results
              )

              val handActions = newRound.hands.foldLeft[Map[Hand, List[BlackJackAction]]](Map.empty) { (acc, hand) =>
                acc + (hand -> getHandActions(newRound, hand))
              }

              val handStandCards = handActions.filter(_._2.isEmpty).keys.toSet

              newRound.copy(
                handActions = handActions,
                handStandCards = handStandCards
              )
            }
            _ <- EitherT.fromEither[F](result).semiflatMap(_ => sendState).value
          } yield result
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
                          case _                    => 0
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

    private def canSplitHand(round: BlackJackGameStateRound, hand: Hand): Boolean =
      round.handCards.get(hand) match {
        case Some(card1 :: card2 :: Nil) if round.hands.count(_.seatId == hand.seatId) == 1 =>
          card1.value == card2.value
        case _ => false
      }

    private def getHandActions(round: BlackJackGameStateRound, hand: Hand): List[BlackJackAction] =
      round.state match {
        case BlackJackRoundStates.Stakes if !round.stakes.contains(hand) =>
          List(BlackJackActions.AddStake)

        case BlackJackRoundStates.Cards if round.stakes.contains(hand) =>
          if (round.handStandCards.contains(hand))
            Nil
          else
            round.results.find(_.hand.contains(hand)) match {
              case Some(result) if result.points >= calculator.getMaxPoints =>
                Nil
              case _ if canSplitHand(round, hand) =>
                List(BlackJackActions.AddCard, BlackJackActions.StandCards, BlackJackActions.SplitHand)
              case _ =>
                List(BlackJackActions.AddCard, BlackJackActions.StandCards)
            }

        case _ => Nil
      }

    private def allHandsStood(round: BlackJackGameStateRound): Boolean =
      round.hands.filter(round.stakes.contains).forall(round.handStandCards.contains)

    private def allSeatsPlaced(round: BlackJackGameStateRound): Boolean =
      round.hands.forall(round.stakes.contains)

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
        (for {
          _ <- EitherT.cond[F](actions.contains(BlackJackActions.AddStake), (), ActionIsNotAllowed)
          roundAndHand <- EitherT(state.get.map(getRoundAndHand(_, handId)))
          (_, hand) = roundAndHand

          _ <- EitherT(walletService.doTransaction(hand.holder.wallet, WalletTransaction(
            Withdrawal,
            WalletTransactionAmount(amount.value)
          )))

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
      }.flatMap { res =>
        startAcceptingHandActions(handId) *>
        sendState *>
        Concurrent[F].pure(res)
      }
    }

    private def addHandCards(hand: Hand, cards: List[Card]): F[Either[BlackJackError, BlackJackGameState]] =
      updateCurrentRound { round =>
        val newCards = round.handCards.getOrElse(hand, Nil) ++ cards
        val newHandCards = round.handCards + (hand -> newCards)
        val results = calculator.calculateAll(round.dealerCards, newHandCards.toList, isFinally = false)
        val handResult = results.find(_.hand.contains(hand)).get
        val newStandCards =
          if (handResult.points >= calculator.getMaxPoints) round.handStandCards + hand
          else round.handStandCards

        round.copy(
          handCards = newHandCards,
          results = results,
          handStandCards = newStandCards
        )
      }

    private def splitHandCards(hand: Hand, newHand: Hand, amount: Amount): F[Either[BlackJackError, BlackJackGameState]] =
      updateCurrentRound { round =>
        round.handCards.get(hand) match {
          case Some(card1 :: card2 :: Nil) =>
            val handCards = round.handCards + (hand -> List(card1)) + (newHand -> List(card2))

            round.copy(
              hands = newHand :: round.hands,
              handCards = handCards,
              stakes = round.stakes + (newHand -> amount),
              results = calculator.calculateAll(round.dealerCards, handCards.toList, isFinally = false)
            )
          case _ => round
        }
      }

    def addCard(handId: EntityId, player: Player): F[Either[BlackJackError, BlackJackGameState]] =
      stopAcceptingHandActions(handId, player.id).flatMap { actions =>
        (for {
          _ <- EitherT.cond[F](actions.contains(BlackJackActions.AddCard), (), ActionIsNotAllowed)
          roundAndHand <- EitherT(state.get.map(
            getRoundAndHand(_, handId).filterOrElse({
              case (round, _) => round.state == BlackJackRoundStates.Cards
            }, RoundNotStarted)
          ))
          (_, hand) = roundAndHand
          cards <- EitherT.liftF[F, BlackJackError, List[Card]](getCards(1))
          _     <- EitherT(addHandCards(hand, cards))
          state <- EitherT.liftF[F, BlackJackError, BlackJackGameState](state.get)
        } yield state).value
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

    def standCards(handId: EntityId, player: Player): F[Either[BlackJackError, BlackJackGameState]] =
      stopAcceptingHandActions(handId, player.id).flatMap { actions =>
        (for {
          _ <- EitherT.cond[F](actions.contains(BlackJackActions.StandCards), (), ActionIsNotAllowed)
          roundAndHand <- EitherT(state.get.map(
            getRoundAndHand(_, handId).filterOrElse({
              case (round, _) => round.state == BlackJackRoundStates.Cards
            }, RoundNotStarted)
          ))
          (_, hand) = roundAndHand
          _ <- EitherT(updateCurrentRound { round =>
            round.copy(
              handStandCards = round.handStandCards + hand
            )
          })
          state <- EitherT.liftF[F, BlackJackError, BlackJackGameState](state.get)
        } yield state).value
      }.flatMap {
        case result @ Right(_) =>
          startAcceptingHandActions(handId) *>
            sendState *>
            Concurrent[F].pure(result)

        case error  @ Left(_)  =>
          startAcceptingHandActions(handId) *>
            Concurrent[F].pure(error)
      }

    def splitHand(handId: EntityId, player: Player): F[Either[AppError, BlackJackGameState]] =
      stopAcceptingHandActions(handId, player.id).flatMap { actions =>
        (for {
          _ <- EitherT.cond[F](actions.contains(BlackJackActions.SplitHand), (), ActionIsNotAllowed)
          _ <- EitherT.liftF[F, AppError, Unit](extendRoundState)

          roundAndHand <- EitherT[F, AppError, (BlackJackGameStateRound, Hand)](state.get.map(
            getRoundAndHand(_, handId).filterOrElse({
              case (round, _) => round.state == BlackJackRoundStates.Cards
            }, RoundNotStarted)
          ))
          (round, hand) = roundAndHand
          stake   <- EitherT.fromOption[F](round.stakes.get(hand), ActionIsNotAllowed)
          newHand <- EitherT.liftF[F, AppError, Hand](Hand[F](hand.seatId, hand.holder))

          _ <- EitherT[F, AppError, WalletTransactionResult](walletService.doTransaction(
            hand.holder.wallet,
            WalletTransaction(
              Withdrawal,
              WalletTransactionAmount(stake.value)
            )
          ))
          _ <- EitherT[F, AppError, BlackJackGameState](splitHandCards(hand, newHand, stake))

          state <- EitherT.liftF[F, AppError, BlackJackGameState](state.get)
        } yield (newHand, state)).value
      }.flatMap {
        case result @ Right((newHand, state)) =>
          startAcceptingHandActions(handId) *>
          startAcceptingHandActions(newHand.id) *>
            sendState *>
            Concurrent[F].pure(state.asRight)

        case error  @ Left(e)  =>
          startAcceptingHandActions(handId) *>
            Concurrent[F].pure(e.asLeft)
      }

    override def subscribe(subscriber: Player): Stream[F, GameEvent] =
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

  final case class BlackJackGameInfo(id: EntityId, playerCount: Int, maxPlayerCount: Int, gameType: GameType)
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

  final case class BlackJackGameStateRound
  (
    hands: List[Hand],
    nextStateTime: Instant,
    dealerCards: List[Card] = Nil,
    handCards: Map[Hand, List[Card]] = Map.empty,
    handStandCards: Set[Hand] = Set.empty,
    stakes: Map[Hand, Amount] = Map.empty,
    handActions: Map[Hand, List[BlackJackAction]] = Map.empty,
    state: BlackJackRoundState = BlackJackRoundStates.Stakes,
    results: List[CalculatorResult] = Nil
  ) extends BlackJackStateRound

  final case class BlackJackGameState
  (
    seats: Map[EntityId, Option[Player]],
    status: GameStatus,
    currentRound: Option[BlackJackGameStateRound],
    nextRoundTime: Option[Instant] = None
  ) extends BlackJackState

  object BlackJackGameState {
    def apply[F[_] : Sync](config: BlackJackConfig): F[BlackJackGameState] = {
      def recursive(seats: List[(EntityId, Option[Player])], seatCount: Int): F[BlackJackGameState] =
        if (seatCount > 0)
          EntityId.of[F].flatMap(eid => recursive((eid, None) :: seats, seatCount - 1))
        else
          Sync[F].pure(BlackJackGameState(seats.toMap, New, None))

      recursive(Nil, config.seatCount)
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
    case object SplitHand extends BlackJackAction {
      override def toString: String = "split-hand"
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

trait Hand {
  def id: EntityId
  def seatId: EntityId
  def holder: Player
}

object Hand {
  def apply[F[_]: Sync](_seatId: EntityId, _player: Player): F[Hand] =
    EntityId.of[F].map { eid =>
      new Hand {
        override val id: EntityId = eid

        override val holder: Player = _player

        override def seatId: EntityId = _seatId
      }
    }
}
