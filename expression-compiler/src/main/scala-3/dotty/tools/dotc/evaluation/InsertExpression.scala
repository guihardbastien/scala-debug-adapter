package dotty.tools.dotc.evaluation

import dotty.tools.dotc.EvaluationContext
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.util.SourceFile
import java.nio.charset.StandardCharsets
import dotty.tools.io.VirtualFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.NoSourcePosition

/**
 * This phase:
 * - inserts the expression that is being evaluated in the line of the breakpoint
 * - inserts `Expression` class in a proper package
 */
class InsertExpression(using
    evalCtx: EvaluationContext
) extends Phase:
  private var expressionInserted = false

  override def phaseName: String = InsertExpression.name
  override def isCheckable: Boolean = false

  private val evaluationClassSource =
    s"""|class ${evalCtx.evaluationClassName}(names: Array[String], values: Array[Object]):
        |  import scala.util.Try
        |
        |  val valuesByName = names.zip(values).toMap
        |
        |  def evaluate(): Any =
        |    ()
        |
        |  def getLocalValue(name: String): Any =
        |    valuesByName(name)
        |
        |  def callMethod(obj: Any, className: String, methodName: String, paramTypesNames: Array[String], returnTypeName: String, args: Array[Object]): Any =
        |    val clazz = getClass.getClassLoader.loadClass(className)
        |    val method = clazz.getDeclaredMethods
        |      .find { m => 
        |        m.getName == methodName &&
        |          m.getReturnType.getName == returnTypeName &&
        |          m.getParameterTypes.map(_.getName).toSeq == paramTypesNames.toSeq
        |      }
        |      .getOrElse(throw new NoSuchMethodException(methodName))
        |    method.setAccessible(true)
        |    method.invoke(obj, args*)
        |
        |  def callConstructor(className: String, paramTypesNames: Array[String], args: Array[Object]): Any =
        |    val classLoader = getClass.getClassLoader
        |    val clazz = classLoader.loadClass(className)
        |    val constructor = clazz.getConstructors
        |      .find { c => c.getParameterTypes.map(_.getName).toSeq == paramTypesNames.toSeq }
        |      .getOrElse(throw new NoSuchMethodException(s"new $$className"))
        |    constructor.setAccessible(true)
        |    constructor.newInstance(args*)
        |
        |  def getField(obj: Any, className: String, fieldName: String): Any =
        |    val clazz = getClass.getClassLoader.loadClass(className)
        |    val field = clazz.getDeclaredField(fieldName)
        |    field.setAccessible(true)
        |    field.get(obj)
        |
        |  private def setField(obj: Any, className: String, fieldName: String, value: Any): Unit =
        |    val clazz = getClass.getClassLoader.loadClass(className)
        |    val field = clazz.getDeclaredField(fieldName)
        |    field.setAccessible(true)
        |    field.set(obj, value)
        |
        |  private def getOuter(obj: Any, outerTypeName: String): Any =
        |    val clazz = obj.getClass
        |    val field = getSuperclassIterator(clazz)
        |      .flatMap(_.getDeclaredFields)
        |      .find { field => field.getName == "$$outer" && field.getType.getName == outerTypeName }
        |      .getOrElse(throw new NoSuchFieldException("$$outer"))
        |    field.setAccessible(true)
        |    field.get(obj)
        |
        |  private def getStaticObject(className: String): Any =
        |    val clazz = getClass.getClassLoader.loadClass(className)
        |    val field = clazz.getDeclaredField("MODULE$$")
        |    field.setAccessible(true)
        |    field.get(null)
        |
        |  private def getSuperclassIterator(clazz: Class[?]): Iterator[Class[?]] =
        |    Iterator.iterate(clazz)(_.getSuperclass).takeWhile(_ != null)
        |
        |  // a fake method that is used between the extract-expression and the resolve-reflect-eval phases, 
        |  // which transforms them to calls of one of the methods defined above.
        |  private def reflectEval(qualifier: Object, term: String, args: Array[Object]): Any = ???
        |""".stripMargin

  override def run(using Context): Unit =
    val inserter = Inserter(parseExpression, parseEvaluationClass)
    ctx.compilationUnit.untpdTree =
      inserter.transform(ctx.compilationUnit.untpdTree)

  class Inserter(expression: Tree, expressionClass: Seq[Tree])
      extends UntypedTreeMap:
    override def transform(tree: Tree)(using Context): Tree =
      tree match
        case tree: PackageDef =>
          val transformed = super.transform(tree).asInstanceOf[PackageDef]
          if expressionInserted then
            // set to `false` to prevent inserting `Expression` class in other `PackageDef`s
            expressionInserted = false
            cpy.PackageDef(transformed)(
              transformed.pid,
              transformed.stats ++ expressionClass.map(
                _.withSpan(tree.span)
              )
            )
          else transformed
        case tree @ DefDef(name, paramss, tpt, rhs)
            if rhs != EmptyTree && isOnBreakpoint(tree) =>
          cpy.DefDef(tree)(
            name,
            paramss,
            tpt,
            mkExprBlock(expression, tree.rhs)
          )
        case tree @ ValDef(name, tpt, _) if isOnBreakpoint(tree) =>
          cpy.ValDef(tree)(name, tpt, mkExprBlock(expression, tree.rhs))
        case tree: (Ident | Select | GenericApply | Literal | This | New |
              InterpolatedString | OpTree | Tuple) if isOnBreakpoint(tree) =>
          mkExprBlock(expression, tree)
        case tree => super.transform(tree)

  private def parseExpression(using Context): Tree =
    val wrappedExpression =
      s"""|object Expression:
          |  {
          |    ${evalCtx.expression}
          |  }
          |""".stripMargin
    val expressionFile = SourceFile.virtual("<expression>", evalCtx.expression)
    val wrappedExpressionFile = new VirtualFile(
      "<wrapped-expression>",
      wrappedExpression.getBytes(StandardCharsets.UTF_8)
    )
    val sourceFile =
      new SourceFile(wrappedExpressionFile, scala.io.Codec.UTF8):
        override def start: Int = -27
        override def underlying: SourceFile = expressionFile
        override def atSpan(span: Span): SourcePosition =
          if (span.exists) SourcePosition(this, span)
          else NoSourcePosition

    parse(sourceFile)
      .asInstanceOf[PackageDef]
      .stats
      .head
      .asInstanceOf[ModuleDef]
      .impl
      .body
      .head

  private def parseEvaluationClass(using Context): Seq[Tree] =
    val sourceFile =
      SourceFile.virtual("<evaluation class>", evaluationClassSource)
    parse(sourceFile).asInstanceOf[PackageDef].stats

  private def parse(sourceFile: SourceFile)(using Context): Tree =
    val newCtx = ctx.fresh.setSource(sourceFile)
    val parser = Parsers.Parser(sourceFile)(using newCtx)
    parser.parse()

  private def isOnBreakpoint(tree: Tree)(using Context): Boolean =
    val startLine =
      if tree.span.exists then tree.sourcePos.startLine + 1 else -1
    startLine == evalCtx.breakpointLine

  private def mkExprBlock(expr: Tree, tree: Tree)(using
      Context
  ): Block =
    if expressionInserted then
      // TODO replace with warning
      throw new Exception("expression already inserted")
    expressionInserted = true
    val valDef = ValDef(evalCtx.expressionTermName, TypeTree(), expr)
    Block(List(valDef), tree)

object InsertExpression:
  val name: String = "insert-expression"
