package application.database

import org.typelevel.log4cats.Logger
import application.common.EntityId
import application.common.Errors.{AppError, DBError}
import application.common.Errors.DBErrors.CreationError
import application.common.Errors.UserErrors.UserIsNotFound
import cats.effect.Sync
import cats.syntax.all._
import doobie.implicits._

final case class PlayerFirstName(value: String) extends AnyVal
final case class PlayerLastName(value: String) extends AnyVal
final case class PlayerNickName(value: String) extends AnyVal

final case class PlayerModel
(
  id: EntityId,
  firstName: PlayerFirstName,
  lastName: PlayerLastName,
  nickName: PlayerNickName
)

trait PlayerRepository[F[+_]] {
  def getById(id: EntityId): F[Either[AppError, (PlayerModel, WalletModel, CurrencyModel)]]
  def create(firstName: PlayerFirstName, lastName: PlayerLastName, nick: PlayerNickName): F[Either[DBError, PlayerModel]]
}

object PlayerRepository {
  import application.common.EntityId.implicits.db._

  def make[F[+_] : Logger : Sync](connection: Connection[F]): PlayerRepository[F] = new PlayerRepository[F] {
    override def getById(id: EntityId): F[Either[AppError, (PlayerModel, WalletModel, CurrencyModel)]] =
      connection.runQuery(
        sql"""SELECT p.id, p.firstName, p.lastName, p.nickName,
               |w.id, w.playerId, w.currencyId, w.balance,
               |c.id, c.name, c.code, c.exchangeRate, c.main
               |FROM Players p
               |JOIN Wallets w on w.playerId = p.id
               |JOIN Currencies c on c.id = w.currencyId
               |WHERE p.id = ${id.value.toString}"""
          .stripMargin
          .query[(PlayerModel, WalletModel, CurrencyModel)]
          .option
          .map(_.toRight(UserIsNotFound))
      )

    override def create
    (
      firstName: PlayerFirstName,
      lastName: PlayerLastName,
      nick: PlayerNickName
    ): F[Either[DBError, PlayerModel]] =
      EntityId.of[F].flatMap { eid =>
        connection.runQuery(
          sql"""INSERT INTO Players (id, firstName, lastName, nickName)
               |VALUES (${eid}, ${firstName}, ${lastName}, ${nick});"""
            .stripMargin
            .update
            .withGeneratedKeys[EntityId]("id")
            .map { id =>
              PlayerModel(id, firstName, lastName, nick)
            }
            .attemptSomeSqlState {
              case _ => CreationError
            }
            .compile
            .lastOrError
        )
      }
  }
}
