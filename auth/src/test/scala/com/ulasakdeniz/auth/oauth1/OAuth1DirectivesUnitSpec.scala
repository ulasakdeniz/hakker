package com.ulasakdeniz.auth.oauth1

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.MissingQueryParamRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.ActorMaterializer
import com.ulasakdeniz.base.UnitSpec

import scala.concurrent.Future

class OAuth1DirectivesUnitSpec extends UnitSpec with ScalatestRouteTest {
  private val _system: ActorSystem = ActorSystem("oauth1-directive-test")
  private val _mat: ActorMaterializer = ActorMaterializer()

  override protected def afterAll(): Unit = {
    super.afterAll()
    _system.terminate()
  }

  "authenticate" should {
    "call OAuth1.requestToken and send result to authDirective" in new OAuthDirectivesFixture {
      val httpResponse  = HttpResponse(entity = "test passed")
      val oauthResponse = RedirectionSuccess(httpResponse, Map.empty)
      val future: Future[RedirectionSuccess] = Future.successful(oauthResponse)

      doReturn(future).when(spiedOAuth1).requestToken
      Get("/authenticate") ~> {
        spiedOAuthDirective.oauth {
          case RedirectionSuccess(hr, _) =>
            complete(hr)
          case _ => fail()
        }
      } ~> check {
        response shouldEqual httpResponse
      }
      verify(spiedOAuth1, times(1)).requestToken
    }
  }

  "oauthCallbackAsync" should {
    "extract oauth callback query parameters and use them for accessToken" in new OAuthDirectivesFixture {
      val tokenValue = "hey"
      val verifierValue = "ho"
      val tokenMap= Map(OAuth1Contract.token -> tokenValue)
      val tokenMapWithVerifier: Map[String, String] = tokenMap + (OAuth1Contract.verifier -> verifierValue)
      val tokenFun: String => Future[Tokens] = _ => Future.successful(tokenMap)
      val oauthResponse = AccessTokenSuccess(tokenMap)

      // OAuth1.accessToken method is called with a map included OAuth1Contract.verifier
      doReturn(Future.successful(oauthResponse)).when(spiedOAuth1).accessToken(tokenMapWithVerifier)
      Get(s"/callback?${OAuth1Contract.token}=$tokenValue&${OAuth1Contract.verifier}=$verifierValue") ~> {
        spiedOAuthDirective.oauthCallbackAsync(tokenFun) {
          case AccessTokenSuccess(tokens) =>
            tokens shouldEqual tokenMap
            complete("callback handled")
          case _ => fail()
        }
      } ~> check {
        responseAs[String] shouldEqual "callback handled"
      }
    }

    "reject if query parameters are not oauth_token and oauth_verifier" in new OAuthDirectivesFixture {
      val tokenMap = Map("a" -> "b")
      val tokenFun: String => Future[Tokens] = _ => Future.successful(tokenMap)
      val oauthResponse = AccessTokenSuccess(tokenMap)
      Get("/callback?oauth_token=hey") ~> {
        DirectiveTest.oauthCallbackAsync(tokenFun) { _=>
          complete("couldn't complete")
        }
      } ~> check {
        rejection shouldEqual MissingQueryParamRejection("oauth_verifier")
      }
    }

    "give AuthenticationFailed with http response provided if function returns invalid tokens" in new OAuthDirectivesFixture {
      val tokenValue = "hey"
      val verifierValue = "ho"
      val tokenMap= Map(OAuth1Contract.token -> tokenValue)
      val tokenMapWithVerifier: Map[String, String] = tokenMap + (OAuth1Contract.verifier -> verifierValue)
      val tokenFun: String => Future[Tokens] = _ => Future.successful(tokenMap)
      val oauthResponse = AccessTokenSuccess(tokenMap)

      val response = HttpResponse(StatusCodes.BadRequest)
      doReturn(Future.successful(AccessTokenFailed(response))).when(spiedOAuth1).accessToken(tokenMapWithVerifier)

      Get(s"/callback?${OAuth1Contract.token}=$tokenValue&${OAuth1Contract.verifier}=$verifierValue") ~> {
        DirectiveTest.oauthCallbackAsync(tokenFun) {
          case AccessTokenFailed(hr) =>
            complete(hr)
          case _ => fail()
        }
      } ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "give InternalServerError if function returns a failed Future" in new OAuthDirectivesFixture {
      val tokenFun: String => Future[Tokens] = _ => Future.failed(new Exception)
      Get("/callback?oauth_token=hey&oauth_verifier=ho") ~> {
        DirectiveTest.oauthCallbackAsync(tokenFun) {
          _ => complete("couldn't complete")
        }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
  }

  trait OAuthDirectivesFixture {
    val oauthParams = OAuthParams("key", "secret", "uri", "uri", "uri")
    val context = OAuthContext(_system, _mat, oauthParams)
    object TestOAuthClient extends OAuthClient(context)
    val spiedOAuth1: OAuthClient = spy(TestOAuthClient)

    object DirectiveTest extends OAuth1Directives {
      override val oauthContext: OAuthContext = context
      override private[oauth1] lazy val oauthClient = spiedOAuth1
    }

    val spiedOAuthDirective: OAuth1Directives = spy(DirectiveTest)
  }
}
