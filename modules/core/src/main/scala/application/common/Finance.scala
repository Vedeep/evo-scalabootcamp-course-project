package application.common

import io.circe.Decoder

object Finance {
  case class Amount(value: Double)
  case class Currency(code: String, exchangeRate: Double)

  object implicits {
    implicit val amountDecoder: Decoder[Amount] = Decoder.decodeDouble.map { num =>
      Amount(num)
    }
  }
}
