package application.wallets

import application.common.EntityId
import application.common.Errors.DBError
import application.common.Errors.DBErrors.{CreationError, DuplicateValues}
import application.database.SqlStates.duplicateValues
import cats.effect.Sync
import doobie.ConnectionIO
import doobie.implicits._

final case class CurrencyName(value: String) extends AnyVal
final case class CurrencyCode(value: String) extends AnyVal

final case class CurrencyModel
(
  id: EntityId,
  name: CurrencyName,
  code: CurrencyCode,
  exchangeRate: Double,
  main: Boolean
)

trait CurrencyRepository[F[+_]] {
  def create
  (
    name: CurrencyName,
    code: CurrencyCode,
    exchangeRate: Double,
    main: Boolean = false
  ): ConnectionIO[Either[DBError, CurrencyModel]]
}

object CurrencyRepository {
  import application.common.EntityId.implicits.db._

  def apply[F[+_] : Sync]: CurrencyRepository[F] = new CurrencyRepository[F] {
    override def create
    (
      name: CurrencyName,
      code: CurrencyCode,
      exchangeRate: Double,
      main: Boolean = false
    ): ConnectionIO[Either[DBError, CurrencyModel]] =
      for {
        eid    <- EntityId.of[ConnectionIO]
        result <- sql"""INSERT INTO Currencies (id, name, code, exchangeRate, main)
                       |VALUES (${eid}, ${name.value}, ${code.value},
                       |${exchangeRate}, ${main});"""
          .stripMargin
          .update
          .withGeneratedKeys[EntityId]("id")
          .map { id =>
            CurrencyModel(id, name, code, exchangeRate, main)
          }
          .attemptSomeSqlState {
            case `duplicateValues` => DuplicateValues
            case _                => CreationError
          }
          .compile
          .lastOrError
      } yield result

  }
}
