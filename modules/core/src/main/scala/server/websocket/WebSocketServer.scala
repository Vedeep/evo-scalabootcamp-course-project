package server.websocket

import cats.data.EitherT
import cats.effect.{Async, Blocker, Concurrent, ContextShift, SyncIO, Timer}
import cats.effect.implicits._
import cats.{Applicative, Defer, Functor, Monad, MonadError}
import cats.implicits._
import cats.syntax._

import scala.concurrent.duration._
import org.http4s.HttpRoutes
import org.http4s.syntax._
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.websocket.WebSocketFrame
import fs2.{Pipe, Pure, Stream}
import fs2.concurrent.{Queue, Topic}
import io.circe.Decoder
import org.http4s.server.websocket.WebSocketBuilder
import server.common.EntityId

import scala.concurrent.Promise
import scala.util.Try
import server.players.{Player, PlayerActor, PlayerError, PlayerService}

import java.util.UUID
import scala.concurrent.ExecutionContext


object WebSocketServer {
  import io.circe.jawn.decode
  import org.http4s.circe.CirceEntityCodec._
  import io.circe.generic.auto._

  import Errors._

  def make[F[_] : Concurrent : Timer]
  (/*gamesActor: GamesActor, playerService: PlayerService*/)
  (implicit CF: ContextShift[F], EC: ExecutionContext): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "ws" / "v1" / "games" =>

      def onMessage(router: WebSocketRouter[F])(msg: WebSocketFrame): Stream[F, Unit] = msg match {
        case WebSocketFrame.Text(msg, _) => Stream.eval[F, Unit](router.execute(msg)
        )
//        case _ => Stream.eval[F, WebSocketFrame](Concurrent[F].pure(WebSocketFrame.Text("")))
      }

      // @TODO jwt token + playerId
//      def getPlayer: F[Either[WSError, Player]] = (for {
////          playerId <- EitherT.rightT[F, EntityId](EntityId(UUID.randomUUID()))
//          player   <- EitherT[F, PlayerError, Player](playerService.getPlayerById[F](EntityId(UUID.randomUUID())))
//            .leftMap[WSError](_ => UserIsNotFound)
//        } yield player).value

      for {
        queue        <- Queue.unbounded[F, WebSocketFrame]
        queueOutput <- Queue.unbounded[F, WebSocketFrame]

        session = WebSocketRouter.makeSession[F](new Player {
          override def id: EntityId = EntityId(UUID.randomUUID())
        }, queueOutput)
        router  = WebSocketRouter.make[F](session)

        response <- WebSocketBuilder[F].build(
          receive = queue.enqueue,
          send =   queueOutput.dequeue
        )

        _ <- Concurrent[F].pure(println("test"))
        _ <- queue.dequeue
          .flatMap(onMessage(router))
          .compile
          .last
          .start
        _ <- Concurrent[F].pure(println("after test"))

      } yield response

  }
}




//      for {
//        player <- getPlayer
//      } yield player match {
//        case Right(player) => for {
//          queueIncome  <- Queue.unbounded[F, WebSocketFrame]
//          queueOutput <- Queue.unbounded[F, WebSocketFrame]
//
//          session <- Concurrent[F].pure(WebSocketRouter.makeSession[F](player, queueOutput))
//
//          response <- WebSocketBuilder[F].build(
//            receive = queueIncome.enqueue,
//            send = queueOutput.dequeue.flatMap {
//              case WebSocketFrame.Text(message, _) => {
//                println("on message", message)
//                Stream.eval(router.execute(message))
//              }
//            },
//          )
//        } yield response
//
////        case Left(error) => BadRequest()
//      }


//        _ <- Concurrent[F].background(queue.dequeue.flatMap(onMessage(router)).compile.drain).use { d => Concurrent[F].pure()}.start


//        _ <- Timer[F].sleep(1.seconds).flatMap(_ => {
//          println("on call")
//          queueOutput.enqueue1(WebSocketFrame.Text("ggggg"))
//        }).foreverM[Unit].start


//var isMyPromise: Promise[Unit] = Promise()
//
//def getPromise(): Promise[Unit] = isMyPromise
//
//def resolvePromise(): Unit = {
//  val oldPromise = isMyPromise
//  isMyPromise = Promise()
//  oldPromise.complete(Try())
//}

//_ <- Concurrent[F].start[Unit](Timer[F].sleep(1.seconds).flatMap(_ => {
//println("test")
//queue2.enqueue1(WebSocketFrame.Text("test1"))
////          queue2.enqueue1(WebSocketFrame.Text("test2"))
////          queue2.enqueue1(WebSocketFrame.Text("test3"))
////          toClient.append(Stream.emit(WebSocketFrame.Text("test123")))
////          sendStream.append()
////          queue.enq()
//
//
//}).foreverM)
//
//_ <- Timer[F].sleep(1.seconds).map(_ => {
//println("resolve promise")
//resolvePromise()
//}).foreverM[Unit].start
//
//_ <- Concurrent[F].async[Unit](cb => {
//println("try to get promise")
//getPromise().future.onComplete(_ => cb)
//}).foreverM[Unit].start
//
//_ <- Concurrent[F].pure(println("test"))


//          .flatMap(msg => {
//            Stream.eval(queue.enqueue1(msg))
//          })
//          .map(m => {
//            println("after message", m)
//            m
//          })
//          .observeAsync(1)(getPipe(session))
//          .concurrently(queueOutput.dequeue)
