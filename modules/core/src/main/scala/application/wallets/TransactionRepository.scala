package application.wallets

import application.common.EntityId
import application.common.Errors.WalletError
import application.common.Errors.WalletErrors.WalletIsNotFound
import application.database.Connection
import cats.effect.Sync
import cats.syntax.all._
import doobie.implicits._
import doobie.{ConnectionIO, Transactor}

trait TransactionRepository[F[_]] {
  def create
  (
    walletId: EntityId,
//    gameRoundId: EntityId,
    transactionType: TransactionType,
    amount: Double,
    balanceBefore: Double,
    balanceAfter: Double,
    status: TransactionStatus = TransactionStatuses.Completed
  ): ConnectionIO[Either[WalletError, EntityId]]
}

trait TransactionType
object TransactionTypes {
  case object Withdrawal extends TransactionType {
    override def toString: String = "withdrawal"
  }
  case object Deposit extends TransactionType {
    override def toString: String = "deposit"
  }
}

trait TransactionStatus
object TransactionStatuses {
  case object New extends TransactionStatus {
    override def toString: String = "new"
  }
  case object Completed extends TransactionStatus {
    override def toString: String = "completed"
  }
}

object TransactionRepository {
  import application.common.EntityId.implicits.db._

  def apply[F[_] : Sync]: TransactionRepository[F] =
    new TransactionRepository[F] {
      override def create
      (
        walletId: EntityId,
//        gameRoundId: EntityId,
        transactionType: TransactionType,
        amount: Double,
        balanceBefore: Double,
        balanceAfter: Double,
        status: TransactionStatus
      ): ConnectionIO[Either[WalletError, EntityId]] =
        for {
          transId <- EntityId.of[ConnectionIO]
          result  <- sql"""INSERT INTO Transactions(
            |id,
            |walletId,
            |transactionType,
            |amount,
            |balanceBefore,
            |balanceAfter,
            |status
             ) VALUES (
             | ${transId},
             | ${walletId},
             | ${transactionType.toString},
             | ${amount},
             | ${balanceBefore},
             | ${balanceAfter},
             | ${status.toString}
             | );"""
            .stripMargin
            .update
            .withGeneratedKeys[EntityId]("id")
            .attemptSomeSqlState {
              case _ => WalletIsNotFound
            }
            .compile
            .lastOrError
        } yield result
    }
}
