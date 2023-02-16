package github.nikolaiser.tldr

import zio.{Scope, ZIO, ZIOAppDefault}
import telegramium.bots.high.*
import telegramium.bots.high.implicits.*

object Main extends ZIOAppDefault:
  override def run: ZIO[Any, Any, Any] = TlDrBot.live.build(Scope.global)
