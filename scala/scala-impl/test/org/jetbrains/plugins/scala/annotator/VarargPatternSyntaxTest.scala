package org.jetbrains.plugins.scala.annotator

import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.codeInspection.ScalaAnnotatorQuickFixTestBase

abstract class VarargPatternSyntaxTestBase extends ScalaAnnotatorQuickFixTestBase

class VarargPatternSyntaxTest extends VarargPatternSyntaxTestBase {

  override protected val description = "':' syntax in vararg pattern requires Scala 3.0"

  private val replaceWithAtFix = "Replace with '@'"

  def testVarargPatternWithColonWithBindName(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, other$START:$END _*) =>
         |}
         |""".stripMargin
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, other@_*) =>
         |}
         |""".stripMargin
    checkTextHasError(code)
    testQuickFix(code, codeFixed, replaceWithAtFix)
  }

  def testVarargPatternWithColonWithBindWildcard(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, _$START:$END _*) =>
         |}
         |""".stripMargin
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, _@_*) =>
         |}
         |""".stripMargin
    checkTextHasError(code)
    testQuickFix(code, codeFixed, replaceWithAtFix)
  }

  def testVarargPatternWithAt(): Unit =
    checkTextHasNoErrors(
      s"""List(1, 2, 3) match {
         |  case List(first, other@_*) =>
         |}""".stripMargin
    )
}

class VarargPatternSyntaxScala3Test extends VarargPatternSyntaxTestBase {

  override protected val description = "'@' syntax in vararg pattern has been deprecated since Scala 3.0"

  private val replaceWithColonFix = "Replace with ':'"

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ScalaVersion.Scala_3_0

  def testVarargPatternWithColonWithBindName(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, other$START@$END _*) =>
         |}
         |""".stripMargin
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, other: _*) =>
         |}
         |""".stripMargin
    checkTextHasError(code)
    testQuickFix(code, codeFixed, replaceWithColonFix)
  }

  def testVarargPatternWithColonWithBindWildcard(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, _$START@$END _*) =>
         |}
         |"""
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, _: _*) =>
         |}
         |""".stripMargin
    checkTextHasError(code)
    testQuickFix(code, codeFixed, replaceWithColonFix)
  }

  def testVarargPatternWithColon(): Unit =
    checkTextHasNoErrors(
      s"""List(1, 2, 3) match {
         |  case List(first, other: _*) =>
         |}""".stripMargin
    )
}

class VarargShortPatternSyntaxScala3Test extends VarargPatternSyntaxTestBase {

  override protected val description = "Short _* pattern syntax has been deprecated since Scala 3.0"

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ScalaVersion.Scala_3_0

  def testShortVarargPattern(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, ${START}_*$END) =>
         |}
         |"""
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, _: _*) =>
         |}
         |"""
    checkTextHasError(code)
    testQuickFix(code, codeFixed, "Replace with '_: _*'")
  }

  // NOTE: yes, codeFixed still has an error (vararg should be last argument)
  def testShortVarargPatternErrorMessageShouldHavePriorityOverOtherErrors(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(first, ${START}_*$END, last) =>
         |}
         |"""
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(first, _: _*, last) =>
         |}
         |"""
    checkTextHasError(code)
    testQuickFix(code, codeFixed, "Replace with '_: _*'")
  }

  def testShortVarargPatternErrorMessageShouldHavePriorityOverOtherErrors_1(): Unit = {
    val code =
      s"""List(1, 2, 3) match {
         |  case List(${START}_*$END, last) =>
         |}
         |"""
    val codeFixed =
      s"""List(1, 2, 3) match {
         |  case List(_: _*, last) =>
         |}
         |"""
    checkTextHasError(code)
    testQuickFix(code, codeFixed, "Replace with '_: _*'")
  }
}
