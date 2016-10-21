package resources

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.http.scaladsl.unmarshalling._
import akka.util.{ByteString, Timeout}
import org.bitcoin.protocols.payments.Protos
import org.bitcoin.protocols.payments.Protos._
import org.bitcoinj.protocols.payments.PaymentProtocol
import wallet.WalletSupervisorService
import wallet.WalletSupervisorService.{PAYMENT, PAYMENT_REQUEST, GET_RECEIVING_ADDRESS}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import akka.pattern.ask
import ExtraHttpHeaders._
import commons.Helpers._

/**
  * Created by andrea on 15/09/16.
  */
trait WelcomeController extends CommonResource {

  implicit val timeout = Timeout(10 seconds)

  lazy val walletServiceActor = actorRefFor[WalletSupervisorService]

  def headerLogger:LoggingMagnet[HttpRequest ⇒ Unit] = LoggingMagnet { loggingAdapter => request =>
     loggingAdapter.info(s"Headers: ${request._3.toString()}")
     loggingAdapter.info(s"HTTP Method: ${request._1}")
  }


  implicit val paymentUnmarshaller:FromRequestUnmarshaller[Protos.Payment] = Unmarshaller { ec => httpRequest =>
    httpRequest._4.dataBytes.runFold(ByteString(Array.emptyByteArray))(_ ++ _) map { byteString =>
      Protos.Payment.parseFrom(byteString.toArray[Byte])
    }
  }


  def welcomeRoute: Route = {
    logRequest(headerLogger){
     path("pay" / Segment) { sessionId:String =>
      get {
        complete {
           (walletServiceActor ? PAYMENT_REQUEST(sessionId)).map { case req: PaymentRequest =>
             HttpEntity(req.toByteArray).withContentType(paymentRequestContentType)
           }
        }
      } ~ post {
        entity(as[Protos.Payment]){ payment =>
          complete {
            //Send the payment to the wallet actor and wait for its response
            HttpEntity(
              PaymentProtocol.createPaymentAck(payment, s"Enjoy session $sessionId").toByteArray
            ).withContentType(paymentAckContentType)

          }
        }
      }
     }
    }

  }

}
