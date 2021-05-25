package application.games

import cats.syntax.all._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import io.circe.{Encoder, Json}

import scala.util.Random

object Cards {
  trait CardDeck[F[_]] {
    def getCards(count: Int): F[List[Card]]
    def shuffle: F[Unit]
  }

  object CardDeck {

    import CardSuits._
    import CardValues._

    private def createDeck: List[Card] = Random.shuffle(for {
      suit <- List(Club, Diamond, Hearth, Spade)
      value <- List(
        Two,
        Three,
        Four,
        Five,
        Six,
        Seven,
        Eight,
        Nine,
        Ten,
        Jack,
        Queen,
        King,
        Ace,
      )
    } yield Card(suit, value))

    def make[F[_] : Sync]: F[CardDeck[F]] =
      for {
        deck <- Ref.of[F, List[Card]](createDeck)
        result <- Sync[F].delay(
          new CardDeck[F] {
            override def getCards(count: Int): F[List[Card]] =
              deck.modify { cards =>
                (cards.drop(count), cards.take(count))
              }

            override def shuffle: F[Unit] =
              deck.update { _ =>
                createDeck
              }
          }
        )
      } yield result
  }

  final case class Card(suit: CardSuit, value: CardValue)

  trait CardSuit {
    override def toString: String = getClass.getSimpleName.toLowerCase.replaceAll("[^A-z]+", "")
  }
  object CardSuits {
    case object Club extends CardSuit
    case object Diamond extends CardSuit
    case object Hearth extends CardSuit
    case object Spade extends CardSuit
  }

  trait CardValue {
    override def toString: String = getClass.getSimpleName.toLowerCase.replaceAll("[^A-z]+", "")
  }
  object CardValues {
    case object Two extends CardValue
    case object Three extends CardValue
    case object Four extends CardValue
    case object Five extends CardValue
    case object Six extends CardValue
    case object Seven extends CardValue
    case object Eight extends CardValue
    case object Nine extends CardValue
    case object Ten extends CardValue
    case object Jack extends CardValue
    case object Queen extends CardValue
    case object King extends CardValue
    case object Ace extends CardValue
  }

  object implicits {
    implicit val suitEncoder: Encoder[CardSuit] = suit => Json.fromString(suit.toString)
    implicit val valueEncoder: Encoder[CardValue] = value => Json.fromString(value.toString)
    implicit val cardEncoder: Encoder[Card] =
      card => Json.obj(
        ("suit", Json.fromString(card.suit.toString)),
        ("value", Json.fromString(card.value.toString))
      )
  }
}
