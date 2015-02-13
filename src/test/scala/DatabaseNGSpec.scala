import net.liftweb.json._
import net.rfc1149.canape.CouchNG.StatusError
import net.rfc1149.canape._

import scala.concurrent.Future

class DatabaseNGSpec extends DbNGSpecification("databasetest") {

  import implicits._

  private def insertedId(f: Future[JValue]): Future[String] = f map { js => (js \ "id").extract[String] }

  private def insertedRev(f: Future[JValue]): Future[String] = f map { js => (js \ "rev").extract[String] }

  private def inserted(f: Future[JValue]): Future[(String, String)] =
    for (id <- insertedId(f); rev <- insertedRev(f)) yield (id, rev)

  "db.insert()" should {

    "be able to insert a new document with an explicit id" in new freshDb {
      waitForResult(insertedId(db.insert(JObject(Nil), "docid"))) must be equalTo "docid"
    }

    "be able to insert a new document with an explicit id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(JObject(Nil), "docid", true))) must be equalTo "docid"
    }

    "be able to insert a new document with an implicit id" in new freshDb {
      waitForResult(insertedId(db.insert(JObject(Nil)))) must be matching "[0-9a-f]{32}"
    }

    "be able to insert a new document with an implicit id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(JObject(Nil), batch = true))) must be matching "[0-9a-f]{32}"
    }

    "be able to insert a new document with an embedded id" in new freshDb {
      waitForResult(insertedId(db.insert(Map("_id" -> "docid")))) must be equalTo "docid"
    }

    "be able to insert a new document with an embedded id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(Map("_id" -> "docid"), batch = true))) must be equalTo "docid"
    }

    "be able to update a document with an embedded id" in new freshDb {
      waitForResult(insertedId(db.insert(Map("_id" -> "docid")))) must be equalTo "docid"
      val updatedDoc = waitForResult(db("docid")) + ("foo" -> "bar")
      waitForResult(insertedId(db.insert(updatedDoc))) must be equalTo "docid"
    }

    "be able to update a document with an embedded id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(Map("_id" -> "docid")))) must be equalTo "docid"
      val updatedDoc = waitForResult(db("docid")) + ("foo" -> "bar")
      waitForResult(insertedId(db.insert(updatedDoc, batch = true))) must be equalTo "docid"
    }

    "return a consistent rev" in new freshDb {
      waitForResult(insertedRev(db.insert(JObject(Nil), "docid"))) must be matching "1-[0-9a-f]{32}"
    }

  }

  "db.apply()" should {

    "be able to retrieve the content of a document" in new freshDb {
      val id = waitForResult(insertedId(db.insert(Map("key" -> "value"))))
      val doc = waitForResult(db(id))
      doc("key").extract[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with two params" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Map("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> "newValue")))
      waitForResult(db(id, rev))("key").extract[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with a params map" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Map("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> "newValue")))
      (waitForResult(db(id, Map("rev" -> rev))) \ "key").extract[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with a params sequence" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Map("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> "newValue")))
      (waitForResult(db(id, Seq("rev" -> rev))) \ "key").extract[String] must be equalTo "value"
    }

  }

  "db.delete()" should {

    "be able to delete a document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(JObject(Nil))))
      waitForResult(db.delete(id, rev))
      waitForResult(db(id)) must throwA[StatusError]
    }

    "fail when trying to delete a non-existing document" in new freshDb {
      waitForResult(db.delete("foo", "bar")) must throwA[StatusError]
    }

    "fail when trying to delete a deleted document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(JObject(Nil))))
      waitForResult(db.delete(id, rev))
      waitForResult(db.delete(id, rev)) must throwA[StatusError]
    }

    "fail when trying to delete an older revision of a document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Map("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> "newValue")))
      waitForResult(db.delete(id, rev)) must throwA[StatusError]
    }
  }

  "db.bulkDocs" should {

    "be able to insert a single document" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid"))))(0) \ "id").extract[String] must be equalTo "docid"
    }

    "fail to insert a duplicate document" in new freshDb {
      waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid"))))
      (waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid", "extra" -> "other"))))(0) \ "error").extract[String] must be equalTo "conflict"
    }

    "fail to insert a duplicate document at once" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid"),
        Map("_id" -> "docid", "extra" -> "other"))))(1) \ "error").extract[String] must be equalTo "conflict"
    }

    "accept to insert a duplicate document in batch mode" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid"),
        Map("_id" -> "docid", "extra" -> "other")),
        true))(1) \ "id").extract[String] must be equalTo "docid"
    }

    "generate conflicts when inserting duplicate documents in batch mode" in new freshDb {
      waitForResult(db.bulkDocs(Seq(Map("_id" -> "docid"),
        Map("_id" -> "docid", "extra" -> "other"),
        Map("_id" -> "docid", "extra" -> "yetAnother")),
        true))
      (waitForResult(db("docid", Map("conflicts" -> "true"))) \ "_conflicts").children must have size 2
    }

  }

  "db.allDocs" should {

    "return an empty count for an empty database" in new freshDb {
      waitForResult(db.allDocs()).total_rows must be equalTo 0
    }

    "return a correct count when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).total_rows must be equalTo 1
    }

    "return a correct id enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).ids must be equalTo List("docid")
    }

    "return a correct key enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).keys[String] must be equalTo List("docid")
    }

    "return a correct values enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      val JString(rev) = waitForResult(db.allDocs()).values[JValue].head \ "rev"
      rev must be matching "1-[0-9a-f]{32}"
    }

    "be convertible to an items triple" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      val (id: String, key: String, value: JValue) = waitForResult(db.allDocs()).items[String, JValue].head
      (value \ "rev").extract[String] must be matching "1-[0-9a-f]{32}"
    }

    "be convertible to an items quartuple in include_docs mode" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      val (id: String, key: String, value: JValue, doc: JValue) =
        waitForResult(db.allDocs(Map("include_docs" -> "true"))).itemsWithDoc[String, JValue, JValue].head
      ((value \ "rev").extract[String] must be matching "1-[0-9a-f]{32}") &&
        ((value \ "rev") must be equalTo(doc \ "_rev")) &&
        ((doc \ "key").extract[String] must be equalTo "value")
    }

    "not return full docs in default mode" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).docsOption[JValue] must be equalTo List(None)
    }

    "return full docs in include_docs mode" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value")))
      waitForResult(db.allDocs(Map("include_docs" -> "true"))).docs[JValue].head \ "key" must
        be equalTo JString("value")
    }

    "return full docs in include_docs mode and option" in new freshDb {
      waitForResult(db.insert(Map("key" -> "value"), "docid"))
      waitForResult(db.allDocs(Map("include_docs" -> "true"))).docsOption[JValue].head.map(_ \ "key") must
        be equalTo Some(JString("value"))
    }

  }

  "db.compact()" should {
    "return with success" in new freshDb {
      waitForResult(db.compact()) \ "ok" must be equalTo JBool(true)
    }
  }

  "db.ensureFullCommit()" should {
    "return with success" in new freshDb {
      waitForResult(db.ensureFullCommit()) \ "ok" must be equalTo JBool(true)
    }
  }

}