// TxBits - An open source Bitcoin and crypto currency exchange
// Copyright (C) 2014-2015  Viktor Stanchev & Kirk Zathey
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package controllers.StatsAPI

import globals._
import play.api.i18n.I18nSupport
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.json.Writes._
import play.api.mvc.{ Action, Controller, WebSocket }
import play.api.libs.iteratee.{ Iteratee, Concurrent }
import play.i18n.MessagesApi
import scala.collection.mutable
import play.api.libs.iteratee.Concurrent.Channel
import org.joda.time.DateTime
import play.api.db.DB
import service.sql.frontend
import java.sql.Timestamp
import models.Match
import akka.actor.Cancellable
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import org.postgresql.util.PSQLException
import anorm._
import scala.Some
import play.api.libs.json.JsObject
import java.util.Date

case class Ticker(first: String, low: String, high: String, last: String, volume: String, base: String, counter: String)
object Ticker {
  implicit val writes = Json.writes[Ticker]
  implicit val format = Json.format[Ticker]
}
case class TickerHistory(date: DateTime, first: BigDecimal, low: BigDecimal, high: BigDecimal, last: BigDecimal, volume: BigDecimal)
object TickerHistory {
  implicit val writes = Json.writes[TickerHistory]
  implicit val format = Json.format[TickerHistory]
}

// DON'T DO AUTHENTICATED ACTIONS OVER WEBSOCKETS UNTIL SOMEONE CAN VERIFY THAT THIS IS A SAFE THING TO DO

class APIv1(val messagesApi: MessagesApi) extends Controller with I18nSupport {

  val channels = mutable.Set[Channel[String]]()
  var lastMatchDatetime: DateTime = new DateTime(0)
  var lastMatchForPair = mutable.Map[String, JsObject]()

  var cancellable: Option[Cancellable] = None
  val DefaultInterval = 1
  val tickerInterval = "txbits.tickerInterval.sec"

  def onStart() {
    val i = current.configuration.getInt(tickerInterval).getOrElse(DefaultInterval)

    //DISABLED FOR NOW UNTIL WE DO WEBSOCKET PUSH TICKER STUFF
    /*
    cancellable = Some(
      Akka.system.scheduler.schedule(i.seconds, i.seconds) {
        checkMatchesAndNotifySockets()
      }
    )*/
  }

  def onStop() {
    cancellable.map(_.cancel())
  }

  def checkMatchesAndNotifySockets(): Unit = DB.withConnection(masterDB) { implicit c =>
    try {
      val matches = frontend.getRecentMatches.on(
        'last_match -> new Timestamp(lastMatchDatetime.getMillis)
      )().map(row =>
          Match(
            row[BigDecimal]("amount"),
            row[BigDecimal]("price"),
            row[DateTime]("created"),
            row[String]("base"),
            row[String]("counter")
          )
        )
      if (!matches.isEmpty) {
        // mathc is match with ch flipped because match is a keyword
        val mathc = matches.head
        val json = Match.toJson(mathc)
        channels.foreach(_ push json.toString())
        lastMatchDatetime = new DateTime(mathc.created)
        lastMatchForPair.put("%s/%s".format(mathc.base, mathc.counter), Json.obj("last" -> mathc.price, "base" -> mathc.base, "counter" -> mathc.counter))
      }
    } catch {
      case e: PSQLException => // Ignore failures (they can be caused by a connection that just closed and hopefully next time we'll get a valid connection
    }
  }

  def tickerFromDb = DB.withConnection(masterDB) { implicit c =>
    globals.metaModel.validPairs.flatMap {
      case (base, counter, active, minAmount) =>
        val res: List[Seq[JsNumber]] = chartFromDB(base, counter)
        if (!res.isEmpty) {
          val ticker = play.api.cache.Cache.getOrElse("%s.%s.ticker".format(base, counter)) {
            Ticker(
              res.head(1).value.toString(),
              res.map { _(3) }.reduce { (num1, num2) => if (num1.value < num2.value) num1 else num2 }.toString(),
              res.map { _(2) }.reduce { (num1, num2) => if (num1.value > num2.value) num1 else num2 }.toString(),
              res.last(4).value.toString(),
              res.map { _(5) }.reduce { (num1, num2) => JsNumber(num1.value + num2.value) }.toString(),
              base,
              counter
            )
          }
          Some(ticker)
        } else {
          None
        }
    }
  }

  def ticker = Action {
    Ok(Json.toJson(tickerFromDb))
  }

  //TODO: display a ticker instead of the last trade
  // It looks like any website can connect as the currently logged in user over a websocket
  def websocketTicker = WebSocket.using[String] { request =>
    val (out, channel) = Concurrent.broadcast[String]

    channels += channel

    //log the message to stdout and send response back to client
    val in = Iteratee.foreach[String] {
      msg =>
        // reply to any request with the full list of currencies
        channels.foreach(_ push Json.toJson(lastMatchForPair.map(m => m._2)).toString())
    }.map { _ =>
      channels -= channel
    }
    (in, out)
  }

  def chartFromDB(base: String, counter: String) = DB.withConnection(masterDB) { implicit c =>
    play.api.cache.Cache.getOrElse("%s.%s.stats".format(base, counter)) {
      frontend.chartFromDb.on(
        'base -> base,
        'counter -> counter
      )().filter(row => row[Option[Date]]("start_of_period").isDefined).map(row => {
          // We want a json array because that's what the graphing api understands
          // Format: Date,Open,High,Low,Close,Volume

          Seq(
            JsNumber(row[Option[Date]]("start_of_period").get.getTime),
            JsNumber(row[Option[BigDecimal]]("open").get),
            JsNumber(row[Option[BigDecimal]]("high").get),
            JsNumber(row[Option[BigDecimal]]("low").get),
            JsNumber(row[Option[BigDecimal]]("close").get),
            JsNumber(row[Option[BigDecimal]]("volume").get)
          )

        }).toList
    }
  }

  def chart(base: String, counter: String) = Action {
    if (globals.metaModel.activeMarkets.contains(base, counter)) {
      Ok(Json.toJson(chartFromDB(base, counter)))
    } else {
      BadRequest(Json.obj("message" -> "Invalid pair."))
    }
  }
}
