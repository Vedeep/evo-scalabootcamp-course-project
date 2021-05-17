package server.database

import org.typelevel.log4cats.Logger
import server.common.EntityId
import server.common.Errors.{AppError, DBError}
import server.common.Errors.DBErrors.CreationError
import server.common.Errors.UserErrors.UserIsNotFound
import doobie.implicits._

case class PlayerFirstName(value: String) extends AnyVal
case class PlayerLastName(value: String) extends AnyVal
case class PlayerNickName(value: String) extends AnyVal

case class PlayerModel
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
  import server.common.EntityId.implicits._

  def make[F[+_] : Logger](connection: Connection[F]): PlayerRepository[F] = new PlayerRepository[F] {
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
      connection.runQuery(
        sql"""INSERT INTO Players (id, firstName, lastName, nickName)
             |VALUES (${EntityId()}, ${firstName}, ${lastName}, ${nick});"""
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
