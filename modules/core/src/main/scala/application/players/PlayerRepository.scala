package application.players

import application.common.EntityId
import application.common.Errors.UserErrors.{UserCannotBeCreated, UserIsNotFound}
import application.common.Errors.{UserError}
import application.database.{CurrencyModel}
import application.wallets.WalletModel
import cats.effect.Sync
import doobie.ConnectionIO
import doobie.implicits._
import org.typelevel.log4cats.Logger

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
  def getById(id: EntityId): ConnectionIO[Either[UserError, (PlayerModel, WalletModel, CurrencyModel)]]
  def create(firstName: PlayerFirstName, lastName: PlayerLastName, nick: PlayerNickName): ConnectionIO[Either[UserError, PlayerModel]]
}

object PlayerRepository {
  import application.common.EntityId.implicits.db._

  def apply[F[+_] : Logger : Sync]: PlayerRepository[F] = new PlayerRepository[F] {
    override def getById(id: EntityId): ConnectionIO[Either[UserError, (PlayerModel, WalletModel, CurrencyModel)]] =
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

    override def create
    (
      firstName: PlayerFirstName,
      lastName: PlayerLastName,
      nick: PlayerNickName
    ): ConnectionIO[Either[UserError, PlayerModel]] =
      for {
        eid <- EntityId.of[ConnectionIO]
        result <- sql"""INSERT INTO Players (id, firstName, lastName, nickName)
                       |VALUES (${eid}, ${firstName}, ${lastName}, ${nick});"""
          .stripMargin
          .update
          .withGeneratedKeys[EntityId]("id")
          .map { id =>
            PlayerModel(id, firstName, lastName, nick)
          }
          .attemptSomeSqlState {
            case _ => UserCannotBeCreated
          }
          .compile
          .lastOrError
      } yield result
  }
}
