//package server.games
//
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.actor.typed.pubsub.Topic
//import akka.actor.typed.pubsub.Topic.Publish
//import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
//import akka.persistence.typed.PersistenceId
//import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
//import server.games.BlackJack.Points
//import server.players.Player
//
//class BlackJackActor(context: ActorContext[BlackJackCommand]) extends AbstractBehavior[BlackJackCommand](context) {
//  import BlackJackCommands._
//  import BlackJackEvents._
//
//  private val game: BlackJack.Game = ???
//  private val topic: ActorRef[Topic.Command[BlackJackEvent]]
//    = context.spawn(Topic[BlackJackEvent]("test-topic"), "TestTopic")
//
//  override def onMessage(msg: BlackJackCommand): Behavior[BlackJackCommand] =
//    EventSourcedBehavior[BlackJackCommand, BlackJackEvent, BlackJackActorState](
//      persistenceId = PersistenceId.ofUniqueId("test"),
//      emptyState = BlackJackActorState(),
//      commandHandler = commandHandler,
//      eventHandler = eventHandler
//    )
//
//  private def commandHandler: (BlackJackActorState, BlackJackCommand) => Effect[BlackJackEvent, BlackJackActorState]
//    = { (state, command) =>
//      command match {
//        case Subscribe(ref: ActorRef[BlackJackEvent], id: BlackJackId) =>
//          topic ! Topic.Subscribe(ref)
//          Effect.none
//
//        case JoinTable(ref: ActorRef[BlackJackEvent], player: Player, id: BlackJackId, placeId: BlackJackId) =>
//          game.join(player, placeId) match {
//            case Left(e)  => Effect.none.thenRun(_ => ref ! BlackJackErrorEvent(e))
//            case Right(r) =>
//              val event = TableJoined(r.id, r.holder)
//              Effect.persist(event).thenRun(_ => {
//                ref ! event
//                topic ! Publish(event)
//              })
//          }
//
//        case PlaceBet(handId: BlackJackId, amount: Double) => ???
//
////        case AddCard(handId: BlackJackId) =>
//
//
//        case Stand(handId: BlackJackId) => ???
//        case Split(handId: BlackJackId) => ???
//      }
//    }
//
//  private def eventHandler: (BlackJackActorState, BlackJackEvent) => BlackJackActorState = { (state, event) =>
//    state
//  }
//}
//
//case class BlackJackActorState()
//
//trait BlackJackCommand
//object BlackJackCommands {
//  case class Subscribe(ref: ActorRef[BlackJackEvent], id: BlackJackId) extends BlackJackCommand
//  case class GetTable(id: BlackJackId) extends BlackJackCommand
//  case class JoinTable(ref: ActorRef[BlackJackEvent], player: Player, id: BlackJackId, placeId: BlackJackId)
//    extends BlackJackCommand
//  case class PlaceBet(handId: BlackJackId, amount: Double) extends BlackJackCommand
//  case class GetCard(handId: BlackJackId) extends BlackJackCommand
//  case class Stand(handId: BlackJackId) extends BlackJackCommand
//  case class Split(handId: BlackJackId) extends BlackJackCommand
//}
//
//trait BlackJackEvent
//object BlackJackEvents {
//  case class BlackJackErrorEvent(error: BlackJackError) extends BlackJackEvent
//  case class TableJoined(placeId: BlackJackId, player: Player) extends BlackJackEvent
//  case class CardAdded(handId: BlackJackId, card: Card, points: Points) extends BlackJackEvent
//}
//
//
//
