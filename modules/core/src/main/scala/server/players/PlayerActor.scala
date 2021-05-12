package server.players

import cats.Applicative
import cats.effect.Sync
import com.evolutiongaming.akkaeffect.ActorOf.Stop
import com.evolutiongaming.akkaeffect.{ActorCtx, Call, Receive}

class PlayerActor[F[_] : Sync](context: ActorCtx[F])
  extends Receive[F, Call[F, PlayerRequest, PlayerResponse], Stop] {

  println("cont", context)

  override def apply(msg: Call[F, PlayerRequest, PlayerResponse]): F[Boolean] = {
    println("on message", msg)
    Sync[F].delay(false)
  }

  override def timeout: F[Boolean] = {
    println("on timeout")
    Sync[F].delay(false)
  }

//  def postStop: Unit = {
//    println("on post stop")
//  }
}

trait PlayerRequest
trait PlayerResponse
