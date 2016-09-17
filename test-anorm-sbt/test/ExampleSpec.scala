import anorm._
import org.scalatest._
import org.scalatestplus.play._
import play.api.db._
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.util.{Failure, Success, Try}

case class Example(
  data: JsValue
)

class ExampleSpec extends PlaySpec with OneAppPerSuite {

  import scala.concurrent.ExecutionContext.Implicits.global

  "example" in {
    DB.withConnection { implicit c =>
      SQL("drop table if exists pgbugkeystore").execute()
      SQL("create table pgbugkeystore(data json)").execute()
      SQL("insert into pgbugkeystore (data) values ({data}::json)").on(
        'data -> "{}"
      ).executeUpdate()

      SQL("select data from pgbugkeystore").as(
        SqlParser.get[JsObject]("data").*
      )
    }
  }

  implicit val columnToJsObject: Column[play.api.libs.json.JsObject] = Util.parser { _.as[play.api.libs.json.JsObject] }

  /**
    * Conversions to collections of objects using JSON.
    */
  object Util {

    def parseJson[T](f: play.api.libs.json.JsValue => T, columnName: String, value: String) = {
      Try {
        f(
          play.api.libs.json.Json.parse(value)
        )
      } match {
        case Success(result) => Right(result)
        case Failure(ex) => Left(
          TypeDoesNotMatch(
            s"Column[$columnName] error parsing json $value: $ex"
          )
        )
      }
    }

    def parser[T](
      f: play.api.libs.json.JsValue => T
    ) = anorm.Column.nonNull { (value, meta) =>
      val MetaDataItem(columnName, nullable, clazz) = meta
      value match {
        case json: org.postgresql.util.PGobject => parseJson(f, columnName.qualified, json.getValue)
        case _=> {
          Left(
            TypeDoesNotMatch(
              s"Column[${columnName.qualified}] error converting $value to Json. Expected class to be[org.postgresql.util.PGobject] and not[${value.asInstanceOf[AnyRef].getClass}"
            )
          )
        }


      }
    }

  }
  
}
