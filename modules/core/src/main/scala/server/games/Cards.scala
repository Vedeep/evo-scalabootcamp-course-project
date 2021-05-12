package server.games

trait Card {
  def suit: CardSuit
  def value: CardValue
}

case class CardOps(suit: CardSuit, value: CardValue) extends Card

trait CardDeck

trait CardSuit
object CardSuits {
  case object Club extends CardSuit
  case object Diamond extends CardSuit
  case object Hearth extends CardSuit
  case object Spade extends CardSuit
}

trait CardValue
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
