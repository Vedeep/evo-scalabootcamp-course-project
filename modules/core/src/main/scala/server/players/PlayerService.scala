package server.players

import cats.Monad
import cats.data.EitherT
import server.common.EntityId
import server.common.Errors.AppError
import server.database.{CurrencyCode, PlayerFirstName, PlayerLastName, PlayerNickName, PlayerRepository, WalletBalance}

case class PlayerWallet
(
  id: EntityId,
  balance: WalletBalance
)

case class PlayerWalletCurrency
(
  id: EntityId,
  code: CurrencyCode,
  exchangeRate: Double
)

case class Player
(
  id: EntityId,
  firstName: PlayerFirstName,
  lastName: PlayerLastName,
  nickName: PlayerNickName,
  wallet: PlayerWallet,
  currency: PlayerWalletCurrency
)

trait PlayerService[F[+_]] {
  def getPlayerById(id: EntityId): F[Either[AppError, Player]]
}

object PlayerService {
  def make[F[+_] : Monad](playerRepo: PlayerRepository[F]): PlayerService[F] = new PlayerService[F] {
    override def getPlayerById(id: EntityId): F[Either[AppError, Player]] =
      (for {
        data <- EitherT(playerRepo.getById(id))
        (playerModel, walletModel, currencyModel) = data
      } yield Player(
        playerModel.id,
        playerModel.firstName,
        playerModel.lastName,
        playerModel.nickName,
        PlayerWallet(
          walletModel.id,
          walletModel.balance,
        ),
        PlayerWalletCurrency(
          currencyModel.id,
          currencyModel.code,
          currencyModel.exchangeRate
        )
      )).value
  }
}

trait PlayerError
object PlayerErrors {

}

