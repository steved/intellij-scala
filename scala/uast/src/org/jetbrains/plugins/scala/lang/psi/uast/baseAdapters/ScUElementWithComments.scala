package org.jetbrains.plugins.scala.lang.psi.uast.baseAdapters

import com.intellij.psi.{PsiComment, PsiElement, PsiWhiteSpace}
import org.jetbrains.plugins.scala.lang.psi.uast.converter.Scala2UastConverter
import org.jetbrains.uast.java.internal.JavaUElementWithComments
import org.jetbrains.uast.{UComment, UElement, UExpression}

import scala.collection.JavaConverters._

/**
  * Default implementation of the [[UElement]]#getComments
  * same as [[JavaUElementWithComments]] implementation.
  */
trait ScUElementWithComments extends UElement {

  override def getComments: java.util.List[UComment] = {
    val psi = getSourcePsi
    if (psi == null) return Seq.empty.asJava
    val childrenComments = psi.getChildren.collect {
      case pc: PsiComment => Scala2UastConverter.createUComment(pc, this)
    }

    this match {
      case _: UExpression =>
        Seq
          .concat(
            childrenComments,
            nearestCommentSibling(psi, forward = true)
              .map(Scala2UastConverter.createUComment(_, this)),
            nearestCommentSibling(psi, forward = false)
              .map(Scala2UastConverter.createUComment(_, this))
          )
          .asJava
      case _ => childrenComments.toSeq.asJava
    }
  }

  private def nearestCommentSibling(psiElement: PsiElement,
                                    forward: Boolean): Option[PsiComment] = {
    var sibling =
      if (forward) psiElement.getNextSibling else psiElement.getPrevSibling

    while (sibling.isInstanceOf[PsiWhiteSpace] &&
           !sibling.getText.contains('\n')) {
      sibling = if (forward) sibling.getNextSibling else sibling.getPrevSibling
    }
    Option(sibling).collect { case pc: PsiComment => pc }
  }
}