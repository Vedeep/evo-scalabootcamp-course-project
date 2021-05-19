package application.database

import cats.effect.Sync
import application.common.EntityId
import doobie.implicits._
import doobie.syntax._
import cats.syntax.all._
import doobie.Transactor
import application.common.Errors.AppError
import application.common.Errors.DBErrors.CreationError
import application.common.Errors.WalletErrors.WalletIsNotFound
import application.database.SqlStates._

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
  )(implicit cn: Option[Transactor[F]] = None): F[Either[AppError, EntityId]]
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

  def make[F[_] : Sync](connection: Connection[F]): TransactionRepository[F] =
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
      )(implicit cn: Option[Transactor[F]] = None): F[Either[AppError, EntityId]] = {
        for {
          transId <- EntityId.of[F]
          query =
          sql"""INSERT INTO Transactions(
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
              case `foreignKeyViolation` => WalletIsNotFound
              case _                     => CreationError
            }
            .compile
            .lastOrError
          result <- connection.runQuery(query)(cn)
        } yield result


      }
    }
}
