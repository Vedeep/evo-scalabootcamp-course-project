package server.common

import java.util.UUID
import scala.util.Try

case class EntityId(id: UUID)

object EntityId {
  def fromString(s: String): Option[EntityId] =
    Try(UUID.fromString(s)).toOption.map(EntityId(_))
}
