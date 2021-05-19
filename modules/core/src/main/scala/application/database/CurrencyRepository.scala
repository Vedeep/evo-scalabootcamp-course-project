package application.database

import application.common.EntityId
import application.common.Errors.DBError
import doobie.implicits._
import application.common.Errors.DBErrors.{CreationError, DuplicateValues}
import application.database.SqlStates.duplicateValues
import cats.effect.Sync
import cats.syntax.all._

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
  ): F[Either[DBError, CurrencyModel]]
}

object CurrencyRepository {
  import application.common.EntityId.implicits.db._

  def make[F[+_] : Sync](connection: Connection[F]): CurrencyRepository[F] = new CurrencyRepository[F] {
    override def create
    (
      name: CurrencyName,
      code: CurrencyCode,
      exchangeRate: Double,
      main: Boolean = false
    ): F[Either[DBError, CurrencyModel]] =
      EntityId.of[F].flatMap { eid =>
        connection.runQuery(
          sql"""INSERT INTO Currencies (id, name, code, exchangeRate, main)
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
        )
      }

  }
}
