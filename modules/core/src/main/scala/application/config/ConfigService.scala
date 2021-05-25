package application.config

import pureconfig._
import pureconfig.generic.auto._

object ConfigService {
  def getConfig: AppConfig =
    ConfigSource
      .default
      .load[AppConfig]
      .getOrElse(throw new Exception("Configuration error"))
}

case class AppConfig
(
  server: ServerConfig,
  db: DatabaseConfig,
  auth: AuthConfig
)

case class ServerConfig(
  host: String,
  port: Int,
)

case class DatabaseConfig(
  driver: String,
  url: String,
  user: String,
  password: String,
  poolSize: Int,
)

case class AuthConfig(
  secretKey: String,
  expirationSeconds: Int,
)
