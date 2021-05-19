package application.common

import cats.effect.Sync
import cats.implicits._
import doobie.Meta
import io.circe.{Decoder, Encoder, Json}

import java.util.UUID
import scala.util.Try

case class EntityId(value: UUID) extends AnyVal

object EntityId {
//  def apply(): EntityId = EntityId(UUID.randomUUID())

  def fromString(s: String): Option[EntityId] =
    Try(UUID.fromString(s)).toOption.map(EntityId(_))

  def of[F[_] : Sync]: F[EntityId] =
    Sync[F].delay(EntityId(UUID.randomUUID()))

  object implicits {
    object json {
      implicit val entityIdEncoder: Encoder[EntityId] = (eid: EntityId) => Json.fromString(eid.value.toString)
      implicit val entityIdDecoder: Decoder[EntityId] = Decoder.decodeString.emap { str =>
        Either.catchNonFatal(UUID.fromString(str)).map(EntityId(_)).leftMap(_ => "Not parsable")
      }
    }

    object db {
      implicit val entityIdMeta: Meta[EntityId] = Meta[String].timap { v =>
        EntityId(UUID.fromString(v))
      }(_.value.toString)
    }
  }
}
