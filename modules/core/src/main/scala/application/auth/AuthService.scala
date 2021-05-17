package application.auth

import cats.effect.Sync
import pdi.jwt.algorithms.JwtHmacAlgorithm
import application.common.EntityId
import pdi.jwt.{Jwt, JwtClaim}

import java.time.Clock
import scala.util.{Failure, Success}

trait AuthService[F[_]] {
  def getPlayerIdFromToken(token: String): F[Option[EntityId]]
  def createToken(playerId: EntityId): F[String]
}

trait AuthServiceConfig {
  def secretKey: String
  def algo: JwtHmacAlgorithm
  def expirationSeconds: Long
}

case class TokenData(id: EntityId)

object AuthService {
  import application.common.EntityId.implicits._
  import io.circe.jawn.decode
  import io.circe.generic.auto._
  import io.circe.syntax._

  def make[F[_] : Sync](config: AuthServiceConfig): AuthService[F] = new AuthService[F] {
    override def getPlayerIdFromToken(token: String): F[Option[EntityId]] =
      Sync[F].delay {
        Jwt.decode(token, config.secretKey, Seq(config.algo)) match {
          case Success(value) => decode[TokenData](value.content).map(_.id).toOption
          case Failure(_) => None
        }
      }

    override def createToken(playerId: EntityId): F[String] =
      Sync[F].delay {
        val clock = Clock.systemDefaultZone()

        Jwt.encode(
          JwtClaim(TokenData(playerId).asJson.noSpaces)
            .issuedNow(clock)
            .expiresIn(config.expirationSeconds)(clock),
          config.secretKey,
          config.algo
        )
      }
  }
}
