package server.database

import cats.effect.{Async, ContextShift}
import doobie._
import doobie.implicits._

object traits {
  trait Connection {
    def runQuery[F[_] : ContextShift : Async, A](query: ConnectionIO[A]): F[A]
  }
}
//
//object ConnectionOps extends traits.Connection {
//  override def runQuery[F[_] : ContextShift : Async, A](query: doobie.ConnectionIO[A], con: Transactor[F]): F[A] = {
//    con.use {}
//  }
//}

