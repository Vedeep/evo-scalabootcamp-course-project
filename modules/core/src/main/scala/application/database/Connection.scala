package application.database

import cats.effect.{Async, Blocker, ContextShift}
import cats.syntax.all._
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._

import scala.concurrent.ExecutionContext

trait Connection[F[_]] {
  def runQuery[A](query: ConnectionIO[A])(implicit trans: Option[Transactor[F]] = None): F[A]
  def transaction[A](fa: Transactor[F] => F[A]): F[A]
}

trait ConnectionConfig {
  def driver: String
  def url: String
  def user: String
  def password: String
  def poolSize: Int
}

object SqlStates {
  val FOREIGN_KEY_VIOLATION = SqlState("23506")
  val DUPLICATE_VALUES = SqlState("23506")
}

object Connection {
  def make[F[_] : ContextShift: Async](config: ConnectionConfig)(implicit EC: ExecutionContext): F[Connection[F]] = {
    Async[F].delay {
      for {
        be <- Blocker[F]
        xa <- HikariTransactor.newHikariTransactor[F](
          driverClassName = config.driver,
          url = config.url,
          user = config.user,
          pass = config.password,
          connectEC = EC,
          blocker = be,
        )
      } yield xa
    }.map { pool =>
      new Connection[F] {
        override def runQuery[A](query: doobie.ConnectionIO[A])(implicit trans: Option[Transactor[F]] = None): F[A] = {
          trans match {
            case Some(cn) => query.transact(cn)
            case None     => transaction(query.transact(_))
          }

        }

        override def transaction[A](fa: Transactor[F] => F[A]): F[A] =
          pool.use { cn =>
            fa(cn)
          }
      }
    }
  }
}