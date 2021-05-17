package application.wallets

import cats.Monad
import cats.data.EitherT
import cats.syntax.all._
import application.common.Errors.AppError
import application.common.Errors.WalletErrors.{WalletBalanceIsLessThanNeed, WalletIsNotFound}
import application.database.{TransactionRepository, TransactionType, WalletBalance}
import application.players.PlayerWallet
import application.common.EntityId
import application.common.Errors.WalletError
import application.database.{Connection, WalletRepository}

trait WalletService[F[+_]] {
  def doTransaction
  (
    wallet: PlayerWallet,
    transaction: WalletTransaction
  ): F[Either[AppError, WalletTransactionResult]]

  def getBalance(walletId: EntityId): F[Either[WalletError, Double]]
}

case class WalletTransactionAmount(value: Double) extends AnyVal
case class WalletTransaction(transactionType: TransactionType, amount: WalletTransactionAmount)
case class WalletTransactionId(value: EntityId)
case class WalletTransactionResult(transactionId: EntityId)

object WalletService {
  import application.database.TransactionTypes._

  def make[F[+_] : Monad]
  (
    connection: Connection[F],
    walletRepo: WalletRepository[F],
    transactionRepo: TransactionRepository[F]
  ): WalletService[F] = new WalletService[F] {
    override def getBalance(walletId: EntityId): F[Either[WalletError, Double]] =
      walletRepo.getBalance(walletId).map(_.toRight(WalletIsNotFound))

    override def doTransaction
    (
      wallet: PlayerWallet,
      transaction: WalletTransaction
    ): F[Either[AppError, WalletTransactionResult]] =
      connection.transaction { cn =>
        (for {
          balanceBefore <- EitherT.fromOptionF[F, AppError, Double](walletRepo.getBalance(wallet.id), WalletIsNotFound)
          balanceAfter  <- EitherT.fromEither[F](transaction.transactionType match {
            case Deposit  => Right(balanceBefore + transaction.amount.value)
            case Withdrawal if balanceBefore - transaction.amount.value >= 0 =>
              Right(balanceBefore - transaction.amount.value)
            case _ => Left(WalletBalanceIsLessThanNeed.asInstanceOf[AppError])
          })
          transactionId <- EitherT(transactionRepo.create(
            wallet.id,
            transaction.transactionType,
            transaction.amount.value,
            balanceBefore,
            balanceAfter,
          )(cn.some))
          _ <- EitherT[F, AppError, Unit](walletRepo.setBalance(wallet.id, WalletBalance(balanceAfter))(cn.some))
        } yield WalletTransactionResult(transactionId)).value
      }
  }
}
