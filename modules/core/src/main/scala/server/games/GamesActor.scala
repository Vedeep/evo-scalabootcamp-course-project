package server.games

//import akka.actor.typed.Behavior
//import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import server.common.EntityId

trait GamesCommand
object GamesCommands {
  case class GetTableList() extends GamesCommand
  case class AddTable() extends GamesCommand
  case class GetTable(id: EntityId) extends GamesCommand
  case class SubscribeTable(id: EntityId) extends GamesCommand
}
//
//class GamesActor(context: ActorContext[GamesCommand]) extends AbstractBehavior[GamesCommand](context) {
//  override def onMessage(msg: GamesCommand): Behavior[GamesCommand] = ???
//}


