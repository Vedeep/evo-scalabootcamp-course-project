package server.players

import server.common.EntityId

trait Player {
  def id: EntityId
}

trait PlayerService {
  def getPlayerById[F[_]](id: EntityId): F[Either[PlayerError, Player]]
}

trait PlayerError
object PlayerErrors {

}

