package application.wallets

import cats.Monad
import cats.data.EitherT
import cats.syntax.all._
import application.common.Errors.WalletErrors.{WalletBalanceIsLessThanNeed, WalletIsNotFound}
import application.players.PlayerWallet
import application.common.EntityId
import application.common.Errors.WalletError
import application.database.Connection
import doobie.ConnectionIO

trait WalletService[F[+_]] {
  def doTransaction
  (
    wallet: PlayerWallet,
    transaction: WalletTransaction
  ): F[Either[WalletError, WalletTransactionResult]]

  def getBalance(walletId: EntityId): F[Either[WalletError, Double]]
}

final case class WalletTransactionAmount(value: Double) extends AnyVal
final case class WalletTransaction(transactionType: TransactionType, amount: WalletTransactionAmount)
final case class WalletTransactionId(value: EntityId)
final case class WalletTransactionResult(transactionId: EntityId)

object WalletService {
  import TransactionTypes._

  def make[F[+_] : Monad]
  (
    connection: Connection[F],
    walletRepo: WalletRepository[F],
    transactionRepo: TransactionRepository[F]
  ): WalletService[F] = new WalletService[F] {
    override def getBalance(walletId: EntityId): F[Either[WalletError, Double]] =
      connection
        .runQuery(walletRepo.getBalance(walletId))
        .map(_.toRight(WalletIsNotFound))

    override def doTransaction
    (
      wallet: PlayerWallet,
      transaction: WalletTransaction
    ): F[Either[WalletError, WalletTransactionResult]] =
      connection.runEitherQuery(
        (for {
          balanceBefore <- EitherT.fromOptionF[ConnectionIO, WalletError, Double](walletRepo.getBalance(wallet.id), WalletIsNotFound)
          balanceAfter  <- EitherT.fromEither[ConnectionIO](transaction.transactionType match {
            case Deposit  => Right(balanceBefore + transaction.amount.value)
            case Withdrawal if balanceBefore - transaction.amount.value >= 0 =>
              Right(balanceBefore - transaction.amount.value)
            case _ => Left(WalletBalanceIsLessThanNeed)
          })
          transactionId <- EitherT(transactionRepo.create(
            wallet.id,
            transaction.transactionType,
            transaction.amount.value,
            balanceBefore,
            balanceAfter,
          ))
          _ <- EitherT[ConnectionIO, WalletError, Unit](
            walletRepo.setBalance(wallet.id, WalletBalance(balanceAfter))
          )
        } yield WalletTransactionResult(transactionId)).value
      )
  }
}
