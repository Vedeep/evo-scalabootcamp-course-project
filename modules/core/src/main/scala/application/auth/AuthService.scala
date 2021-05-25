package application.auth

import cats.effect.Sync
import pdi.jwt.algorithms.JwtHmacAlgorithm
import application.common.EntityId
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.time.Clock

trait AuthService[F[_]] {
  def getPlayerIdFromToken(token: String): F[Option[EntityId]]
  def createToken(playerId: EntityId): F[String]
}

final case class AuthServiceConfig(secretKey: String, expirationSeconds: Long, algo: JwtHmacAlgorithm = JwtAlgorithm.HS256)

final case class TokenData(id: EntityId)

object AuthService {
  import application.common.EntityId.implicits.json._
  import io.circe.jawn.decode
  import io.circe.generic.auto._
  import io.circe.syntax._

  def make[F[_] : Sync](config: AuthServiceConfig): AuthService[F] = new AuthService[F] {
    override def getPlayerIdFromToken(token: String): F[Option[EntityId]] =
      Sync[F].delay {
        Jwt.decode(token, config.secretKey, Seq(config.algo)).toOption.flatMap { value =>
          decode[TokenData](value.content).map(_.id).toOption
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
