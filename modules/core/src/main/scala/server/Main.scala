package server

import akka.actor.{ActorRef, ActorSystem, Props}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ContextShift, ExitCase, ExitCode, IO, IOApp, Resource, Sync}
import cats.implicits._
import cats.effect.implicits._
import cats.implicits.catsSyntaxApplicativeId
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorOf, ActorRefOf, Ask, Call, Envelope, Receive, ReceiveOf, Tell}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits._

import scala.concurrent.duration._
import server.websocket.WebSocketServer

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import server.players.{PlayerActor, PlayerRequest, PlayerResponse}

object MainOld extends IOApp {
  override implicit val executionContext: ExecutionContext
    = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  private val wsRoutes = WebSocketServer.make[IO]
  private val httpApp = wsRoutes.orNotFound

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](ExecutionContext.global)
      .bindHttp(port = 9876, host = "localhost")
      .withHttpApp(httpApp)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
}

object Main extends IOApp {
//  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
  implicit val ec: ExecutionContext = ExecutionContext.global

  private def getActorSystem(implicit executor: ExecutionContext): Resource[IO, ActorSystem] = {
    Resource.makeCase[IO, ActorSystem](
      IO(ActorSystem(
        name = "test",
        //          config = config.some,
        defaultExecutionContext = Some(executor),

      )).handleErrorWith(e => {
        println("on error")
        println(e)
        IO(ActorSystem(
          name = "test",
          //          config = config.some,
          defaultExecutionContext = Some(executor),

        ))
      })
    )((actorSystem: ActorSystem, ec: ExitCase[Throwable]) =>
      IO(println("before terminate")) *> IO(println("before terminate", ec)) *>
      IO.fromFuture(IO.pure(actorSystem.terminate())) *> IO(println("TERMINATED"))
        *> IO(new Throwable("test").printStackTrace())
    )
  }

  private def getActorSystem2(implicit executor: ExecutionContext): IO[ActorRefOf[IO]] =
    Resource.eval[IO, ActorSystem](IO(ActorSystem(
      name = "test",
      defaultExecutionContext = Some(executor),
    ))).use[IO, ActorRefOf[IO]](system => IO(ActorRefOf.fromActorRefFactory[IO](system)))

  private def receiveOf2: ReceiveOf[IO, Any, Boolean] = ReceiveOf[IO] { ctx =>
    Resource
      .eval(IO(new PlayerActor[IO](ctx).asInstanceOf[Receive[IO, Any, Boolean]]))
  }

  private def actorEffectOf2(actorRefOf: ActorRefOf[IO], receiveOf: ReceiveOf[IO, Any, Boolean]): IO[ActorEffect[IO, Any, Any]] =
    ActorEffect.of[IO](actorRefOf, receiveOf).use(actor => IO(actor))

  private def receive[F[_]: Concurrent: ToFuture: FromFuture](
                                                               actorSystem: ActorSystem,
                                                               shift: F[Unit]
                                                             ): F[Unit] = {

    case class GetAndInc(delay: F[Unit])

    def receiveOf = ReceiveOf.const {
      val receive = for {
        state <- Ref[F].of(0)
      } yield {
        Receive[Call[F, Any, Any]] { call =>
          call.msg match {
            case a: GetAndInc =>
              println("on get and inc")
              for {
                _ <- shift
                _ <- a.delay
                a <- state.modify { a => (a + 1, a) }
                _ <- call.reply(a)
              } yield false

            case _ => shift as false
          }
        } {
          false.pure[F]
        }
      }
      Resource.make { shift productR receive } { _ => shift }
    }

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    ActorEffect
      .of[F](actorRefOf, receiveOf)
      .use { actorRef =>
        val timeout = 1.seconds

        def getAndInc(delay: F[Unit]) = {
          actorRef.ask(GetAndInc(delay), timeout)
        }

        for {
          a   <- getAndInc(().pure[F]).flatten
        } yield {}
      }
  }

  case class Message1()
  case class Message2()

  private def receiveOf: ReceiveOf[IO, Call[IO, Any, Any], Boolean] = ReceiveOf[IO] { ctx =>
    Resource.eval(IO(new PlayerActor[IO](ctx).asInstanceOf[Receive[IO, Any, Boolean]]))
//    Resource.make(
//      IO(println("tezd")).map(_ => new PlayerActor[IO](ctx).asInstanceOf[Receive[IO, Any, Boolean]]).handleErrorWith(e => {
//        println("on error")
//        println(e)
//        IO(new PlayerActor[IO](ctx).asInstanceOf[Receive[IO, Any, Boolean]])
//      })
//    )( res =>
//      IO(println("on resource close", res))
//    )
  }

  override def run(args: List[String]): IO[ExitCode] = {
//    val testRes = Resource.make(
//      IO(ActorSystem(
//        name = "test",
//        //          config = config.some,
//        defaultExecutionContext = Some(ec),
//
//      ))
//    )(actorSystem =>
//      IO.fromFuture(IO.pure(actorSystem.terminate())) *> IO(println("TERMINATED"))
////      IO(println("Release Start")) *>
////        IO.sleep(1.second) *>
////        IO(println("Release Finish"))
//    )

//    def getAllActors: IO[ActorEffect[IO, Any, Any]] = {
//      for {
//        actorSystem <- getActorSystem
//        actorRefOf   = ActorRefOf.fromActorRefFactory[IO](actorSystem)
//        actor       <- ActorEffect.of[IO](actorRefOf, receiveOf)
//      } yield actor
//    }

    for {
      stopped <- Deferred[IO, Unit]
//      fiber <- testRes.use(_ =>
//        IO(println("Operation Start")) *>
//          IO.sleep(1.second) *>
//          IO(println("Operation Finish"))
//      ).start
//      _ <- fiber.join

//      _ <- testRes.use { _ =>
//        IO(println("I use it")) *> stopped.get *> IO(println("on stopped get"))
//      }.start
//      actorSystemF <- getActorSystem2.start
//      actorSystem  <- actorSystemF.join
//      receive      <- IO(receiveOf2)
//      myActorF     <- actorEffectOf2(actorSystem, receive).start
//      myActor      <- myActorF.join
//      _ <- IO(println(myActor, actorSystem))
//      _ <- myActor.tell(Message1())
//      _ <- myActor.tell(Message2())
//      _ <- myActor.tell(Message1())
//      myActor2     <- actorEffectOf2(actorSystem, receive)
//      _ <- myActor2.tell(Message1())
//      _ <- myActor2.tell(Message2())
//      _ <- myActor2.tell(Message1())

      fiber2 <- getActorSystem.use { system: ActorSystem =>
        for {
          actorRefOf <- IO(ActorRefOf.fromActorRefFactory[IO](system))
          _ <- IO(println("actor ref", actorRefOf))
          fiber <- ActorEffect.of[IO](actorRefOf, receiveOf).use { actor =>
            val tell = Tell.fromActorRef[IO](actor.toUnsafe)
            val ask = Ask.fromActorRef[IO](actor.toUnsafe)


            tell(Message1())
              .productR({
                println("test")
                tell(Message2())
              })
              .productR(tell(Message2()))
              .map(_ => println("telled"))
              .map(_ => println("after stopped"))
              .flatMap(_ => IO.sleep(3.seconds))
          }.start
          _ <- fiber.join
          _ <- ActorEffect.of[IO](actorRefOf, receiveOf).use { actor =>
            IO(println("getted res", actor))
            val tell = Tell.fromActorRef[IO](actor.toUnsafe)
            val ask = Ask.fromActorRef[IO](actor.toUnsafe)

            IO.delay()
            .flatMap(_ =>
            tell(Message1())
              .productR({
                println("test")
                tell(Message2())
              })
              .productR(tell(Message2()))
              .map(_ => println("telled"))
//              .productR(stopped.get)
              .map(_ => println("after stopped")))

          }
          _ <- stopped.get.map(_ => "ON HARD STOPPED")
        } yield println("stopped anus")

//        stopped.get.map(_ => "ON HARD STOPPED")
//        receive(system, ContextShift[IO].shift)
      }
//      _ <- fiber2.join
//      _ <- IO.sleep(1.seconds).foreverM
//      actorSystem = getActorSystem
//      _ <- receive[IO]
//      actorSystem <- getActorSystem
//      actorRefOf = ActorRefOf.fromActorRefFactory[IO](actorSystem)
//      receiveOf = ReceiveOf[IO] { ctx =>
//        Resource.make {
//          IO.delay(new PlayerActor[IO](ctx))
//        } { actor =>
//          IO.pure()
//        }
//      }
//      test = ActorOf[IO](receiveOf.asInstanceOf[ReceiveOf[IO, Envelope[Any], Boolean]])
//      player <- actorRefOf(Props(test))
//      actor <- ActorOf[IO] { ctx =>
//
//      }
//      actor <- ActorOf(ReceiveOf[IO] { actorCtx => new PlayerActor[IO](actorCtx) })
    } yield ExitCode.Success
  }
}