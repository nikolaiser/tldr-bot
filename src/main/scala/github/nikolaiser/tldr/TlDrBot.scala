package github.nikolaiser.tldr

import org.http4s.blaze.client.BlazeClientBuilder
import telegramium.bots.{ChatIntId, Message}
import telegramium.bots.high.*
import telegramium.bots.high.implicits.*
import zio.*
import zio.concurrent.ConcurrentMap
import zio.interop.catz.*
import zio.interop.*
import java.time.Instant

import scala.annotation.tailrec

object TlDrBot:
  private val ttlSeconds = 60 * 60 * 24

  private case class Key(chatId: Long, messageId: Int)
  private sealed trait Data:
    def timestamp: Instant
  private object Data:
    case class Root(timestamp: Instant, repliesCount: Int) extends Data
    case class Reply(timestamp: Instant, replyTo: Option[Int]) extends Data

  private case class TlDrBotLive(
      cache: ConcurrentMap[Key, Data],
      limit: Int
  )(api: Api[Task])
      extends LongPollBot[Task](api):
    given Api[Task] = api

    override def onMessage(msg: Message): Task[Unit] =
      ZIO.attempt(println(msg.chat.id)) *> (
      if msg.text.exists(_.startsWith("/tldr")) then
        getTop(msg.chat.id)
          .map(toResultMessage(_, msg))
          .flatMap(text =>
            Methods
              .sendMessage(chatId = ChatIntId(msg.chat.id), text = text)
              .exec
              .unit
          )
      else saveMessage(msg).unit)

    private def getTop(chatId: Long): Task[List[Int]] = cache.toList.map(
      _.filter(_._1.chatId == chatId)
        .collect[(Key, Data.Root)] { case (key, x: Data.Root) =>
          (key, x)
        }
        .sortWith { case (e1, e2) =>
          e1._2.repliesCount > e2._2.repliesCount
        }
        .take(limit)
        .map(_._1.messageId)
    )

    private def findRoot(chatId: Long, messageId: Int): UIO[Option[Int]] =
      cache.get(Key(chatId, messageId)).flatMap {
        case None                               => ZIO.none
        case Some(Data.Root(_, _))              => ZIO.some(messageId)
        case Some(Data.Reply(_, None))          => ZIO.none
        case Some(Data.Reply(_, Some(replyTo))) => findRoot(chatId, replyTo)
      }

    private def saveMessage(msg: Message) =
      for {
        now <- ZIO.clockWith(_.instant)
        _ <- msg.replyToMessage.fold(
          cache.put(Key(msg.chat.id, msg.messageId), Data.Root(now, 0))
        )(initial =>
          cache.put(
            Key(msg.chat.id, msg.messageId),
            Data.Reply(now, Some(initial.messageId))
          ) *> findRoot(initial.chat.id, msg.messageId).flatMap(
            _.fold(ZIO.unit)(root =>
              cache.compute(
                Key(initial.chat.id, root),
                (_, oldValue) =>
                  oldValue.map {
                    case Data.Root(i, c) => Data.Root(i, c + 1)
                    case x               => x
                  }
              )
            )
          )
        )
      } yield ()

    private def toResultMessage(messages: List[Int], root: Message): String =
      messages.zipWithIndex
        .map { (id, index) =>
          s"${index + 1}. https://t.me/c/${root.chat.id.abs.toString.takeRight(10)}/$id"
        }
        .mkString("\n")

  private def invalidateEntries(map: ConcurrentMap[Key, Data], ttl: Int) =
    for {
      now <- ZIO.clockWith(_.instant)
      _ <- map.removeIf((_, data) =>
        Duration.fromInterval(data.timestamp, now).getSeconds >= ttl
      )
    } yield ()

  val live: ZLayer[Any, Throwable, Unit] =
    ZLayer.scoped(for {
      map <- ConcurrentMap.empty[Key, Data]
      token <- ZIO
        .systemWith(_.env("TLDR_BOT_TOKEN"))
        .flatMap(x =>
          ZIO.fromOption(x).mapError(_ => new Exception("No token"))
        )
      client <- BlazeClientBuilder[Task].resource.toScopedZIO
      api = BotApi[Task](
        client,
        baseUrl = s"https://api.telegram.org/bot$token"
      )
      bot = TlDrBotLive(map, 5)(api)
      _ <- invalidateEntries(map, ttlSeconds).scheduleFork(
        zio.Schedule.spaced(1.second)
      )
      _ <- bot.start()
    } yield ())
