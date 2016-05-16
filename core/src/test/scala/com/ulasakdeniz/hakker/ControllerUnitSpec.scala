package com.ulasakdeniz.hakker

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import com.ulasakdeniz.hakker.base.UnitSpec
import com.ulasakdeniz.hakker.template.Render

class ControllerUnitSpec extends UnitSpec with ScalatestRouteTest {

  "route" should {
    "render html files for GET request to appropriate paths" in new RoutesUnitSpecFixture {
      val html = "Hello World"
      doReturn(complete(HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/html(UTF-8)`, ByteString(html)))))
        .when(controllerSpy).render("index")

      Get("/") ~> controllerSpy.apply() ~> check {
        handled shouldBe true
        status shouldEqual StatusCodes.OK
        inside(responseEntity) {
          case HttpEntity.Strict(contentType, data) => {
            data.utf8String shouldEqual html
            contentType shouldEqual ContentTypes.`text/html(UTF-8)`
          }
        }
      }
    }

    "leave GET requests to other paths unhandled" in new RoutesUnitSpecFixture {
      Get("/anUnhandledPath") ~> routes ~> check {
        handled shouldBe false
      }
    }

    "return a MethodNotAllowed error for PUT requests to the root path" in
      new RoutesUnitSpecFixture {
        Put() ~> Route.seal(routes) ~> check {
        status === StatusCodes.MethodNotAllowed
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: GET"
      }
    }

    "handle the routes in the route function" in new RoutesUnitSpecFixture {
      Get("/aPath") ~> routes ~> check {
        handled shouldBe true
        responseEntity.contentType shouldEqual ContentTypes.`text/plain(UTF-8)`
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "jsonResponse" should {

    "return a json response with the status code" in new RoutesUnitSpecFixture {
      Get("/user") ~> routes ~> check {
        val jsonResult =
          """{
            |  "name": "Dilek",
            |  "notes": ["do this", "than that", "after that", "end"]
            |}""".stripMargin

        status shouldEqual StatusCodes.OK
        inside(responseEntity) {
          case HttpEntity.Strict(contentType, data) => {
            data.utf8String shouldEqual jsonResult
            contentType shouldEqual ContentTypes.`application/json`
          }
        }
      }
    }
  }

  trait RoutesUnitSpecFixture {

    object ControllerTest extends Controller {

      override def route: Route = {
        get {
          path("aPath") {
            complete("a lucky, handled path")
          } ~
          pathSingleSlash {
            render("index")
          } ~
            path("user") {
              implicit val userFormat = jsonFormat2(User)
              val dilek = User("Dilek", List("do this", "than that", "after that", "end"))
              jsonResponse(StatusCodes.OK, dilek)
            }
        }
      }
    }

    val controllerSpy = spy(ControllerTest)

    lazy val routes = ControllerTest.apply()

    case class User(name: String, notes: List[String])
  }
}
