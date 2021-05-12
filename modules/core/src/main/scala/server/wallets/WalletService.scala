package server.wallets

import cats.implicits._
import cats.effect.{Async, ContextShift}
import cats.free.Free
import doobie.free.connection
import doobie.free.connection.{ConnectionIO, raiseError}
import server.players._
import server.wallets.traits.WalletTransactionType

import java.util.UUID
//import doobie._
//import doobie.hikari.HikariTransactor
import doobie.implicits._
//import doobie.implicits.javatime._
import server.database.traits.Connection
//import server.wallets.traits.WalletCurrency

object traits {

  trait Wallet {
    def id: UUID

    def playerId: UUID

    def currency: WalletCurrency
  }

  trait WalletService {
    def getPlayerWallet[F[_]](player: Player): F[Either[WalletError, Wallet]]

    def doTransaction[F[_] : Async]
    (
      wallet: Wallet,
      transaction: WalletTransaction
    ): F[Either[WalletError, WalletTransactionResult]]

    def rollbackTransaction[F[_] : Async]
    (
      transactionId: WalletTransactionId
    ): F[Either[WalletError, WalletTransactionResult]]

    def getBalance[F[_] : Async]
    (
      wallet: Wallet
    ): F[Either[WalletError, WalletBalance]]
  }

  trait WalletTransaction {
    def transactionType: WalletTransactionType

    def amount: WalletTransactionAmount

    def currency: WalletCurrency
  }

  trait WalletTransactionAmount {
    def value: Double
  }

  trait WalletCurrencyCode {
    def value: String
  }

  trait WalletCurrencyExchangeRate {
    def value: Double
  }

  trait WalletTransactionId {
    def value: UUID
  }

  trait WalletTransactionResult {
    def transactionId: WalletTransactionId
  }

  trait WalletCurrency {
    def code: WalletCurrencyCode

    def exchangeRate: WalletCurrencyExchangeRate
  }

  trait WalletBalance {
    def amount: Double

    def currency: WalletCurrency
  }

  trait WalletError

  sealed trait WalletTransactionType

  object WalletTransactionTypes {
    sealed class Deposit extends WalletTransactionType
    sealed class Withdraw extends WalletTransactionType
  }

}

object WalletErrors {
  case object WalletNotFound extends traits.WalletError
}

//object impl {
//  import traits.WalletTransactionTypes._
//
//  class WalletServiceDB[F[_]]
//  (
//    db: Connection
//  )
//  (
//    implicit CF: ContextShift[F],
//    AF: Async[F]
//  )
//  extends traits.WalletService {
//    private def getTransactionTypeAsString(t: WalletTransactionType): String = {
//      t match {
//        case _: Deposit  => "deposit"
//        case _: Withdraw => "withdraw"
//      }
//    }
//
//    override def getPlayerWallet(player: Player): F[Either[traits.WalletError, traits.Wallet]] = {
//      val query = sql"" // @TODO
//        .query[Wallet]
//        .option
//        .map(r => r.toRight(WalletErrors.WalletNotFound.asInstanceOf[traits.WalletError]))
//
//      db
//        .runQuery(query)
//    }
//
//    override def doTransaction
//    (
//      wallet: traits.Wallet,
//      transaction: traits.WalletTransaction
//    ): F[Either[traits.WalletError, traits.WalletTransactionResult]] = {
//      // @TODO convert amount by currency
//
//      val balanceQuery = sql"SELECT balance FROM Wallets WHERE id = ${wallet.id}".query[Double].unique
//
//      var query = fr"UPDATE Wallets"
//
//      transaction.transactionType match {
//        case _: Deposit  => query = query ++ fr"SET balance = balance + ${transaction.amount} WHERE id = ${wallet.id}"
//        case _: Withdraw =>
//          query = query ++
//            fr"""SET balance = balance - ${transaction.amount}
//                | WHERE id = ${wallet.id} AND balance - ${transaction.amount} > 0""".stripMargin
//      }
//
//      query = query
//
//      val transactionType = getTransactionTypeAsString(transaction.transactionType)
//      def transactionQuery(balanceBefore: Double, balanceAfter: Double) =
//        sql"""INSERT INTO Transactions
//             | (id, walletId, transactionType, amount, balanceBefore, balanceAfter, status)
//             | VALUES
//             | (${UUID.randomUUID()}, ${wallet.id}, ${transactionType},
//             | ${transaction.amount}, $balanceBefore, $balanceAfter 'completed')"""
//          .stripMargin
//          .update
//          .withGeneratedKeys[UUID]("id")
//          .map { id =>
//            WalletTransactionResult(WalletTransactionId(id))
//          }
//          .compile
//          .lastOrError
//
//      val test: Free[connection.ConnectionOp, WalletTransactionResult] = for {
//        balanceBefore <- balanceQuery
//        result        <- query.update.run
//        balanceAfter  <- balanceQuery
//        transaction   <- transactionQuery(balanceBefore, balanceAfter)
//      } yield transaction
//
//
////      db.runQuery(query >> transactionQuery.map { r =>
////        if (r > 0) Right()
////      })
//    }
//
//    override def rollbackTransaction[F[_] : Async](transactionId: traits.WalletTransactionId): F[Either[traits.WalletError, traits.WalletTransactionResult]] = ???
//
//    override def getBalance[F[_] : Async](wallet: traits.Wallet): F[Either[traits.WalletError, traits.WalletBalance]] = ???
//  }
//
//  case class Wallet(id: UUID, playerId: UUID, currency: WalletCurrency) extends traits.Wallet
//
//  case class WalletCurrency(code: traits.WalletCurrencyCode, exchangeRate: traits.WalletCurrencyExchangeRate)
//    extends traits.WalletCurrency
//
//  case class WalletTransactionResult(transactionId: WalletTransactionId) extends traits.WalletTransactionResult
//
//  case class WalletTransactionId(value: UUID) extends traits.WalletTransactionId
//}


