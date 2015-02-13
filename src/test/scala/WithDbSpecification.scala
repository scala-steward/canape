import java.util.UUID

import akka.actor.ActorSystem
import net.rfc1149.canape.Couch.StatusError
import net.rfc1149.canape._
import org.specs2.mutable._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

// This requires a local standard CouchDB instance. The "canape-test-*" databases
// will be created, destroyed and worked into. There must be an "admin"/"admin"
// account.

class WithDbSpecification(dbSuffix: String) extends Specification {

  implicit val system = ActorSystem()
  implicit val dispatcher = system.dispatcher
  implicit val timeout: Duration = (5, SECONDS)

  val couch = new Couch(auth = Some("admin", "admin"))

  trait freshDb extends BeforeAfter {

    val db = couch.db("canape-test-" + dbSuffix + "-" + UUID.randomUUID())

    override def before =
      try {
        Await.ready(db.create(), timeout)
      } catch {
        case _: StatusError =>
      }

    override def after =
      try {
        Await.ready(db.delete(), timeout)
      } catch {
        case _: StatusError =>
      }
  }

  def waitForResult[T](f: Future[T]): T = Await.result(f, timeout)

}
