package app.controllers

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, GenericHttpCredentials}
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server._
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.ByteString
import app.models.TwitterUser
import app.services.Redis
import com.typesafe.scalalogging.StrictLogging
import com.ulasakdeniz.hakker.Controller
import com.ulasakdeniz.hakker.auth.{AuthenticationHeader, OAuth1, OAuth1Helper, OAuthResponse}
import com.ulasakdeniz.hakker.websocket.WebSocketHandler

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Application extends Controller with StrictLogging {

  lazy val webSocketHandler = new WebSocketHandler

  // throws exception if these keys are missing
  val twitterConsumerSecret = config.getString("TwitterConsumerSecret")
  val twitterConsumerKey = config.getString("TwitterConsumerKey")
  val accessTokenUri = config.getString("TwitterAccessTokenUri")
  val verificationUri = config.getString("TwitterUserDataUri")

  val oAuth1 = new OAuth1(twitterConsumerSecret)(http, mat)

  def route: Route = {
    get {
      pathSingleSlash {
        render("index")
      } ~
        path("socket") {
          handleWebSocketMessages(
            webSocketHandler {
              case textMessage: TextMessage => textMessage
            }
          )
        } ~
        path("user" / Remaining) { userName => {
          ctx =>
            val userData = getUserTwitterData(userName)
            userData.map(byteStringOpt => {
              byteStringOpt.map(byteString => {
                sendResponse(entity = byteString.utf8String)
              }).getOrElse(sendInternalServerError)
            })
        }} ~
        path("twitter") { ctx =>
          twitterRequestToken
        } ~
        path("callback") {
          parameters('oauth_token, 'oauth_verifier) {
            (oauth_token, oauth_verifier) => ctx =>
              twitterCallback(oauth_token, oauth_verifier)
          }
        }
    }
  }

  def twitterRequestToken: Future[RouteResult] = {
    val requestUri = "https://api.twitter.com/oauth/request_token"
    val redirectTo = "https://api.twitter.com/oauth/authenticate"
    val oAuthResponseF = oAuth1.requestToken(twitterConsumerKey, requestUri, redirectTo)

    oAuthResponseF.flatMap{
      case OAuthResponse.RedirectionSuccess(httpResponse, tokens) => {
        Redis.setOAuthTokens(tokens).map(isSuccessful => {
          if(isSuccessful) {
            logger.info("twitterRequestToken is successfully taken")
            RouteResult.Complete(httpResponse)
          }
          else {
            logger.warn("twitterRequestToken is taken, but cannot be written to Redis")
            RouteResult.Complete(
              HttpResponse(StatusCodes.InternalServerError)
            )
          }
        })
      }
      case _ => {
        logger.warn("twitterRequestToken is failed, UnAuthorized response is sent")
        Future.successful(
          RouteResult.Complete(
            HttpResponse(StatusCodes.Unauthorized, entity = "Authorization failed"))
        )
      }
    }.recover{
      case ex: Exception => {
        logger.error(s"Exception recovered while getting request token: $ex")
        RouteResult.Complete(
          HttpResponse(StatusCodes.InternalServerError)
        )
      }
    }
  }

  def twitterCallback(oauth_token: String, oauth_verifier: String): Future[RouteResult] = {
    Redis.getHM(oauth_token).flatMap(tokenOpt => {
      tokenOpt.map(tokenMap => {
        val verifierTuple = OAuth1Helper.verifier -> oauth_verifier
        val oAuthResponseF = oAuth1.accessToken(tokenMap + verifierTuple, accessTokenUri)

        val response: Future[RouteResult] = oAuthResponseF.flatMap{
          case OAuthResponse.AccessTokenSuccess(tokens) => {
            Redis.setHM(tokens("screen_name"), tokens).map(isSuccessful => {
              if(isSuccessful) {
                val twitterUserOpt = TwitterUser.fromTokens(tokens)
                twitterUserOpt.map(twitterUser => {
                  val successMessage = s"${twitterUser.screen_name} is successfully registered!"
                  logger.info(successMessage)
                  sendResponse(entity = successMessage)
                }).getOrElse{
                  logger.warn("InternalServerError: Registering user is failed, TwitterUser cannot be created")
                  sendInternalServerError
                }
              }
              else {
                logger.warn("InternalServerError: Registering user is failed, saving user to Redis is failed")
                sendInternalServerError
              }
            }).recover{
              case ex: Exception => {
                logger.error(s"InternalServerError: Exception recovered while saving user data to Redis: $ex")
                sendInternalServerError
              }
            }
          }
          case OAuthResponse.AuthenticationFailed(hr) => {
            logger.warn(s"AuthenticationFailed while taking access token: $hr")
            Future.successful{
              Complete(
                HttpResponse(StatusCodes.Unauthorized, entity = "Authorization failed")
              )
            }
          }
          case _ => {
            logger.warn("Unauthorized: While taking access token")
            Future.successful{
              Complete(HttpResponse(StatusCodes.Unauthorized, entity = "Authorization failed"))
            }
          }
        }.recover{
          case ex: Exception => {
            logger.error(s"InternalServerError: Exception recovered while taking access token: $ex")
            sendInternalServerError
          }
        }
        response
      }).getOrElse{
        logger.warn(s"Conflict: $oauth_token cannot be found in Redis")
        Future.successful(sendResponse(StatusCodes.Conflict))
      }
    }).recover{
      case ex: Exception => {
        logger.error(s"InternalServerError: Exception recovered while querying Redis for $oauth_token: $ex")
        sendInternalServerError
      }
    }
  }

  def getUserTwitterData(userName: String): Future[Option[ByteString]] = {
    Redis.getHM(userName).flatMap(tokensOpt => {
      tokensOpt.map(tokens => {
        val tokenTuple: Option[(String, String)] = for {
          token <- tokens.get(OAuth1Helper.token)
          tokenSecret <- tokens.get(OAuth1Helper.token_secret)
        } yield (token, tokenSecret)

        val params = AuthenticationHeader(
          "GET", verificationUri, twitterConsumerKey, twitterConsumerSecret, tokenTuple
        )
        val headerParamsForRequest = OAuth1Helper.headerParams(params)

        val request = HttpRequest(
          method = HttpMethods.GET,
          uri = verificationUri,
          headers = Seq(Authorization(GenericHttpCredentials("OAuth", headerParamsForRequest)))
        )
        val userDataResponse: Future[HttpResponse] = http.singleRequest(request)(mat)
        userDataResponse.flatMap{
          case hr@HttpResponse(StatusCodes.OK, _, entity, _) => {
            val entitySource = entity.dataBytes
            val graph = entitySource.toMat(Sink.head[ByteString])(Keep.right)
            val resultF = graph.run()
            logger.info(s"Twitter data for userName: $userName is successfully taken")
            resultF.map(bs => Option(bs))
          }
          case _ => {
            logger.warn(s"Twitter data for userName: $userName cannot be taken")
            Future.successful(None)
          }
        }
      }).getOrElse{
        logger.warn(s"$userName cannot be found in Redis")
        Future.successful(None)
      }
    }).recover{
      case ex: Exception => {
        logger.error(s"Exception recovered while querying Redis for $userName: $ex")
        None
      }
    }
  }
}