package application.database

import cats.data.EitherT
import cats.effect.{Async, Blocker, ContextShift, Resource}
import cats.syntax.all._
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._

import scala.concurrent.ExecutionContext

trait Connection[F[_]] {
  def runQuery[A](query: ConnectionIO[A])(implicit trans: Option[Transactor[F]] = None): F[A]
  def runEitherQuery[E, A](query: ConnectionIO[Either[E, A]]): F[Either[E, A]]
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
  val foreignKeyViolation = SqlState("23506")
  val duplicateValues = SqlState("23506")
}

object Connection {
  def apply[F[_] : ContextShift: Async](config: ConnectionConfig)(implicit be: Blocker): Resource[F, Connection[F]] = {
    (for {
      ec <- ExecutionContexts.fixedThreadPool[F](size = 4)
      xa <- HikariTransactor.newHikariTransactor[F](
        driverClassName = config.driver,
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = ec,
        blocker = be,
      )
    } yield xa).map { cn =>
      new Connection[F] {
        override def runQuery[A](query: doobie.ConnectionIO[A])(implicit trans: Option[Transactor[F]] = None): F[A] = {
          trans match {
            case Some(cn) => query.transact(cn)
            case None     => transaction(query.transact(_))
          }
        }

        override def runEitherQuery[E, A](query: doobie.ConnectionIO[Either[E, A]]): F[Either[E, A]] =
          EitherT(query).transact(cn).value

        override def transaction[A](fa: Transactor[F] => F[A]): F[A] =
          fa(cn)
      }
    }
  }
}
