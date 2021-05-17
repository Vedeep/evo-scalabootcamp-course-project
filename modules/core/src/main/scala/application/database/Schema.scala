package application.database

import cats.effect.Bracket
import doobie.ConnectionIO
import doobie.implicits._
import cats.syntax.all._

object Schema {
  def createTables[F[_]](connection: Connection[F])(implicit BF: Bracket[F, Throwable]): F[Unit] =
    connection.transaction { cn =>
      createPlayerTable.transact(cn) *>
      createCurrencyTable.transact(cn) *>
      createWalletTable.transact(cn) *>
      createGameTable.transact(cn) *>
      createGameRoundTable.transact(cn) *>
      createTransactionTable.transact(cn)
    }

  private def createPlayerTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS Players(
       | id UUID PRIMARY KEY,
       | firstName varchar(100) not null,
       | lastName varchar(100) not null,
       | nickName varchar(100) not null,
       | createdAt timestamp not null default now(),
       | updatedAt timestamp not null default now()
       | );"""
      .stripMargin
      .update
      .run
      .void

  private def createCurrencyTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS Currencies(
         | id UUID PRIMARY KEY,
         | name varchar(50) not null,
         | code varchar(3) not null unique,
         | exchangeRate numeric(13,8) NOT NULL,
         | main boolean not null default false,
         | createdAt timestamp not null default now(),
         | updatedAt timestamp not null default now()
         | );"""
      .stripMargin
      .update
      .run
      .void

  private def createWalletTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS Wallets(
         | id UUID PRIMARY KEY,
         | playerId UUID not null unique,
         | currencyId UUID not null,
         | balance numeric(19,8) not null default 0,
         | createdAt timestamp not null default now(),
         | updatedAt timestamp not null default now(),
         | FOREIGN KEY (playerId) REFERENCES Players(id),
         | FOREIGN KEY (currencyId) REFERENCES Currencies(id)
         | );"""
      .stripMargin
      .update
      .run
      .void

  private def createTransactionTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS Transactions(
         | id UUID PRIMARY KEY,
         | walletId UUID not null,
         | transactionType enum('withdrawal', 'deposit') not null,
         | amount numeric(19,8) not null,
         | balanceBefore numeric(19,8) not null,
         | balanceAfter numeric(19,8) not null,
         | status enum('new', 'completed'),
         | createdAt timestamp not null default now(),
         | updatedAt timestamp not null default now(),
         | FOREIGN KEY (walletId) REFERENCES Wallets(id)
         | );"""
      .stripMargin
      .update
      .run
      .void

//  private def createTransactionTable: ConnectionIO[Unit] =
//    sql"""CREATE TABLE IF NOT EXISTS Transactions(
//         | id UUID PRIMARY KEY,
//         | walletId UUID not null,
//         | gameRoundId UUID null,
//         | transactionType enum('withdrawal', 'deposit') not null,
//         | amount numeric(19,8) not null,
//         | balanceBefore numeric(19,8) not null,
//         | balanceAfter numeric(19,8) not null,
//         | status enum('new', 'completed'),
//         | createdAt timestamp not null default now(),
//         | updatedAt timestamp not null default now(),
//         | FOREIGN KEY (walletId) REFERENCES Wallets(id),
//         | FOREIGN KEY (gameRoundId) REFERENCES GameRounds(id)
//         | );"""
//      .stripMargin
//      .update
//      .run
//      .void

  private def createGameTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS Games(
         | id UUID PRIMARY KEY,
         | gameType enum('black-jack') not null,
         | createdAt timestamp not null default now(),
         | updatedAt timestamp not null default now()
         | );"""
      .stripMargin
      .update
      .run
      .void

  private def createGameRoundTable: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS GameRounds(
         | id UUID PRIMARY KEY,
         | gameId UUID not null,
         | createdAt timestamp not null default now(),
         | updatedAt timestamp not null default now(),
         | FOREIGN KEY (gameId) REFERENCES Games(id)
         | );"""
      .stripMargin
      .update
      .run
      .void
}
