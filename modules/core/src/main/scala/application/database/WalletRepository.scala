package application.database

import cats.effect.{Async, ContextShift}
import application.common.EntityId
import application.common.Errors.{DBError, WalletError}
import doobie.implicits._
import cats.syntax.all._
import doobie.Transactor
import application.common.Errors.DBErrors.{DuplicateValues, PlayerOrCurrencyNotFound}
import application.common.Errors.WalletErrors.WalletIsNotFound
import application.database.SqlStates.{DUPLICATE_VALUES, FOREIGN_KEY_VIOLATION}

case class WalletBalance(value: Double) extends AnyVal

case class WalletModel
(
  id: EntityId,
  playerId: EntityId,
  currencyId: EntityId,
  balance: WalletBalance
)

trait WalletRepository[F[+_]] {
  def create(playerId: EntityId, currencyId: EntityId, balance: WalletBalance): F[Either[DBError, WalletModel]]
  def getBalance(walletId: EntityId): F[Option[Double]]
  def setBalance(walletId: EntityId, balance: WalletBalance)(cn: Option[Transactor[F]] = None): F[Either[WalletError, Unit]]
}

object WalletRepository {
  import application.common.EntityId.implicits._

  def make[F[+_] : ContextShift : Async](connection: Connection[F]): WalletRepository[F] = new WalletRepository[F] {
    override def create
    (
      playerId: EntityId,
      currencyId: EntityId,
      balance: WalletBalance
    ): F[Either[DBError, WalletModel]] =
      connection.runQuery(
        sql"""INSERT INTO Wallets (id, playerId, currencyId, balance)
             |VALUES (${EntityId()}, ${playerId}, ${currencyId}, ${balance.value});"""
          .stripMargin
          .update
          .withGeneratedKeys[EntityId]("id")
          .map { id =>
            WalletModel(id, playerId, currencyId, balance)
          }
          .attemptSomeSqlState {
            case DUPLICATE_VALUES => DuplicateValues
            case FOREIGN_KEY_VIOLATION => PlayerOrCurrencyNotFound
          }
          .compile
          .lastOrError
      )

    override def getBalance(walletId: EntityId): F[Option[Double]] =
      connection.runQuery(
        sql"SELECT balance FROM Wallets WHERE id = $walletId"
          .query[Double]
          .option
      )

    override def setBalance
    (walletId: EntityId, balance: WalletBalance)
    (cn: Option[Transactor[F]] = None): F[Either[WalletError, Unit]] = {
      val query = sql"UPDATE Wallets SET balance = $balance WHERE id = $walletId;"
        .update
        .run
        .attemptSomeSqlState {
          _ => WalletIsNotFound
        }
        .map(_.void)

      cn match {
        case Some(trans) => query.transact(trans)
        case None        => connection.runQuery(query)
      }
    }
  }
}
