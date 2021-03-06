package application

import cats.data.EitherT
import cats.effect.{Blocker, ContextShift, ExitCode, IO, IOApp, Resource, Timer}
import cats.implicits._
import io.circe.Json
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.dsl.io._
import application.database.{Connection, ConnectionConfig, Schema}
import application.games.Games.GameTypes.BlackJackType
import application.games.{GamesService, GamesStore}
import application.websocket.{WebSocketRouter, WebSocketServer}
import org.http4s.implicits._
import org.http4s.server.middleware.CORS

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import application.games.BlackJack.BlackJackConfig
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pdi.jwt.JwtAlgorithm
import application.auth.{AuthService, AuthServiceConfig}
import application.common.Errors.AppError
import application.config.{ConfigService, DatabaseConfig, ServerConfig}
import application.players.{PlayerFirstName, PlayerLastName, PlayerModel, PlayerNickName, PlayerRepository, PlayerService}
import application.wallets.{CurrencyCode, CurrencyModel, CurrencyName, CurrencyRepository, TransactionRepository, WalletBalance, WalletModel, WalletRepository, WalletService}


object Main extends IOApp {
  override implicit val executionContext: ExecutionContext
    = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))

  private def createLogger: Logger[IO] =
    Slf4jLogger.getLogger[IO]

  private def createServer(config: ServerConfig, httpApp: HttpApp[IO]): IO[Unit] =
    BlazeServerBuilder[IO](ExecutionContext.global)
    .bindHttp(port = config.port, host = config.host)
    .withHttpApp(CORS(httpApp))
    .serve
    .compile
    .drain

  private def createConnection
  (config: DatabaseConfig)
  (implicit be: Blocker): Resource[IO, Connection[IO]] =
    Connection[IO](new ConnectionConfig {
      override def driver: String = config.driver

      override def url: String = config.url

      override def user: String = config.user

      override def password: String = config.password

      override def poolSize: Int = config.poolSize
    })

  private def createRepositories
  (implicit logger: Logger[IO]): IO[(
    PlayerRepository[IO],
    CurrencyRepository[IO],
    WalletRepository[IO],
    TransactionRepository[IO]
  )] =
    IO(
      PlayerRepository[IO],
      CurrencyRepository[IO],
      WalletRepository[IO],
      TransactionRepository[IO]
    )

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val logger: Logger[IO] = createLogger
    implicit val blocker: Blocker = Blocker.liftExecutionContext(executionContext)
    val config = ConfigService.getConfig

    createConnection(config.db).use { connection =>
      for {
        _            <- logger.info("Start application")
        _            <- logger.info("Connection created")
        _            <- Schema.createTables[IO](connection)
        _            <- logger.info("Tables created")
        repositories <- createRepositories
        _            <- logger.info("Repositories created")
        (playerRepo, currencyRepo, walletRepo, transRepo) = repositories

        authService  <- IO(AuthService.make[IO](AuthServiceConfig(
          secretKey = config.auth.secretKey,
          expirationSeconds = config.auth.expirationSeconds
        )))

        playerService <- IO(PlayerService.make[IO](connection, playerRepo))
        walletService <- IO(WalletService.make[IO](connection, walletRepo, transRepo))
        gamesService  <- IO(GamesService.make[IO](walletService))
        gamesStore    <- gamesService.createStore
        router   = WebSocketRouter.make[IO](playerService, walletService, gamesService, gamesStore)
        wsRoutes = WebSocketServer.make[IO](authService, playerService, gamesService, gamesStore, router)
        demoRoutes <- Demo.start(connection, authService, gamesService, gamesStore, playerRepo, currencyRepo, walletRepo)
        httpApp  = (wsRoutes <+> demoRoutes).orNotFound
        _ <- createServer(config.server, httpApp)
      } yield ExitCode.Success
    }
  }
}

object Demo {
  private def createDemoData
  (
    connection: Connection[IO],
    playerRepo: PlayerRepository[IO],
    currencyRepo: CurrencyRepository[IO],
    walletRepo: WalletRepository[IO]
  )(implicit logger: Logger[IO]): IO[(PlayerModel, PlayerModel)] =
    (for {
      player1   <- EitherT[IO, AppError, PlayerModel](connection.runQuery(playerRepo.create(PlayerFirstName("Vasia"), PlayerLastName("Pupkin"), PlayerNickName("VasPupkin"))))
      player2   <- EitherT[IO, AppError, PlayerModel](connection.runQuery(playerRepo.create(PlayerFirstName("Petia"), PlayerLastName("Ivanov"), PlayerNickName("PetIvanov"))))

      currency1 <- EitherT[IO, AppError, CurrencyModel](connection.runQuery(currencyRepo.create(CurrencyName("Euro"), CurrencyCode("EUR"), exchangeRate = 1, main = true)))
      currency2 <- EitherT[IO, AppError, CurrencyModel](connection.runQuery(currencyRepo.create(CurrencyName("Dollar"), CurrencyCode("USD"), exchangeRate = 1.2)))

      _         <- EitherT[IO, AppError, WalletModel](connection.runQuery(walletRepo.create(player1.id, currency1.id, WalletBalance(10000))))
      _         <- EitherT[IO, AppError, WalletModel](connection.runQuery(walletRepo.create(player2.id, currency2.id, WalletBalance(5000))))
    } yield (player1, player2)).value.map {
      case Right((p1, p2)) => (p1, p2)
      case _ => throw new Exception("Players were not created")
    }

  def start
  (
    connection: Connection[IO],
    authService: AuthService[IO],
    gamesService: GamesService[IO],
    gameStore: GamesStore[IO],
    playerRepo: PlayerRepository[IO],
    currencyRepo: CurrencyRepository[IO],
    walletRepo: WalletRepository[IO]
  )(implicit CS: ContextShift[IO], T: Timer[IO], logger: Logger[IO]): IO[HttpRoutes[IO]] = {
    for {
      players <- createDemoData(connection, playerRepo, currencyRepo, walletRepo)
      player1 = players._1
      player2 = players._2
      token1 <- authService.createToken(player1.id)
      token2 <- authService.createToken(player2.id)
      games  <- (1 to 10).toList.traverse(_ => gamesService.createGame[BlackJackConfig](BlackJackType, new BlackJackConfig {
        override def seatCount: Int = 7

        override def playerSeatsLimit: Int = 1

        override def roundStateSeconds: Int = 10

        override def roundIntervalSeconds: Int = 5
      }))
      _ <- games.traverse(_.start.start)
      _ <- games.traverse(gameStore.addGame)
      routes <- IO.delay {
        import io.circe.syntax._
        import io.circe.generic.auto._

        HttpRoutes.of[IO] {
          case GET -> Root / "users" =>
            Ok(
              List((player1.nickName.value, token1), (player2.nickName.value, token2))
                .map { p =>
                  Json.obj(
                    ("nickName", p._1.asJson),
                    ("token", p._2.asJson)
                  )
                }.asJson.noSpaces
            )
        }
      }
    } yield routes
  }
}
