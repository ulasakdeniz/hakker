package com.ulasakdeniz.base

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Inside, Matchers, OptionValues, WordSpec}

trait SuperSpec extends WordSpec
  with Matchers
  with ScalaFutures
  with OptionValues
  with Inside
