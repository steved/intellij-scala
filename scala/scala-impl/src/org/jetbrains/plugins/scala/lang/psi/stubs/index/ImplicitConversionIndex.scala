package org.jetbrains.plugins.scala
package lang
package psi
package stubs
package index

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScMember}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys.StubIndexKeyExt

/**
 * @author Alexander Podkhalyuzin
 */
final class ImplicitConversionIndex extends ScStringStubIndexExtension[ScMember] {

  //noinspection TypeAnnotation
  override def getKey = ImplicitConversionIndex.indexKey
}

object ImplicitConversionIndex extends ImplicitIndex {

  //noinspection TypeAnnotation
  override protected val indexKey = ScalaIndexKeys.IMPLICIT_CONVERSION_KEY

  private val dummyStringKey: String = "implicit_conversion"

  def conversionCandidatesForFqn(classFqn: String, scope: GlobalSearchScope)
                                (implicit project: Project): Iterable[ScFunction] =
    for {
      member   <- forClassFqn(classFqn, scope)
      function <- member match {
        case f: ScFunction => f :: Nil
        case c: ScClass => c.getSyntheticImplicitMethod.toSet
        case _ => Nil
      }
    } yield function

  def allConversions(scope: GlobalSearchScope)
                    (implicit project: Project): Iterable[ScFunction] = for {
    key      <- indexKey.allKeys
    member   <- indexKey.elements(key, scope)

    function <- member match {
      case f: ScFunction => f :: Nil
      case c: ScClass => c.getSyntheticImplicitMethod.toList
      case _ => Nil
    }
  } yield function

  override def occurrences(sink: IndexSink, names: Array[String]): Unit = {
    super.occurrences(sink, names)
    //type of definition is missing or we couldn't extract class name from it
    if (names.isEmpty) {
      occurrence(sink, dummyStringKey)
    }
  }
}