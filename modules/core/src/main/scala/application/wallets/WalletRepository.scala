package application.wallets

import application.common.EntityId
import application.common.Errors.WalletErrors.{WalletAlreadyExists, WalletCreationError, WalletIsNotFound}
import application.common.Errors.WalletError
import application.database.SqlStates.duplicateValues
import cats.effect.{Async, ContextShift}
import cats.syntax.all._
import doobie.implicits._
import doobie.ConnectionIO

final case class WalletBalance(value: Double) extends AnyVal

final case class WalletModel
(
  id: EntityId,
  playerId: EntityId,
  currencyId: EntityId,
  balance: WalletBalance
)

trait WalletRepository[F[+_]] {
  def create(playerId: EntityId, currencyId: EntityId, balance: WalletBalance): ConnectionIO[Either[WalletError, WalletModel]]
  def getBalance(walletId: EntityId): ConnectionIO[Option[Double]]
  def setBalance(walletId: EntityId, balance: WalletBalance): ConnectionIO[Either[WalletError, Unit]]
}

object WalletRepository {
  import application.common.EntityId.implicits.db._

  def apply[F[+_] : ContextShift : Async]: WalletRepository[F] = new WalletRepository[F] {
    override def create
    (
      playerId: EntityId,
      currencyId: EntityId,
      balance: WalletBalance
    ): ConnectionIO[Either[WalletError, WalletModel]] =
      for {
        eid    <- EntityId.of[ConnectionIO]
        result <- sql"""INSERT INTO Wallets (id, playerId, currencyId, balance)
                       |VALUES (${eid}, ${playerId}, ${currencyId}, ${balance.value});"""
          .stripMargin
          .update
          .withGeneratedKeys[EntityId]("id")
          .map { id =>
            WalletModel(id, playerId, currencyId, balance)
          }
          .attemptSomeSqlState {
            case `duplicateValues` => WalletAlreadyExists
            case _                 => WalletCreationError
          }
          .compile
          .lastOrError
      } yield result

    override def getBalance(walletId: EntityId): ConnectionIO[Option[Double]] =
      sql"SELECT balance FROM Wallets WHERE id = $walletId"
        .query[Double]
        .option

    override def setBalance(walletId: EntityId, balance: WalletBalance): ConnectionIO[Either[WalletError, Unit]] =
      sql"UPDATE Wallets SET balance = $balance WHERE id = $walletId;"
        .update
        .run
        .attemptSomeSqlState {
          _ => WalletIsNotFound
        }
        .map(_.void)
  }
}
