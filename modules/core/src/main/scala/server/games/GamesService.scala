package server.games

import server.games.BlackJack.Points

object GamesService {

  trait GamesService {
    def createBlackJack: BlackJack.Game
  }

//  class GamesServiceImpl extends GamesService {
//    override def createBlackJack(
//      config: BlackJack.GameConfig,
//    ): BlackJack.Game = {
//      val calculatorConfig = new BlackJack.CalculatorConfig {
//        override val maxPoints: Points = 21
//      }
//
//      new BlackJack.Game(config, BlackJack.Calculator(calculatorConfig))
//    }
//  }
}
