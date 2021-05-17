package application.common

object Errors {

  def getErrorMessage(error: AppError): String = error match {
    case UserErrors.UserIsNotFound => "User is not found"

    case WSErrors.CommandIsNotFound => "Command is not found"
    case WSErrors.WrongIncomingParams => "Wrong incoming params"

    case GameErrors.GameIsNotStarted => "Game is not started"
    case GameErrors.GameIsNotFound => "Game is not found"

    case BlackJackErrors.TableIsFull => "Table is full"
    case BlackJackErrors.TableIsEmpty => "Table is empty"
    case BlackJackErrors.PlayerSeatsExceed => "Player seats exceed"
    case BlackJackErrors.HandIsNotFound => "Hand is not found"
    case BlackJackErrors.RoundAlreadyStarted => "Round already started"
    case BlackJackErrors.RoundNotStarted => "Round not started"
    case BlackJackErrors.SeatIsNotFound => "Seat is not found"
    case BlackJackErrors.SeatIsBusy => "Seat is busy"
    case BlackJackErrors.NotPlayerSeat => "No player seat"
    case BlackJackErrors.ActionIsNotAllowed => "Action is not allowed"

    case DBErrors.DuplicateValues => "Values is duplicate"
    case DBErrors.CreationError => "Creation error"
    case DBErrors.PlayerOrCurrencyNotFound => "Player or currency is not found"

    case WalletErrors.WalletIsNotFound => "Wallet is not found"
    case WalletErrors.WalletBalanceIsLessThanNeed => "Wallet balance is less than amount"
  }

  sealed trait AppError
  sealed trait UserError extends AppError
  sealed trait WSError extends AppError
  sealed trait GameError extends AppError
  sealed trait BlackJackError extends GameError
  sealed trait DBError extends AppError
  sealed trait WalletError extends AppError

  object UserErrors {
    case object UserIsNotFound extends UserError
  }

  object WSErrors {
    case object WrongIncomingParams extends WSError
    case object CommandIsNotFound extends WSError
  }

  object GameErrors {
    case object GameIsNotFound extends GameError
    case object GameIsNotStarted extends GameError
  }

  object BlackJackErrors {
    case object TableIsFull extends BlackJackError
    case object TableIsEmpty extends BlackJackError
    case object PlayerSeatsExceed extends BlackJackError
    case object HandIsNotFound extends BlackJackError
    case object RoundAlreadyStarted extends BlackJackError
    case object RoundNotStarted extends BlackJackError
    case object SeatIsNotFound extends BlackJackError
    case object SeatIsBusy extends BlackJackError
    case object NotPlayerSeat extends BlackJackError
    case object ActionIsNotAllowed extends BlackJackError
  }

  object DBErrors {
    case object DuplicateValues extends DBError
    case object CreationError extends DBError
    case object PlayerOrCurrencyNotFound extends DBError
  }

  object WalletErrors {
    case object WalletIsNotFound extends WalletError
    case object WalletBalanceIsLessThanNeed extends WalletError
  }
}
