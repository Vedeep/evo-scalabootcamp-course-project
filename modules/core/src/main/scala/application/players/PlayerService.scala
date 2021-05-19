package application.players

import cats.Monad
import cats.data.EitherT
import application.common.EntityId
import application.common.Errors.AppError
import application.database.{CurrencyCode, PlayerFirstName, PlayerLastName, PlayerNickName, PlayerRepository, WalletBalance}

final case class PlayerWallet
(
  id: EntityId,
  balance: WalletBalance
)

final case class PlayerWalletCurrency
(
  id: EntityId,
  code: CurrencyCode,
  exchangeRate: Double
)

final case class Player
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

