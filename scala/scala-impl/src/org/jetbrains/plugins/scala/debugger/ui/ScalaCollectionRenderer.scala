package org.jetbrains.plugins.scala.debugger.ui

import java.lang
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

import com.intellij.debugger.engine._
import com.intellij.debugger.engine.evaluation.expression.Evaluator
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluatorImpl
import com.intellij.debugger.engine.evaluation.expression.TypeEvaluator
import com.intellij.debugger.engine.evaluation.CodeFragmentKind
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl
import com.intellij.debugger.impl.DebuggerUtilsAsync
import com.intellij.debugger.settings.NodeRendererSettings
import com.intellij.debugger.ui.tree.render._
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.debugger.ui.tree.NodeManager
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.DebuggerContext
import com.intellij.debugger.JavaDebuggerBundle
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.psi.PsiElement
import com.sun.jdi._
import org.jetbrains.plugins.scala.debugger.evaluation.EvaluationException
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaDuplexEvaluator
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaFieldEvaluator
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaMethodEvaluator
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaThisEvaluator
import org.jetbrains.plugins.scala.debugger.filters.ScalaDebuggerSettings
import org.jetbrains.plugins.scala.debugger.ui.ScalaCollectionRenderer._
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createExpressionFromText

import scala.collection.mutable
import scala.language.implicitConversions
import scala.reflect.NameTransformer

/**
 * @author Nikolay.Tropin
 */

class ScalaCollectionRenderer extends CompoundReferenceRenderer(NodeRendererSettings.getInstance(), "Scala collection", sizeLabelRenderer, ScalaToArrayRenderer) {

  setClassName(collectionClassName)

  override def isEnabled: Boolean = ScalaDebuggerSettings.getInstance().FRIENDLY_COLLECTION_DISPLAY_ENABLED
}

object ScalaCollectionRenderer {
  implicit class ToExpressionEvaluator(private val e: Evaluator) extends AnyVal {
    def exprEval = new ExpressionEvaluatorImpl(e)
  }

  implicit def toCFJBoolean(f: CompletableFuture[Boolean]): CompletableFuture[java.lang.Boolean] =
    f.asInstanceOf[CompletableFuture[java.lang.Boolean]]

  implicit def toCFBoolean(f: CompletableFuture[java.lang.Boolean]): CompletableFuture[Boolean] =
    f.asInstanceOf[CompletableFuture[Boolean]]

  def instanceOf(tp: Type, baseClassNames: String*): Boolean =
    baseClassNames.exists(DebuggerUtils.instanceOf(tp, _))

  def instanceOfAsync(tp: Type, baseClassNames: String*): CompletableFuture[Boolean] = {
    val futures = baseClassNames.map(DebuggerUtilsAsync.instanceOf(tp, _).thenApply[Boolean](_.booleanValue()))
    forallAsync(futures: _*)
  }

  def andAsync(f1: CompletableFuture[Boolean], f2: CompletableFuture[Boolean]): CompletableFuture[Boolean] =
    f1.thenCombine[Boolean, Boolean](f2, _ && _)

  def orAsync(f1: CompletableFuture[Boolean], f2: CompletableFuture[Boolean]): CompletableFuture[Boolean] =
    f1.thenCombine[Boolean, Boolean](f2, _ || _)

  def forallAsync(futures: CompletableFuture[Boolean]*): CompletableFuture[Boolean] =
    futures.reduce(andAsync)

  private val evaluators: mutable.HashMap[DebugProcess, CachedEvaluators] = new mutable.HashMap()

  private def cachedEvaluators(context: EvaluationContext): CachedEvaluators = {
    val debugProcess = context.getDebugProcess
    evaluators.get(debugProcess).orElse {
      if (debugProcess.isDetached || debugProcess.isDetaching) None
      else {
        val value = new CachedEvaluators
        evaluators.put(debugProcess, value)
        debugProcess.addDebugProcessListener(new DebugProcessAdapterImpl {
          override def processDetached(process: DebugProcessImpl, closedByUser: Boolean): Unit = evaluators.remove(debugProcess)
        })
        Some(value)
      }
    }.getOrElse(new CachedEvaluators)
  }

  private def hasDefiniteSizeEval(context: EvaluationContext) = cachedEvaluators(context).hasDefiniteSizeEval
  private def nonEmptyEval(context: EvaluationContext) = cachedEvaluators(context).nonEmptyEval
  private def sizeEval(context: EvaluationContext) = cachedEvaluators(context).sizeEval

  private[debugger] val collectionClassName = "scala.collection.Iterable"
  private[debugger] val streamClassName = "scala.collection.immutable.Stream"
  private[debugger] val streamViewClassName = "scala.collection.immutable.StreamView"
  private[debugger] val viewClassName = "scala.collection.IterableView"
  private[debugger] val iteratorClassName = "scala.collection.Iterator"

  private[debugger] val viewClassName_2_13 = "scala.collection.View"
  private[debugger] val lazyList_2_13 = "scala.collection.immutable.LazyList"

  private[debugger] val sizeLabelRenderer = createSizeLabelRenderer()

  private[debugger] def hasDefiniteSize(value: Value, evaluationContext: EvaluationContext): Boolean = {
    value.`type`() match {
      case ct: ClassType if ct.name.startsWith("scala.collection") && notStream(ct) && notIterator(ct) => true
      case _ => evaluateBoolean(value, evaluationContext, hasDefiniteSizeEval(evaluationContext))
    }
  }

  private[debugger] def nonEmpty(value: Value, evaluationContext: EvaluationContext): Boolean = {
    value.`type`() match {
      case ct: ClassType if ct.name.toLowerCase.contains("empty") || ct.name.contains("Nil") => false
      case _ => evaluateBoolean(value, evaluationContext, nonEmptyEval(evaluationContext))
    }
  }

  private[debugger] def size(value: Value, evaluationContext: EvaluationContext): Int =
    evaluateInt(value, evaluationContext, sizeEval(evaluationContext))

  private def checkNotCollectionOfKind(tp: Type, shortNames: String*)(baseClassNames: String*) =
    !shortNames.exists(tp.name().contains(_)) && !instanceOf(tp, baseClassNames: _*)

  private def checkNotCollectionOfKindAsync(tp: Type, shortNames: String*)(baseClassNames: String*): CompletableFuture[Boolean] =
    if (shortNames.exists(tp.name().contains(_))) completedFuture(false)
    else instanceOfAsync(tp, baseClassNames: _*).thenApply(!_)

  private def notStream(tp: Type): Boolean =
    checkNotCollectionOfKind(tp, "Stream", "LazyList")(streamClassName, lazyList_2_13)

  private def notViewAsync(tp: Type): CompletableFuture[Boolean] =
    checkNotCollectionOfKindAsync(tp, "View")(viewClassName, viewClassName_2_13)

  private def notStreamAsync(tp: Type): CompletableFuture[Boolean] =
    checkNotCollectionOfKindAsync(tp, "Stream", "LazyList")(streamClassName, lazyList_2_13)

  private def notIterator(tp: Type): Boolean = checkNotCollectionOfKind(tp, "Iterator")(iteratorClassName)
  /**
   * util method for collection displaying in debugger
    *
    * @param name name encoded for jvm (for example, scala.collection.immutable.$colon$colon)
   * @return decoded nonqualified part (:: in example)
   */
  def transformName(name: String): String = getNonQualifiedName(NameTransformer decode name)

  private def getNonQualifiedName(fullName: String): String = {
    val index =
      if (fullName endsWith "`") fullName.substring(0, fullName.length - 1).lastIndexOf('`')
      else fullName.lastIndexOf('.')
    "\"" + fullName.substring(index + 1) + "\""
  }

  private def createSizeLabelRenderer(): LabelRenderer = {
    val expressionText = "size()"
    val sizePrefix = " size = "
    val labelRenderer: LabelRenderer = new LabelRenderer() {
      override def calcLabel(descriptor: ValueDescriptor, evaluationContext: EvaluationContext, labelListener: DescriptorLabelListener): String = {
        descriptor.getValue match {
          case null => "null"
          case objRef: ObjectReference =>
            val typeName = if (objRef.referenceType() != null) ScalaCollectionRenderer.transformName(objRef.referenceType().name) else ""
            val sizeValue =
              if (!hasDefiniteSize(objRef, evaluationContext)) "?"
              else size(objRef, evaluationContext)
            typeName + sizePrefix + sizeValue
        }
      }
    }
    labelRenderer.setLabelExpression(new TextWithImportsImpl(CodeFragmentKind.EXPRESSION, expressionText, "", StdFileTypes.JAVA))
    labelRenderer
  }

  private def evaluateBoolean(value: Value, context: EvaluationContext, evaluator: Evaluator): Boolean = {
    evaluate(value, context, evaluator) match {
      case b: BooleanValue => b.booleanValue()
      case x => throw EvaluationException(s"$x is not a boolean")
    }
  }

  private def evaluateInt(value: Value, context: EvaluationContext, evaluator: Evaluator): Int = {
    evaluate(value, context, evaluator) match {
      case i: IntegerValue => i.intValue()
      case x => throw EvaluationException(s"$x is not an integer")
    }
  }

  private def evaluate(value: Value, context: EvaluationContext, evaluator: Evaluator) = {
    if (value != null) {
      val newContext = context.createEvaluationContext(value)
      evaluator.exprEval.evaluate(newContext)
    }
    else if (value != null) {
      val newContext = context.createEvaluationContext(value)
      evaluator.exprEval.evaluate(newContext) match {
        case b: BooleanValue => b.booleanValue()
        case _ => throw EvaluationException("Cannot evaluate expression")
      }
    }
    else throw EvaluationException("Cannot evaluate expression")
  }

  object ScalaToArrayRenderer extends ReferenceRenderer(collectionClassName) with ChildrenRenderer {

    private def toArrayEvaluator(context: EvaluationContext) = cachedEvaluators(context).toArrayEvaluator

    override def getUniqueId: String = "ScalaToArrayRenderer"

    override def isExpandableAsync(value: Value, context: EvaluationContext, parentDescriptor: NodeDescriptor): CompletableFuture[lang.Boolean] = {
      //todo: make async
      val evaluationContext: EvaluationContext = context.createEvaluationContext(value)
      try {
        return CompletableFuture.completedFuture(nonEmpty(value, context) && hasDefiniteSize(value, context))
      }
      catch {
        case _: EvaluateException =>
      }

      try {
        val children: Value = evaluateChildren(evaluationContext, parentDescriptor)
        val defaultChildrenRenderer: ChildrenRenderer = DebugProcessImpl.getDefaultRenderer(value.`type`)
        defaultChildrenRenderer.isExpandableAsync(children, evaluationContext, parentDescriptor)
      }
      catch {
        case _: EvaluateException =>
          CompletableFuture.completedFuture(true)
      }
    }

    override def getChildValueExpression(node: DebuggerTreeNode, context: DebuggerContext): PsiElement = {
      createExpressionFromText("this.toArray()")(context.getProject)
    }


    override def buildChildren(value: Value, builder: ChildrenBuilder, evaluationContext: EvaluationContext): Unit = {
      val nodeManager: NodeManager = builder.getNodeManager
      try {
        val parentDescriptor: ValueDescriptor = builder.getParentDescriptor
        val childrenValue: Value = evaluateChildren(evaluationContext.createEvaluationContext(value), parentDescriptor)
        val renderer: NodeRenderer = getChildrenRenderer(childrenValue, parentDescriptor)
        renderer.buildChildren(childrenValue, builder, evaluationContext)
      }
      catch {
        case e: EvaluateException =>
          val errorChildren: util.ArrayList[DebuggerTreeNode] = new util.ArrayList[DebuggerTreeNode]
          errorChildren.add(nodeManager.createMessageNode(JavaDebuggerBundle.message("error.unable.to.evaluate.expression") + " " + e.getMessage))
          builder.setChildren(errorChildren)
      }
    }

    private def getChildrenRenderer(childrenValue: Value, parentDescriptor: ValueDescriptor): NodeRenderer = {
      var renderer: NodeRenderer = ExpressionChildrenRenderer.getLastChildrenRenderer(parentDescriptor)
      if (renderer == null || childrenValue == null || !renderer.isApplicable(childrenValue.`type`)) {
        renderer = DebugProcessImpl.getDefaultRenderer(if (childrenValue != null) childrenValue.`type` else null)
        ExpressionChildrenRenderer.setPreferableChildrenRenderer(parentDescriptor, renderer)
      }
      renderer
    }

    private def evaluateChildren(context: EvaluationContext, descriptor: NodeDescriptor): Value = {
      val evaluator: ExpressionEvaluator = toArrayEvaluator(context).exprEval
      val value: Value = evaluator.evaluate(context)
      context.keep(value)
      value
    }
  }

  private class CachedEvaluators {
    val hasDefiniteSizeEval: ScalaMethodEvaluator = ScalaMethodEvaluator(new ScalaThisEvaluator(), "hasDefiniteSize", JVMNameUtil.getJVMRawText("()Z"), Nil)
    val nonEmptyEval: ScalaMethodEvaluator = ScalaMethodEvaluator(new ScalaThisEvaluator(), "nonEmpty", JVMNameUtil.getJVMRawText("()Z"), Nil)
    val sizeEval: ScalaMethodEvaluator = ScalaMethodEvaluator(new ScalaThisEvaluator(), "size", JVMNameUtil.getJVMRawText("()I"), Nil)

    val toArrayEvaluator: ScalaMethodEvaluator = {
      val classTagObjectEval = {
        val classTagEval = stableObjectEval("scala.reflect.ClassTag$")
        ScalaMethodEvaluator(classTagEval, "Object", null, Nil)
      }

      val manifestObjectEval = {
        val predefEval = stableObjectEval("scala.Predef$")
        val manifestEval = ScalaMethodEvaluator(predefEval, "Manifest", null, Nil)
        ScalaMethodEvaluator(manifestEval, "Object", null, Nil)
      }
      val argEval = ScalaDuplexEvaluator(classTagObjectEval, manifestObjectEval)

      ScalaMethodEvaluator(new ScalaThisEvaluator(), "toArray", null, Seq(argEval))
    }

    private def stableObjectEval(name: String) = ScalaFieldEvaluator(new TypeEvaluator(JVMNameUtil.getJVMRawText(name)), "MODULE$", classPrivateThisField = false)
  }
}


