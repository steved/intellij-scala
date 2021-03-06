package org.jetbrains.plugins.scala
package testingSupport
package specs2

/**
 * @author Roman.Shein
 * @since 16.10.2014.
 */
abstract class SCL7228Test extends Specs2TestCase {

  override def debugProcessOutput: Boolean = true

  addSourceFile("SCL7228Test.scala",
    """
      |import org.specs2.mutable.Specification
      |
      |class SCL7228Test extends Specification {
      |  override def is = "foo (bar)" ! (true == true)
      |}
    """.stripMargin
  )

  def testScl7228(): Unit =
    runTestByLocation2(3, 1, "SCL7228Test.scala",
      assertConfigAndSettings(_, "SCL7228Test"),
      assertResultTreeHasExactNamedPath(_, Seq("[root]", "SCL7228Test", "foo (bar)"))
    )
}
