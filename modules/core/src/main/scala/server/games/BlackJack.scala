package server.games

import server.players.Player

import java.util.UUID
import scala.annotation.tailrec
import cats.implicits._

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
 * (фронт автоматически отправляет сообщение): PlaceBet
 * сервер ждет 20+- секунд или все сообщения PlaceBet
 * 9. Сервер отправляет сообщения пользователям о начале раздачи с сумой их ставки: BetPlaced
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
 * PlaceBet(handId, amount)
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
 * BetPlaced - ставка принята (всем пользователям)
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

  trait GameConfig {
    def placeCount: Int
    def playerPlacesLimit: Int
  }

  class Game
  (
    private val config: GameConfig,
    private val calculator: Calculator
  )
  {
    import BlackJackErrors._

    private val places: scala.collection.mutable.Map[BlackJackId, Option[Player]]
      = scala.collection.mutable.Map.from((1 to config.placeCount).map(_ => (BlackJackId(), None)))

    private def getPlayerPlaces(player: Player): List[BlackJackId] = {
      places.filter {
        case (_, p) => p.fold(false)(_ == player)
      }.keys.toList
    }

    def startRound(): Either[BlackJackError, GameRound] = {
      if (places.isEmpty) Left(TableIsEmpty)
      else {
        Right(new GameRound(
          config,
          calculator,
          places
            .values
            .filter(_.isDefined)
            .map(p => Hand(p.get))
            .toList
        ))
      }
    }

    def endRound(round: GameRound): Either[BlackJackError, Unit] = ???

    def join(player: Player, placeId: BlackJackId): Either[BlackJackError, Place] = {
      for {
        place <- places.get(placeId).toRight(PlaceIsNotFound)
        _     <- place.toLeft().leftMap(_ => PlaceIsBusy)
        _     <- getPlayerPlaces(player) match {
                case ls if ls.size > config.playerPlacesLimit => Left(PlayerPlacesExceed)
                case _ => Right()
              }
        _     <- Right(places.put(placeId, Some(player)))
      } yield Place(placeId, player)
    }

    def leave(player: Player, placeId: BlackJackId): Either[BlackJackError, Unit] = {
      for {
        place <- places.get(placeId).toRight(PlaceIsNotFound)
        _     <- place.toRight(NotPlayerPlace).filterOrElse(_ == player, NotPlayerPlace)
        _     <- Right(places.put(placeId, None))
      } yield ()
    }
  }

  class GameRound(
   private val config: GameConfig,
   private val calculator: Calculator,
   private val hands: List[Hand],
  ) {
    import BlackJackErrors._

    private var dealerCards: List[Card] = Nil
    private val handCards: scala.collection.mutable.Map[Hand, List[Card]] = scala.collection.mutable.Map.empty
    private val stakes: scala.collection.mutable.Map[Hand, Amount] = scala.collection.mutable.Map.empty

    def addDealerCard(card: Card): Either[BlackJackError, (Points, List[CalculatorResult])] = {
      dealerCards = dealerCards :+ card
      Right(calculator.calculateAll(dealerCards, handCards.toList))
    }

    def addCard(handId: BlackJackId, card: Card): Either[BlackJackError, CalculatorResult] = {
      for {
        hand      <- hands.find(_.id == handId).toRight(HandIsNotFound)
        cards     = handCards.getOrElse(hand, Nil)
        newCards  = cards :+ card
        _         <- handCards.put(hand, newCards).toRight(HandIsNotFound)
      } yield calculator.calculate(dealerCards, newCards)
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

    def calculate(dealerHand: List[Card], playerHand: List[Card]): CalculatorResult = {
      val dealerPoints = getWinPoints(dealerHand)
      val points = getWinPoints(playerHand)

      CalculatorResult(points, points match {
        case p if p <= config.maxPoints && p > dealerPoints  => Results.Won
        case p if p <= config.maxPoints && p == dealerPoints => Results.Equals
        case _                                               => Results.Loss
      })
    }

    def calculateAll(dealerHand: List[Card], playersHands: List[(Hand, List[Card])]): (Points, List[CalculatorResult]) = {
      val dealerPoints = getWinPoints(dealerHand)

      (dealerPoints, for {
        (hand, cards) <- playersHands
        points = getWinPoints(cards)
      } yield CalculatorResult(points, points match {
        case p if p <= config.maxPoints && p > dealerPoints  => Results.Won
        case p if p <= config.maxPoints && p == dealerPoints => Results.Equals
        case _                                               => Results.Loss
      }, Some(hand)))
    }
  }

  object Calculator {
    def apply(config: CalculatorConfig): Calculator = new Calculator {
      override val config: CalculatorConfig = config
    }
  }

  trait CalculatorResult {
    def points: Points
    def result: Result
    def hand: Option[Hand]
  }

  object CalculatorResult {
    import Results._

    def apply(_points: Points, _result: Result, _hand: Option[Hand] = None): CalculatorResult = new CalculatorResult {
      val points: Points = _points
      val result: Result = _result
      val hand: Option[Hand] = _hand

      def getStrResult: String = result match {
        case Won    => "won"
        case Loss   => "loss"
        case Equals => "equals"
      }

      override def toString: String = s"($points,$getStrResult)"
    }
  }

  trait Result
  object Results {
    case object Loss extends Result
    case object Won extends Result
    case object Equals extends Result
  }
}

//object Test extends App {
//  import CardSuits._
//  import CardValues._
//  import BlackJack._
//
//  val calc = new Calculator {
//    override def config: CalculatorConfig = new CalculatorConfig {
//      override def maxPoints: Points = 21
//    }
//  }
//  val cards = List(
////    CardOps(Club, Three),
//    CardOps(Club, Ace),
////    CardOps(Club, Seven),
////    CardOps(Club, Five),
//    CardOps(Club, Ace),
//    CardOps(Club, Ace),
//  )
//
//  val dealerCards = List(
//    CardOps(Club, Ace),
//    CardOps(Club, Ace),
//    CardOps(Club, Eight)
//  )
//
//  val hands = List(
//    (
//      new Hand {
//        override def id: BlackJackId = BlackJackId()
//
//        override def main: Boolean = true
//      },
//      cards
//    )
//  )
//
//  println(calc.calculate(dealerCards, hands))
//}



trait BlackJackError
object BlackJackErrors {
  case object TableIsFull extends BlackJackError
  case object TableIsEmpty extends BlackJackError
  case object PlayerPlacesExceed extends BlackJackError
  case object HandIsNotFound extends BlackJackError
  case object RoundAlreadyStarted extends BlackJackError
  case object RoundNotStarted extends BlackJackError
  case object PlaceIsNotFound extends BlackJackError
  case object PlaceIsBusy extends BlackJackError
  case object NotPlayerPlace extends BlackJackError
}

trait BlackJackAction {
  def actionType: BlackJackActionType
  def handId: BlackJackId
}

trait BlackJackActionType
object BlackJackActionTypes {
  case object Join extends BlackJackActionType
  case object Leave extends BlackJackActionType
  case object AddCard extends BlackJackActionType
  case object Hit extends BlackJackActionType
  case object Stand extends BlackJackActionType
}

trait Stall {
  def cardDeck: CardDeck
  def hands: Seq[Hand]
}

trait Place {
  def id: BlackJackId
  def holder: Player
}

object Place {
  def apply(placeId: BlackJackId, player: Player): Place = new Place {
    override def id: BlackJackId = placeId

    override def holder: Player = player
  }
}

trait Hand {
  def id: BlackJackId
  def holder: Player
  def main: Boolean
}

object Hand {
  def apply(player: Player, main: Boolean = true): Hand = {
    new Hand {
      override val id: BlackJackId = BlackJackId()

      override val holder: Player = player

      override val main: Boolean = main
    }
  }
}

trait BlackJackId {
  def value: UUID
}

object BlackJackId {
  def apply(): BlackJackId = new BlackJackId {
    val value: UUID = UUID.randomUUID()
  }
}


trait PlaceHolder {
  def player: Player
}

object PlaceHolder {
  def apply(player: Player): PlaceHolder = new PlaceHolder {
    val player: Player = player
  }
}

trait Amount {
  def value: Double
  def currency: Currency
}

trait Currency {
  def code: String
  def exchangeRate: Double
}

trait HolderType
object HolderTypes {
  case object PlayerHolder extends HolderType
  case object DealerHolder extends HolderType
}


trait Dealer {}


