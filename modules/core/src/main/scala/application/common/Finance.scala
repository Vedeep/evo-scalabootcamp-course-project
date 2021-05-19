package application.common

import io.circe.Decoder

object Finance {
  final case class Amount(value: Double)
  final case class Currency(code: String, exchangeRate: Double)

  object implicits {
    implicit val amountDecoder: Decoder[Amount] = Decoder.decodeDouble.map(Amount)
  }
}
