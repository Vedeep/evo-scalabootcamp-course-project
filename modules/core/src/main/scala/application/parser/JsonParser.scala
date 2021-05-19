package application.parser

import io.circe.{Encoder, Json}
import application.common.Finance.{Amount, Currency}
import application.games.BlackJack.{BlackJackGameInfo, BlackJackGameState, BlackJackGameStateRound}
import application.games.Games.GameStatuses.{Ended, New, Started}
import application.games.Games.{GameInfo, GameState, GameStatus}
import application.games.{BlackJackJoinResult, BlackJackLeaveResult, JoinGameResult, LeaveGameResult}
import application.players.Player

object JsonParser {
  import application.games.Games.GameTypes.implicits._
  import application.games.Cards.implicits._
  import application.common.EntityId.implicits.json._
  import application.games.Games.GameStatuses.implicits._
  import application.games.Cards.implicits._

  import io.circe.jawn.decode
  import io.circe.generic.auto._
  import io.circe.syntax._

  object encoders {
    val blackJackGameStateEncoder: Encoder[BlackJackGameState] =
      Encoder.forProduct4("status", "seats", "currentRound", "nextRoundTime") { s =>
        (
          s.status.asJson,
          s.seats.foldLeft[List[Json]](Nil)((acc, item) => {
            item match {
              case (seatId, player) => acc :+ Json.obj(
                ("id", seatId.asJson),
                ("holder", Json.obj(
                  ("id", player.map(_.id).asJson),
                  ("nickName", player.map(_.nickName.value).asJson)
                ))
              )
            }
          }).asJson,
          s.currentRound.map(_.asJson(blackJackGameStateRoundEncoder)),
          s.nextRoundTime.map(_.toEpochMilli).asJson
        )
      }

    val blackJackGameStateRoundEncoder: Encoder[BlackJackGameStateRound] = {
      Encoder.forProduct5("dealerCards", "dealerPoints", "hands", "nextStateTime", "state")
      { round =>
        (
          round.dealerCards,
          round.results.find(_.hand.isEmpty).map(_.points),
          round.hands.map[Json] { hand =>
            Json.obj(
              ("id", hand.id.asJson),
              ("seatId", hand.seatId.asJson),
              ("holder", Json.obj(
                ("id", hand.holder.id.asJson),
                ("nick", hand.holder.nickName.value.asJson)
              )),
              ("main", hand.main.asJson),
              (
                "cards",
                round.handCards.getOrElse(hand, Nil).asJson
              ),
              (
                "stake",
                round.stakes.getOrElse[Amount](
                  hand,
                  Amount(
                    0
                  )
                ).value.asJson
              ),
              (
                "result",
                round.results.find(_.hand.contains(hand)).map { res =>
                  Json.obj(
                    ("points", res.points.asJson),
                    ("result", res.result.map(_.toString).asJson)
                  )
                }.asJson
              ),
              (
                "actions",
                round.handActions.get(hand).map(_.map(_.toString)).asJson
              )
            )
          },
          round.nextStateTime.toEpochMilli,
          round.state.toString
        )
      }
    }

    val playerInfoEncoder: Encoder[Player] = Encoder.forProduct6(
      "id",
      "firstName",
      "lastName",
      "nickName",
      "balance",
      "currency"
    ) { player =>
      (
        player.id.value,
        player.firstName.value,
        player.lastName.value,
        player.nickName.value,
        player.wallet.balance.value,
        player.currency.code.value
      )
    }
  }

  import encoders._

  def encodeGameInfo(info: GameInfo): Json = info match {
    case gameInfo: BlackJackGameInfo => gameInfo.asJson
  }

  def encodeGameState(state: GameState): Json = state match {
    case gameState: BlackJackGameState => gameState.asJson(blackJackGameStateEncoder)
  }

  def encodePlayerInfo(player: Player): Json = player.asJson(playerInfoEncoder)

  def encodeJoinGameResult(result: JoinGameResult): Json = result match {
    case _: BlackJackJoinResult => result.asJson
  }

  def encodeLeaveGameResult(result: LeaveGameResult): Json = result match {
    case _: BlackJackLeaveResult => result.asJson
  }
}
