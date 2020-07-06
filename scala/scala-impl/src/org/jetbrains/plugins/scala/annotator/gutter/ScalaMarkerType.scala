package org.jetbrains.plugins.scala
package annotator
package gutter

import java.awt.event.MouseEvent
import java.util

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.ide.util.{PsiClassListCellRenderer, PsiElementListCellRenderer}
import com.intellij.psi._
import com.intellij.psi.presentation.java.ClassPresentationUtil
import com.intellij.psi.search.searches.ClassInheritorsSearch
import javax.swing.{Icon, ListCellRenderer}
import org.jetbrains.annotations.Nls
import org.jetbrains.plugins.scala.annotator.gutter.GutterUtil.namedParent
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.impl.search.ScalaOverridingMemberSearcher
import org.jetbrains.plugins.scala.lang.psi.types.TermSignature
import org.jetbrains.plugins.scala.util.SAMUtil

/**
 * User: Alexander Podkhalyuzin
 * Date: 09.11.2008
 */
object ScalaMarkerType {
  private[this] def extractClassName(sigs: Seq[TermSignature]): Option[String] =
    sigs.headOption.map(_.namedElement).collect { case ContainingClass(aClass) => aClass.qualifiedName }

  private[this] def sigToNavigatableElement(s: TermSignature): Option[NavigatablePsiElement] = s.namedElement match {
    case ne: NavigatablePsiElement => Option(ne)
    case _                         => None
  }

  private[this] def navigateToSuperMember[T <: NavigatablePsiElement](
    event:                MouseEvent,
    members:              Array[T],
    @Nls title:           String,
    @Nls findUsagesTitle: String,
    renderer:             ListCellRenderer[T] = newCellRenderer.asInstanceOf[ListCellRenderer[T]]
  ): Unit = PsiElementListNavigator.openTargets(event, members, title, findUsagesTitle, renderer)

  private[this] def navigateToSuperMethod(
    event:       MouseEvent,
    method:      PsiMethod,
    includeSelf: Boolean
  ): Unit = {
    val superMethods = (method match {
      case fn: ScFunction =>
        val sigs = fn.superSignaturesIncludingSelfType
        sigs.flatMap(sigToNavigatableElement).toArray
      case _ => method.findSuperMethods(false).map(e => e: NavigatablePsiElement)
    }) ++ (if (includeSelf) Array(method) else NavigatablePsiElement.EMPTY_NAVIGATABLE_ELEMENT_ARRAY)

    val title           = ScalaBundle.message("navigation.title.super.methods", method.name)
    val findUsagesTitle = ScalaBundle.message("navigation.findUsages.title.super.methods", method.name)
    navigateToSuperMember(event, superMethods, title, findUsagesTitle)
  }

  def findOverrides(member: ScMember, deep: Boolean): Seq[PsiNamedElement] = {

    val namedElems: Seq[ScNamedElement] = member match {
      case d: ScDeclaredElementsHolder => d.declaredElements.filterBy[ScNamedElement]
      case param: ScClassParameter => Seq(param)
      case ta: ScTypeAlias => Seq(ta)
      case _ => Seq.empty
    }

    namedElems.flatMap(ScalaOverridingMemberSearcher.search(_, deep = deep, withSelfType = true))
  }

  val overridingMember: ScalaMarkerType = ScalaMarkerType(
    element =>
      namedParent(element)
        .flatMap {
          case method: ScFunction =>
            val signatures = method.superSignaturesIncludingSelfType
            val maybeClass = extractClassName(signatures)

            val msg: String => String  =
              if (GutterUtil.isOverrides(element, signatures)) ScalaBundle.message("overrides.method.from.super", _)
              else ScalaBundle.message("implements.method.from.super", _)

            maybeClass.map(msg)
          case param: ScClassParameter =>
            val signatures = ScalaPsiUtil.superValsSignatures(param, withSelfType = true)
            val maybeClass = extractClassName(signatures)
            val msg: String => String =
              if (GutterUtil.isOverrides(element, signatures)) ScalaBundle.message("overrides.val.from.super", _)
              else ScalaBundle.message("implements.val.from.super", _)

            maybeClass.map(msg)
          case v: ScValueOrVariable =>
            val bindings   = v.declaredElements.filter(e => element.textMatches(e.name))
            val signatures = bindings.flatMap(ScalaPsiUtil.superValsSignatures(_, withSelfType = true))
            val maybeClass = extractClassName(signatures)

            val msg: String => String =
              if (GutterUtil.isOverrides(element, signatures)) ScalaBundle.message("overrides.val.from.super", _)
              else ScalaBundle.message("implements.val.from.super", _)

            maybeClass.map(msg)
          case ta: ScTypeAlias =>
            val superMembers = ScalaPsiUtil.superTypeMembers(ta, withSelfType = true)
            val maybeClass   = superMembers.headOption.collect { case ContainingClass(aClass) => aClass }
            maybeClass.map(cls => ScalaBundle.message("overrides.type.from.super", cls.name))
          case _ => None
        }
        .orNull,
    (event, element) =>
      namedParent(element).collect {
        case method: ScFunction => navigateToSuperMethod(event, method, includeSelf = false)
        case param: ScClassParameter =>
          val signatures      = ScalaPsiUtil.superValsSignatures(param, withSelfType = true)
          val superMembers    = signatures.flatMap(sigToNavigatableElement).toArray
          val title           = ScalaBundle.message("navigation.title.super.vals", element.getText)
          val findUsagesTitle = ScalaBundle.message("navigation.findUsages.title.super.vals", element.getText)
          navigateToSuperMember(event, superMembers, title, findUsagesTitle)
        case v: ScValueOrVariable =>
          val bindings        = v.declaredElements.filter(e => element.textMatches(e.name))
          val signatures      = bindings.flatMap(ScalaPsiUtil.superValsSignatures(_, withSelfType = true))
          val superMembers    = signatures.flatMap(sigToNavigatableElement).toArray
          val title           = ScalaBundle.message("navigation.title.super.vals", element.getText)
          val findUsagesTitle = ScalaBundle.message("navigation.findUsages.title.super.vals", element.getText)
          navigateToSuperMember(event, superMembers, title, findUsagesTitle)
        case ta: ScTypeAlias =>
          val superElements = ScalaPsiUtil.superTypeMembers(ta, withSelfType = true)
          val navigatable: Array[NavigatablePsiElement] =
            superElements.collect { case ne: NavigatablePsiElement => ne }.toArray
          val title           = ScalaBundle.message("navigation.title.super.types", ta.name)
          val findUsagesTitle = ScalaBundle.message("navigation.findUsages.title.super.types", ta.name)
          navigateToSuperMember(event, navigatable, title, findUsagesTitle)
    }
  )

  val overriddenMember: ScalaMarkerType = ScalaMarkerType(
    element =>
      namedParent(element).collect {
        case _: ScMember =>
          if (GutterUtil.isAbstract(element)) ScalaBundle.message("has.implementations")
          else ScalaBundle.message("is.overridden.by")
      }.orNull,
    (event, element) =>
      namedParent(element).collect {
        case member: ScMember =>

          val overrides = findOverrides(member, deep = true)

          if (overrides.nonEmpty) {
            val name = overrides.headOption.fold("")(_.name)

            val (title, findUsagesTitle) =
              if (GutterUtil.isAbstract(member)) {
                ScalaBundle.message("navigation.title.implementing.member", name, overrides.length.toString) ->
                  ScalaBundle.message("navigation.findUsages.title.implementing.member", name)
              } else {
                ScalaBundle.message("navigation.title.overriding.member", name, overrides.length.toString) ->
                  ScalaBundle.message("navigation.findUsages.title.overriding.member", name)
              }

            val renderer = newCellRenderer
            util.Arrays.sort(overrides.map(e => e: PsiElement).toArray, renderer.getComparator)
            PsiElementListNavigator.openTargets(
              event,
              overrides.map(_.asInstanceOf[NavigatablePsiElement]).toArray,
              title,
              findUsagesTitle,
              renderer.asInstanceOf[ListCellRenderer[NavigatablePsiElement]]
            )
          }
    }
  )

  def newCellRenderer: PsiElementListCellRenderer[PsiElement] = new ScCellRenderer

  val subclassedClass: ScalaMarkerType = ScalaMarkerType(
    element =>
      element.parent.collect {
        case _: ScTrait => ScalaBundle.message("trait.has.implementations")
        case _          => ScalaBundle.message("class.has.subclasses")
      }.orNull,
    (event, element) =>
      element.parent.collect {
        case aClass: PsiClass =>
          val inheritors = ClassInheritorsSearch.search(aClass, aClass.getUseScope, true).toArray(PsiClass.EMPTY_ARRAY)
          if (inheritors.nonEmpty) {
            val cname = aClass.name
            val (title, findUsagesTitle) =
              if (aClass.isInstanceOf[ScTrait]) {
                ScalaBundle.message("navigation.title.inheritors.trait", cname, inheritors.length.toString) ->
                  ScalaBundle.message("navigation.findUsages.title.inheritors.trait", cname)
              } else {
                ScalaBundle.message("navigation.title.inheritors.class", cname, inheritors.length.toString) ->
                  ScalaBundle.message("navigation.findUsages.title.inheritors.class", cname)
              }

            val renderer = new PsiClassListCellRenderer
            util.Arrays.sort(inheritors, renderer.getComparator)
            PsiElementListNavigator.openTargets(
              event,
              inheritors,
              title,
              findUsagesTitle,
              renderer.asInstanceOf[ListCellRenderer[PsiClass]]
            )
          }
    }
  )

  def samTypeImplementation(aClass: PsiClass): ScalaMarkerType = ScalaMarkerType(
    _ => ScalaBundle.message("implements.method.from.super", aClass.qualifiedName),
    (event, _) => SAMUtil.singleAbstractMethod(aClass).foreach(navigateToSuperMethod(event, _, includeSelf = true))
  )

  private class ScCellRenderer extends PsiElementListCellRenderer[PsiElement] {

    override def getElementText(element: PsiElement): String = {
      def defaultPresentation: String =
        element.getText.substring(0, math.min(element.getText.length, 20))

      element match {
        case method: PsiMethod if method.containingClass != null =>
          val presentation = method.containingClass.getPresentation
          if (presentation != null)
            presentation.getPresentableText + " " + presentation.getLocationString
          else {
            ClassPresentationUtil.getNameForClass(method.containingClass, false)
          }
        case xlass: PsiClass =>
          val presentation = xlass.getPresentation
          presentation.getPresentableText + " " + presentation.getLocationString
        case x: PsiNamedElement if ScalaPsiUtil.nameContext(x).isInstanceOf[ScMember] =>
          val containing = ScalaPsiUtil.nameContext(x).asInstanceOf[ScMember].containingClass
          if (containing == null) defaultPresentation
          else {
            val presentation = containing.getPresentation
            presentation.getPresentableText + " " + presentation.getLocationString
          }
        case x: ScClassParameter =>
          val presentation = x.getPresentation
          presentation.getPresentableText + " " + presentation.getLocationString
        case x: PsiNamedElement => x.name
        case _                  => defaultPresentation
      }
    }

    override def getContainerText(psiElement: PsiElement, s: String): Null = null

    override def getIconFlags: Int = 0

    override def getIcon(element: PsiElement): Icon =
      element match {
        case _: PsiMethod => super.getIcon(element)
        case x: PsiNamedElement if ScalaPsiUtil.nameContext(x) != null =>
          ScalaPsiUtil.nameContext(x).getIcon(getIconFlags)
        case _ => super.getIcon(element)
      }
  }
}

case class ScalaMarkerType(
  tooltipProvider:   com.intellij.util.Function[PsiElement, String],
  navigationHandler: GutterIconNavigationHandler[PsiElement]
)
