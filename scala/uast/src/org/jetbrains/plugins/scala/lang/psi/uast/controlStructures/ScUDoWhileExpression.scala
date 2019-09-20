package org.jetbrains.plugins.scala.lang.psi.uast.controlStructures

import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScDo
import org.jetbrains.plugins.scala.lang.psi.uast.converter.Scala2UastConverter._
import org.jetbrains.plugins.scala.lang.psi.uast.baseAdapters.{
  ScUAnnotated,
  ScUExpression
}
import org.jetbrains.plugins.scala.lang.psi.uast.internals.LazyUElement
import org.jetbrains.uast._

/**
  * [[ScDo]] adapter for the [[UDoWhileExpression]]
  *
  * @param scExpression   Scala PSI element representing `do {} while()` cycle
  */
class ScUDoWhileExpression(
  override protected val scExpression: ScDo,
  override protected val parent: LazyUElement
) extends UDoWhileExpressionAdapter
    with ScUExpression
    with ScUAnnotated {

  import scExpression._

  override def getCondition: UExpression =
    condition.convertToUExpressionOrEmpty(this)

  override def getDoIdentifier: UIdentifier =
    createUIdentifier(
      scExpression.findFirstChildByType(ScalaTokenTypes.kDO),
      this
    )

  override def getWhileIdentifier: UIdentifier =
    createUIdentifier(
      scExpression.findFirstChildByType(ScalaTokenTypes.kWHILE),
      this
    )

  override def getBody: UExpression = body.convertToUExpressionOrEmpty(this)
}