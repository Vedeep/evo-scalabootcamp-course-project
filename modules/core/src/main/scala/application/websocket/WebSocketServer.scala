package application.websocket

import cats.data.OptionT
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.effect.implicits._
import cats.implicits._
import org.http4s.{HttpRoutes, Response}
import org.http4s.dsl.io._
import org.http4s.websocket.WebSocketFrame
import fs2.Stream
import fs2.concurrent.Queue
import org.http4s.server.websocket.WebSocketBuilder
import org.typelevel.log4cats.Logger
import application.auth.AuthService
import application.games.{GamesService, GamesStore}
import application.players.{Player, PlayerService}
import scala.concurrent.ExecutionContext


object WebSocketServer {
  object JwtTokenMatcher extends QueryParamDecoderMatcher[String](name = "token")

  def make[F[+_] : Concurrent : Timer : Logger]
  (
    authService: AuthService[F],
    playerService: PlayerService[F],
    gamesService: GamesService[F],
    gamesStore: GamesStore[F],
    router: WebSocketRouter[F]
  )
  (implicit CF: ContextShift[F], EC: ExecutionContext): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "ws" / "v1" / "games" :? JwtTokenMatcher(token)  =>

      def onMessage(router: WebSocketRouter[F], session: WebSocketSession[F])(msg: WebSocketFrame): Stream[F, Unit] =
        msg match {
          case WebSocketFrame.Text(msg, _) => Stream.eval[F, Unit](router.execute(msg, session)
        )

        case _ => Stream.empty
      }

      def getPlayer(token: String): F[Option[Player]] = (for {
        playerId <- OptionT(authService.getPlayerIdFromToken(token))
        player   <- OptionT(playerService.getPlayerById(playerId).map(_.toOption))
      } yield player).value

      getPlayer(token).flatMap {
        case Some(player) =>
          for {
            _           <- Logger[F].info(s"Player connected: $player")
            queueInput  <- Queue.unbounded[F, WebSocketFrame]
            queueOutput <- Queue.unbounded[F, WebSocketFrame]
            session     <- WebSocketRouter.makeSession[F](gamesService, gamesStore, player, queueOutput)

            response <- WebSocketBuilder[F].build(
              receive = queueInput.enqueue,
              send    = queueOutput.dequeue,
              onClose = session.destroy *> Logger[F].info(s"Player disconnected: $player")
            )

            _ <- queueInput.dequeue
              .flatMap(onMessage(router, session))
              .compile
              .last
              .start

          } yield response

        case None =>
          for {
            _ <- Logger[F].error(s"Connection error: token = $token")
          } yield Response[F](status = Forbidden)
      }

  }
}
