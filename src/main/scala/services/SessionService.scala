/*
 * btc-hotspot
 * Copyright (C) 2016  Andrea Raspitzu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package services

import com.typesafe.scalalogging.slf4j.LazyLogging
import protocol.domain.{Offer, Session}
import commons.AppExecutionContextRegistry.context._
import commons.Helpers.FutureOption
import iptables.IpTablesInterface
import protocol.SessionRepositoryImpl
import protocol.domain.QtyUnit.MB
import registry.{IpTablesServiceRegistry, SchedulerRegistry, SessionRepositoryRegistry}
import watchdog.{SchedulerImpl, StopWatch, TimebasedStopWatch}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object SessionServiceRegistry extends SessionServiceComponent {
  
  val sessionService:SessionServiceInterface = new SessionService()
  
}

trait SessionServiceInterface {
  
  def enableSessionFor(session: Session, offerId:Long):FutureOption[Unit]
  
  def disableSession(session: Session)
  
  def byId(id:Long):FutureOption[Session]
  
  def byMac(mac: String): FutureOption[Session]
  
  def byMacSync(mac: String): Option[Session]
  
  def getOrCreate(mac:String):Future[Long]
  
}

trait SessionServiceComponent {
  
  val sessionService:SessionServiceInterface
  
}


class SessionService protected (dependencies:{
  val sessionRepository: SessionRepositoryImpl
  val offerService:OfferServiceInterface
  val scheduler: SchedulerImpl
  val ipTableFun: IpTablesInterface
}) extends SessionServiceInterface with LazyLogging {
  import dependencies._
  
  def this() = this(new {
    val sessionRepository: SessionRepositoryImpl = SessionRepositoryRegistry.sessionRepositoryImpl
    val offerService:OfferServiceInterface = OfferServiceRegistry.offerService
    val scheduler: SchedulerImpl = SchedulerRegistry.schedulerImpl
    val ipTableFun: IpTablesInterface = IpTablesServiceRegistry.ipTablesServiceImpl
  })
  
  def selectStopwatchForSession(session: Session, offer: Offer):StopWatch = {
    offer.qtyUnit match {
      case MB => ???
      case millis => new TimebasedStopWatch(dependencies, session, offer)
    }
  }
  
  
  def enableSessionFor(session: Session, offerId:Long):FutureOption[Unit] = {
    
    for {
      offer <- offerService.offerById(offerId)
      upsertedId <- sessionRepository.upsert(session.copy(offerId = Some(offerId)))
      updatedSession <- sessionRepository.bySessionId(upsertedId)
    } yield {
      logger.info(s"Enabling session ${updatedSession.id} for offer $offerId")
      val stopWatch = selectStopwatchForSession(updatedSession, offer)
      stopWatch.start()
    }
    
  }
  
  def disableSession(session: Session) = {
    session.stop
  }

  def byId(id:Long):FutureOption[Session] = sessionRepository.bySessionId(id)
  
  def byMac(mac: String): FutureOption[Session] = sessionRepository.byMacAddress(mac)

  //TODO fucking remove!
  def byMacSync(mac: String): Option[Session] = {
    Await.result(byMac(mac).future, 10 seconds)
  }

  /*
    Returns the id of the existing session for this mac, create a new one if
    no session can be found, ids are created by the database
   */
  def getOrCreate(mac:String):Future[Long] = {
    byMac(mac).future flatMap {
      case Some(session) =>
        Future.successful(session.id)
      case None => sessionRepository.insert(Session(clientMac = mac)) map { sessionId =>
          logger.info(s"Created session $sessionId for $mac")
          sessionId
        }
    }
  }


}
