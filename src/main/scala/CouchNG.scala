package net.rfc1149.canape

import akka.actor.{ActorSystem, ActorRef}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Serialization.write
import spray.can.client.{ClientConnectionSettings, HostConnectorSettings}
import scala.concurrent.duration._
import scala.language.implicitConversions
import spray.can.Http
import spray.can.Http.{HostConnectorSetup, HostConnectorInfo}
import spray.http._
import spray.http.HttpHeaders.{Accept, Authorization, `User-Agent`}
import spray.http.MediaTypes.`application/json`
import spray.httpx.encoding.{Gzip, Deflate}
import scala.concurrent.{Future, ExecutionContext}
import spray.httpx.LiftJsonSupport
import spray.httpx.RequestBuilding._
import spray.httpx.unmarshalling._

/**
 * Connexion to a CouchDB server.
 *
 * @param host the server host name or IP address
 * @param port the server port
 * @param auth an optional (login, password) pair
 */

class CouchNG(val host: String = "localhost",
              val port: Int = 5984,
              private val auth: Option[(String, String)] = None)
             (implicit system: ActorSystem) extends LiftJsonSupport {

  import CouchNG._

  override implicit val liftJsonFormats = DefaultFormats

  private[this] implicit val dispatcher = system.dispatcher
  private[this] implicit val timeout: Timeout = 5.seconds

  private[this] val hostConnector: Future[ActorRef] = {
    // TODO: check gzip handling
    val authHeader = auth map { case (login, password) => Authorization(BasicHttpCredentials(login, password)) }
    val headers = `User-Agent`("canape for Scala") :: Accept(`application/json`) :: authHeader.toList
    val settings = HostConnectorSettings(system).copy(pipelining = false, maxConnections = 1, maxRetries = 0)
    val setup = HostConnectorSetup(host, port, defaultHeaders = headers, settings = Some(settings))
    IO(Http).ask(setup).mapTo[HostConnectorInfo].map(_.hostConnector)
  }

  private[this] def checkResponse[T <: AnyRef : Manifest](response: HttpResponse): T = {
    response.status match {
      case status if status.isFailure => throw new StatusError(status)
      case _                          => response.entity.as[T].right.get
    }
  }

  /**
   * Build a GET HTTP request.
   *
   * @param query The query string, including the already-encoded optional parameters.
   * @param allowChunks True if the handler is ready to handle HTTP chunks, false otherwise.
   * @tparam T The type of the chunks (if allowChunks is true) or of the result.
   * @return A request.
   */
  def makeGetRequest[T <: AnyRef : Manifest](query: String, allowChunks: Boolean = false): Future[T] = {
    // TODO: check chunking
    hostConnector.flatMap(_.ask(Get(query)).mapTo[HttpResponse]).map(checkResponse(_))
  }

  private[this] def buildRequest(builder: RequestBuilder, uri: String, data: AnyRef): HttpRequest =
    data match {
      case EmptyJson => builder(uri, HttpEntity.apply(`application/json`, ""))
      case s: String => builder(uri, FormData(Seq("data" -> s)))     // TODO: check if this is ok to add a name
      case _         => builder(uri, util.toJValue(data))
    }

  private[this] def convert(data: AnyRef): Some[Either[AnyRef, String]] =
    Some(data match {
      case s: String => Right(s)
      case _ => Left(data)
    })

  /**
   * Build a POST HTTP request.
   *
   * The data parameter can be one of the following:
   * <ul>
   *   <li>a String: it will be passed as-is, with type application/x-www-form-urlencoded;</li>
   *   <li>EmptyJson: it will be passed as an empty payload with type application/json;</li>
   *   <li>other: after being converted to Json, it will be passed with type application/json.</li>
   * </ul>
   *
   * @param query the query string, including the already-encoded optional parameters
   * @param data the data to post
   * @tparam T the type of the result
   * @return a request.
   *
   * @throws StatusError if an error occurs
   */
  def makePostRequest[T <: AnyRef : Manifest](query: String, data: AnyRef): Future[T] =
    hostConnector.flatMap(_.ask(buildRequest(Post, query, data)).mapTo[HttpResponse]).map(checkResponse(_))

  /**
   * Build a PUT HTTP request.
   *
   * The data parameter can be one of the following:
   * <ul>
   *   <li>a String: it will be passed as-is, with type application/x-www-form-urlencoded;</li>
   *   <li>EmptyJson: it will be passed as an empty payload with type application/json;</li>
   *   <li>other: after being converted to Json, it will be passed with type application/json.</li>
   * </ul>
   *
   * @param query the query string, including the already-encoded optional parameters
   * @param data the data to post
   * @tparam T the type of the result
   * @return a request.
   *
   * @throws StatusError if an error occurs
   */
  def makePutRequest[T <: AnyRef : Manifest](query: String, data: AnyRef): Future[T] =
    hostConnector.flatMap(_.ask(buildRequest(Put, query, data)).mapTo[HttpResponse]).map(checkResponse(_))

  /**
   * Build a DELETE HTTP request.
   *
   * @param query the query string, including the already-encoded optional parameters
   * @tparam T the type of the result
   * @return a request
   *
   * @throws StatusError if an error occurs
   */
  def makeDeleteRequest[T <: AnyRef : Manifest](query: String): Future[T] =
    hostConnector.flatMap(_.ask(buildRequest(Delete, query, EmptyJson)).mapTo[HttpResponse]).map(checkResponse(_))

  /**URI that refers to the database */
  private[canape] val uri = "http://" + auth.map(x => x._1 + ":" + x._2 + "@").getOrElse("") + host + ":" + port

  protected def canEqual(that: Any) = that.isInstanceOf[Couch]

  override def equals(that: Any) = that match {
    case other: CouchNG if other.canEqual(this) => uri == other.uri
    case _ => false
  }

  override def hashCode() = toString.hashCode()

  override def toString =
    "http://" + auth.map(x => x._1 + ":********@").getOrElse("") + host + ":" + port

  /**
   * Launch a mono-directional replication.
   *
   * @param source the database to replicate from
   * @param target the database to replicate into
   * @param params extra parameters to the request
   * @return a request
   *
   * @throws StatusError if an error occurs
   */
  def replicate[T <% JObject](source: DatabaseNG, target: DatabaseNG, params: T): Future[JObject] = {
    makePostRequest[JObject]("_replicate",
      ("source" -> source.uriFrom(this)) ~
        ("target" -> target.uriFrom(this)) ~
        params)
  }

  /**
   * CouchDB installation status.
   *
   * @return a request
   *
   * @throws StatusError if an error occurs
   */
  def status(): Future[Status] = makeGetRequest[Status]("/")


  /**
   * CouchDB active tasks.
   *
   * @return a request
   *
   * @throws StatusError if an error occurs
   */
  def activeTasks(): Future[List[JObject]] = makeGetRequest[List[JObject]]("/_active_tasks")

  /**
   * Get a named database. This does not attempt to connect to the database or check
   * its existence.
   *
   * @param databaseName the database name
   * @return an object representing this database
   */
  def db(databaseName: String) = DatabaseNG(this, databaseName)

  /**
   * Get the list of existing databases.
   *
   * @return a list of databases on this server
   */
  def databases(): Future[List[String]] = makeGetRequest[List[String]]("/_all_dbs")
}

object CouchNG {

  case object EmptyJson

  class StatusError(val status: spray.http.StatusCode) extends Exception {
    def code: Int = status.intValue
    def reason: String = status.reason
  }

  /**The Couch instance current status. */
  case class Status(couchdb: String,
                    version: String,
                    vendor: Option[VendorInfo])

  case class VendorInfo(name: String,
                        version: String)

}