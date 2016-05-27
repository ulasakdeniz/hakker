package com.ulasakdeniz.hakker.auth

import scala.util.Random

object OAuth1Helper extends AbstractOAuth1Helper

abstract class AbstractOAuth1Helper {

  // http://oauth.net/core/1.0/#auth_header
  val consumer_key = "oauth_consumer_key"
  val nonce = "oauth_nonce"
  val signature = "oauth_signature"
  val signature_method = "oauth_signature_method"
  val timestamp = "oauth_timestamp"
  val version = "oauth_version"

  val callback_confirmed = "oauth_callback_confirmed"
  val verifier = "oauth_verifier"
  val token = "oauth_token"
  val token_secret = "oauth_token_secret"

  val HmacSHA1 = "HMAC-SHA1"
  val version1 = "1.0"

  val random = new Random(System.currentTimeMillis())

  def headerParams(header: AuthenticationHeader): Map[String, String] = {
    def parameterMap = Map(
      consumer_key -> header.consumerKey,
      nonce -> generateNonce,
      signature_method -> HmacSHA1,
      timestamp -> generateTimestamp,
      version -> version1
    )
    val params = header.tokenOpt.map(t => parameterMap + (token -> t._1))
      .getOrElse(parameterMap)
    val generatedSignature = Signer
      .generateSignature(header.httpMethod, header.uri, params.toList, header.consumerSecret,
        oAuthTokenSecret = header.tokenOpt.map(t => t._2))
    params + (signature -> generatedSignature)
  }

  def generateNonce: String = {
    random.alphanumeric.take(32).mkString
  }

  def generateTimestamp: String = {
    (System.currentTimeMillis() / 1000l).toString
  }
}