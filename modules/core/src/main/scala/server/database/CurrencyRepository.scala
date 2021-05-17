package server.database

import server.common.EntityId
import server.common.Errors.{DBError}
import doobie.implicits._
import server.common.Errors.DBErrors.{CreationError, DuplicateValues}
import server.database.SqlStates.DUPLICATE_VALUES

case class CurrencyName(value: String) extends AnyVal
case class CurrencyCode(value: String) extends AnyVal

case class CurrencyModel
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
  ): F[Either[DBError, CurrencyModel]]
}

object CurrencyRepository {
  import server.common.EntityId.implicits._

  def make[F[+_]](connection: Connection[F]): CurrencyRepository[F] = new CurrencyRepository[F] {
    override def create
    (
      name: CurrencyName,
      code: CurrencyCode,
      exchangeRate: Double,
      main: Boolean = false
    ): F[Either[DBError, CurrencyModel]] =
      connection.runQuery(
        sql"""INSERT INTO Currencies (id, name, code, exchangeRate, main)
             |VALUES (${EntityId()}, ${name.value}, ${code.value},
             |${exchangeRate}, ${main});"""
          .stripMargin
          .update
          .withGeneratedKeys[EntityId]("id")
          .map { id =>
            CurrencyModel(id, name, code, exchangeRate, main)
          }
          .attemptSomeSqlState {
            case DUPLICATE_VALUES => DuplicateValues
            case _                => CreationError
          }
          .compile
          .lastOrError
      )
  }
}
