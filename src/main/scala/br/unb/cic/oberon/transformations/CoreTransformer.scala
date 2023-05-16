package br.unb.cic.oberon.transformations

import br.unb.cic.oberon.ir.ast._
import br.unb.cic.oberon.visitor.OberonVisitorAdapter
import scala.collection.mutable.ListBuffer
import br.unb.cic.oberon.ir.ast.Procedure

class CoreVisitor() extends OberonVisitorAdapter {
  override type T = Statement
  
  var addedVariables: List[VariableDeclaration] = Nil

  override def visit(stmt: Statement): Statement = stmt match {
    case SequenceStmt(stmts) =>
      SequenceStmt(flatSequenceOfStatements(SequenceStmt(transformListStmts(stmts)).stmts))

    case LoopStmt(stmt) =>
      WhileStmt(BoolValue(true), stmt.accept(this))

    case RepeatUntilStmt(condition, stmt) =>
      WhileStmt(BoolValue(true), SequenceStmt(List(stmt.accept(this), IfElseStmt(condition, ExitStmt(), None))).accept(this))

    case ForStmt(initStmt, condition, block) =>
      SequenceStmt(List(initStmt.accept(this), WhileStmt(condition, block.accept(this))))

    case IfElseIfStmt (condition, thenStmt, elsifStmt, elseStmt) =>
      IfElseStmt (condition, thenStmt.accept(this), Some(transformElsif(elsifStmt, elseStmt)))

    case CaseStmt(exp, cases, elseStmt) => transformCase(exp, cases, elseStmt)

    case WhileStmt(condition, stmt) =>
      WhileStmt(condition, stmt.accept(this))

    case _ => stmt
  }

  private var nextCaseId = 0

  private def getNextCaseId(): Int = {
    val returnId = nextCaseId
    nextCaseId += 1
    returnId
  }

  private def transformCase(exp: Expression, cases: List[CaseAlternative], elseStmt: Option[Statement]): Statement = {
    val coreElseStmt = if (elseStmt.isEmpty) None else Some(elseStmt.get.accept(this))

    // TODO corrigir comportamento para outras expressões

    val caseExpressionId = exp match {
      case VarExpression(name) => name
      case _ => f"case_exp#${getNextCaseId}"
    }
    val caseExpressionEvaluation = AssignmentStmt(VarAssignment(caseExpressionId), exp)

    def casesToIfElseStmt(cases: List[CaseAlternative]): IfElseStmt =
      cases match {
        case SimpleCase(condition, stmt) :: Nil =>
          val newCondition =
            EQExpression(VarExpression(caseExpressionId), condition)
          IfElseStmt(newCondition, stmt.accept(this), coreElseStmt)

        case SimpleCase(condition, stmt) :: tailCases =>
          val newCondition =
            EQExpression(VarExpression(caseExpressionId), condition)
          val newElse = Some(casesToIfElseStmt(tailCases))
          IfElseStmt(newCondition, stmt.accept(this), newElse)

        case RangeCase(min, max, stmt) :: Nil =>
          val newCondition = AndExpression(
            LTEExpression(min, VarExpression(caseExpressionId)),
            LTEExpression(VarExpression(caseExpressionId), max)
          )
          IfElseStmt(newCondition, stmt.accept(this), coreElseStmt)

        case RangeCase(min, max, stmt) :: tailCases =>
          val newCondition = AndExpression(
            LTEExpression(min, VarExpression(caseExpressionId)),
            LTEExpression(VarExpression(caseExpressionId), max)
          )
          val newElse = Some(casesToIfElseStmt(tailCases))
          IfElseStmt(newCondition, stmt.accept(this), newElse)

        case _ => throw new RuntimeException("Invalid CaseStmt without cases")
      }
    
    exp match {
      case VarExpression(_) => casesToIfElseStmt(cases)
      case _ =>
        addedVariables = VariableDeclaration(caseExpressionId, IntegerType) :: addedVariables
        SequenceStmt(List(caseExpressionEvaluation, casesToIfElseStmt(cases)))
    }
  }

  private def transformElsif (elsifStmts: List[ElseIfStmt], elseStmt: Option[Statement]): Statement = {
    val coreElseStmt = if (elseStmt.isEmpty) None else Some(elseStmt.get.accept(this))
    val currentElsif = elsifStmts.head

    if (elsifStmts.tail.isEmpty) {
      IfElseStmt(currentElsif.condition, currentElsif.thenStmt.accept(this), coreElseStmt)
    } else {
      val nextElsif = Some(transformElsif(elsifStmts.tail, coreElseStmt))
      IfElseStmt(currentElsif.condition, currentElsif.thenStmt.accept(this), nextElsif)
    }
  }

  private def transformListStmts(stmtsList: List[Statement], stmtsCore: ListBuffer[Statement] = new ListBuffer[Statement]): List[Statement] = {

    if (!stmtsList.isEmpty){
      stmtsCore += stmtsList.head.accept(this);
      stmtsCore :: transformListStmts(stmtsList.tail, stmtsCore)
    }
    else {
      return Nil
    }

    stmtsCore.toList
  }

  private def transformProcedureListStatement(listProcedures: List[Procedure]): List[Procedure] = {

    var listProceduresCore = ListBuffer[Procedure]()

    for (procedure <- listProcedures){
      addedVariables = Nil
      val coreStmt = procedure.stmt.accept(this)
      listProceduresCore += Procedure(name = procedure.name,
        args = procedure.args,
        returnType = procedure.returnType,
        constants = procedure.constants,
        variables = procedure.variables ++ addedVariables,
        stmt = coreStmt)
    }
    listProceduresCore.toList
  }

  def flatSequenceOfStatements(stmts: List[Statement]): List[Statement] =
    stmts match {
      case SequenceStmt(ss) :: rest => flatSequenceOfStatements(ss) ++ flatSequenceOfStatements(rest)
      case s :: rest => s :: flatSequenceOfStatements(rest)
      case Nil => List()
    }

  def transformModule(module: OberonModule): OberonModule = {
    
    val stmtprocedureList = transformProcedureListStatement(module.procedures)
    val stmtcore = module.stmt.get.accept(this)

     val coreModule = OberonModule(
      name = module.name,
      submodules = module.submodules,
      userTypes = module.userTypes,
      constants = module.constants,
      variables = module.variables ++ addedVariables,
      procedures = stmtprocedureList,
      stmt = Some(stmtcore)
    )

    coreModule
  }

}

object CoreChecker {
  
  def stmtCheck(stmt: Statement): Boolean = {
    stmt match {
      case SequenceStmt(stmts) => stmts.map(s => stmtCheck(s)).foldLeft(true)(_ && _)

      // Laços
      case LoopStmt(stmt) => false
      case RepeatUntilStmt(condition, stmt) => false
      case ForStmt(initStmt, condition, block) => false
      case WhileStmt(_, whileStmt) => stmtCheck(whileStmt)
      
      // Condicionais
      case CaseStmt(exp, cases, elseStmt) => false  
      case IfElseIfStmt (condition, thenStmt, elsifStmt, elseStmt) => false
      case IfElseStmt(_, thenStmt, Some(elseStmt)) => stmtCheck(thenStmt) && stmtCheck(elseStmt)
      
      case _ => true
    }
  }

  def isModuleCore(module: OberonModule): Boolean = {
    stmtCheck(module.stmt.get) && module.procedures.map(p => stmtCheck(p.stmt)).foldLeft(true)(_ && _)
  }
}