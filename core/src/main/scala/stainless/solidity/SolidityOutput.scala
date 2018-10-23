/* Copyright 2009-2018 EPFL, Lausanne */
package stainless
package solidity

import scala.concurrent.Future
import scala.language.existentials
import scala.reflect.runtime.{universe => u}

import inox.utils.Position

import extraction._
import extraction.xlang.{trees => xt}

import java.io.File


trait SolidityOutput {
  import xt._
  import exprOps._

  val filename: String
  implicit val symbols: xt.Symbols
  val ctx: inox.Context

  val classes = getClassesIn(filename)
  val functions = getFunctionsIn(filename)

  val solFilename = scalaToSolName(filename)

  private final val contractInterfaceID = "stainless.smartcontracts.ContractInterface"
  private final val contractID = "stainless.smartcontracts.Contract"

  val enumParents = classes.filter { cd =>
    cd.children.forall((ccd: ClassDef) => ccd.isCaseObject && ccd.parents.size == 1) &&
    cd.children.size > 0
    cd.parents.size == 0
  }
  val enumChildren = enumParents.flatMap(cd => cd.children)
  val enumTypeMap = enumChildren.map(cd => cd.typed(symbols).toType -> cd.parents.head).toMap

  val enums = enumParents.map(cd =>
    SEnumDefinition(cd.id.toString, cd.children.map(_.id.toString))
  ).toSeq

  def isIdentifier(name: String, id: Identifier) = id match {
    case ast.SymbolIdentifier(`name`) => true
    case _ => false
  }

  def isSolidityNumericType(expr: Expr) = expr.getType(symbols) match {
    case BVType(false, _) => true
    // case BVType(true, _) => true // signed types are not yet supported
    case _ => false
  }

  def transformFlags(flags: Seq[Flag]) = {
    def process(l: Seq[Flag]): Seq[SFlag] = l match {
      case Nil => Nil
      case Private :: xs => SPrivate() +: process(xs)
      case x :: xs if x == IsPure =>
        // FIXME: this warning doesn't show up for some reason
        ctx.reporter.warning("The @pure annotation is ignored by the compiler to Solidity. Use @solidityPure instead.")
        process(xs)
      case Payable :: xs  => SPayable() +: process(xs)
      case Annotation("solidityPure", _) :: xs  => SPure() +: process(xs)
      case Annotation("solidityView", _) :: xs  => SView() +: process(xs)
      case x :: xs => process(xs)
    }

    process(flags)
  }

  def transformType(tpe: Type): SolidityType = {
    tpe match {
      case IntegerType() =>
        ctx.reporter.warning("The BigInt type was translated to int256 during compilation. Overflows might occur.")
        SIntType(256)
      case BooleanType() => SBooleanType()
      case StringType() => SStringType()
      case Int32Type() => SIntType(32)
      case UnitType() => SUnitType()
      case BVType(false, size) => SUIntType(size)
      case BVType(true, size) => SIntType(size)
      case ClassType(id, Seq(tp1, tp2)) if isIdentifier("stainless.smartcontracts.Mapping", id) =>
        SMapping(transformType(tp1), transformType(tp2))
      case ClassType(id, Seq(tp)) if isIdentifier("stainless.collection.List", id) =>
        SArrayType(transformType(tp))
      case ClassType(id, Seq()) if isIdentifier("stainless.smartcontracts.Address", id) =>
        SAddressType()
      case ct:ClassType if enumTypeMap.isDefinedAt(ct) => SEnumType(enumTypeMap(ct).toString)
      case ClassType(id, Seq()) => SContractType(id.toString)

      case _ =>  ctx.reporter.fatalError("Unsupported type " + tpe + " at position " + tpe.getPos + " " + tpe.getPos.file)
    }
  }

  def transformFields(cd: ClassDef) = {
    val accessors: Seq[FunDef] = cd.methods.map(symbols.functions).filter(_.isAccessor)
    val setters: Seq[FunDef] = accessors.filter(fd => fd.id.name.endsWith("_=") && fd.params.size == 1)
    val getters: Seq[FunDef] = accessors.filter(fd => fd.params.size == 0)

    // every getter in the contract gets to be a field of the Solidity contract
    getters.map { (fd: FunDef) =>
      val name = fd.id.name
      // a field is variable (non-constant) if there exists a setter for it
      val isVar = setters.exists { fd2 => fd2.id.name == name + "_=" }
      SParamDef(isVar, name, transformType(fd.returnType))
    }
  }

  def insertReturns(expr: Expr, funRetType: Type): Expr = {
    def rec(expr: Expr): Expr = expr match {
      case Let(v, d, rest) => Let(v, d, rec(rest))
      case LetVar(v, d, rest) => LetVar(v, d, rec(rest))
      case Block(es, rest) => Block(es, rec(rest))
      case IfExpr(c, thenn, elze) => IfExpr(c, rec(thenn), rec(elze))
      case MatchExpr(scrut, cses) => MatchExpr(scrut, cses.map { case MatchCase(x,y,rhs) => MatchCase(x, y, rec(rhs)) })
      case Assert(x, y, body) => Assert(x, y, rec(body))
      case e if e.getType == funRetType => Return(e)
      case e => e
    }

    rec(expr)
  }

  def transformExpr(expr: Expr): SolidityExpr = expr match {
    // Transform call to field addr of a contract
    case MethodInvocation(rcv, id, _, args) if isIdentifier("stainless.smartcontracts.ContractInterface.addr", id) =>
      rcv match {
        case This(_) => SAddress(SVariable("this"))
        case ClassSelector(_, selector) => SAddress(SVariable(selector.name))
      }

    // Transform call to field transfer of an address
    case MethodInvocation(rcv, id, _, Seq(amount)) if isIdentifier("stainless.smartcontracts.Address.transfer", id) =>
      STransfer(transformExpr(rcv), transformExpr(amount))

    // Calls to selfdestruct
    case MethodInvocation(rcv, id, _, Seq(receiver)) if isIdentifier("stainless.smartcontracts.ContractInterface.selfdestruct", id) =>
      SSelfDestruct(transformExpr(receiver))

    // Desugar call to method balance on class address
    case MethodInvocation(rcv, id, _, _) if isIdentifier("stainless.smartcontracts.Address.balance", id) =>
      val srcv = transformExpr(rcv)
      SClassSelector(srcv, "balance")

    case MethodInvocation(rcv, id, _, args) if isIdentifier("stainless.smartcontracts.Mapping.apply", id) =>
      val Seq(arg) = args
      val newArg = transformExpr(arg)
      val newRcv = transformExpr(rcv)
      SMappingRef(newRcv, newArg)

    case MethodInvocation(rcv, id, _, args) if isIdentifier("stainless.smartcontracts.Mapping.update", id) =>
      val Seq(key, value) = args
      val newKey = transformExpr(key)
      val newVal = transformExpr(value)
      val newRcv = transformExpr(rcv)

      SAssignment(SMappingRef(newRcv, newKey), newVal)

    case MethodInvocation(rcv, id, _, Seq(arg)) if isSolidityNumericType(rcv) =>
      val tpe = rcv.getType(symbols)
      val newRcv = transformExpr(rcv)
      val newArg = transformExpr(arg)

      id match {
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$greater", i) => SGreaterThan(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$greater$eq", i) => SGreaterEquals(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$less", i) => SLessThan(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$less$eq", i) => SLessEquals(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$minus", i) => SMinus(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$plus", i) => SPlus(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$times", i) => SMult(newRcv, newArg)
        case i if isIdentifier("stainless.smartcontracts." + tpe + ".$div", i) => SDivision(newRcv, newArg)
        case _ =>
          ctx.reporter.fatalError(rcv.getPos, "Unknown operator: " + id.name)
      }

    // Converting calls to accessors to a class selector
    case MethodInvocation(rcv, id, _, Seq()) if
        symbols.functions.contains(id) &&
        symbols.functions(id).flags.exists{case IsAccessor(_) => true case _ => false} =>
      val srcv = transformExpr(rcv)

      SClassSelector(srcv, id.name)

    // Converting calls to setters to an assignment
    case MethodInvocation(rcv, id, _, Seq(v)) if
        symbols.functions.contains(id) &&
        symbols.functions(id).flags.exists{case IsAccessor(_) => true case _ => false} =>
      val srcv = transformExpr(rcv)
      val sv = transformExpr(v)

      assert(id.name.endsWith("_="), "Internal error in Solidity Compiler, setters' names must end with '_='")

      SAssignment(SClassSelector(srcv, id.name.dropRight(2)), sv)

    case MethodInvocation(rcv, id, _, args) if symbols.functions.contains(id) =>
      val srcv = transformExpr(rcv)
      val newArgs = args.map(transformExpr)
                        .zip(symbols.functions(id).params)
                        .filterNot{ case (a,p) => p.flags.contains(Ghost)}
                        .map(_._1)

      SMethodInvocation(srcv, id.name, newArgs, None)

    case FunctionInvocation(id, _, args) if isIdentifier("stainless.smartcontracts.get", id) =>
      val Seq(array,index) = args
      val newArray = transformExpr(array)
      val newIndex = transformExpr(index)
      SArrayRef(newArray, newIndex)

    case FunctionInvocation(id, _, args) if isIdentifier("stainless.smartcontracts.length", id) =>
      val Seq(array) = args
      val newArray = transformExpr(array)
      SArrayLength(newArray)

    case fi@FunctionInvocation(id, _, args) if isIdentifier("stainless.smartcontracts.dynRequire", id) =>
      val Seq(cond:Expr) = args
      SRequire(transformExpr(cond), "error")

    case fi@FunctionInvocation(id, _, args) if isIdentifier("stainless.smartcontracts.dynAssert", id) =>
      val Seq(cond:Expr) = args
      SAssert(transformExpr(cond), "error")

    // Desugar pay function
    case fi@FunctionInvocation(id, _, args) if isIdentifier("stainless.smartcontracts.pay",id) =>
      val Seq(m:MethodInvocation, amount: Expr) = args
      if(!symbols.functions(m.id).isPayable) {
        ctx.reporter.fatalError(fi.getPos, "The method pay can only be used on a payable function.")
      }

      transformExpr(m) match {
        case SMethodInvocation(rcv, method, args, _) =>
          SMethodInvocation(rcv, method, args, Some(transformExpr(amount)))
        case _ =>
          ctx.reporter.fatalError(fi.getPos, "The compiler to Solidity should only return SMethodInvocation's for this invocation.")
      }

    // Desugar call to the function 'address' of the library
    case fi@FunctionInvocation(id, _, Seq(arg)) if isIdentifier("stainless.smartcontracts.address", id) =>
      SAddress(transformExpr(arg))

    // Desugar call to the function 'now' of the library
    case FunctionInvocation(id, _, _) if isIdentifier("stainless.smartcontracts.now", id) =>
      SNow()

    // Desugar call to the field sender on the variable msg
    case FunctionInvocation(id, _, _) if isIdentifier("stainless.smartcontracts.Msg.sender", id) =>
      SClassSelector(SVariable("msg"), "sender")

    // Desugar call to the field value on the variable msg
    case FunctionInvocation(id, _, _) if isIdentifier("stainless.smartcontracts.Msg.value", id) =>
      SClassSelector(SVariable("msg"), "value")

    case FunctionInvocation(id, _, Seq(lhs, rhs)) if isIdentifier("stainless.smartcontracts.unsafe_$plus", id) =>
      SPlus(transformExpr(lhs), transformExpr(rhs))

    case FunctionInvocation(id, _, Seq(lhs, rhs)) if isIdentifier("stainless.smartcontracts.unsafe_$minus", id) =>
      SMinus(transformExpr(lhs), transformExpr(rhs))

    case FunctionInvocation(id, _, Seq(lhs, rhs)) if isIdentifier("stainless.smartcontracts.unsafe_$times", id) =>
      SMult(transformExpr(lhs), transformExpr(rhs))

    case FunctionInvocation(id, _, Seq(lhs, rhs)) if isIdentifier("stainless.smartcontracts.unsafe_$div", id) =>
      SDivision(transformExpr(lhs), transformExpr(rhs))

    case FunctionInvocation(id, _, _) if isIdentifier("stainless.lang.ghost", id) =>
      STerminal()

    case FunctionInvocation(id, _, _) if isIdentifier("stainless.smartcontracts.unsafeIgnoreCode", id) =>
      STerminal()

    case FunctionInvocation(id, _, args) =>
      assert(symbols.functions.contains(id), "Symbols do not contain the function: " + id)
      val f = symbols.functions(id)
      val name = f.solidityLibraryName match {
        case Some(lib) => lib + "." + id.name
        case None => id.name
      }
      val newArgs = args.map(transformExpr)
                        .zip(symbols.functions(id).params)
                        .filterNot{ case (a,p) => p.flags.contains(Ghost)}
                        .map(_._1)
      SFunctionInvocation(name, newArgs)

    case Block(exprs, last) => SBlock(exprs.map(transformExpr),
                    transformExpr(last))

    case This(tpe) => SThis()

    case Super(tpe) => SSuper()

    case FieldAssignment(obj, sel, expr) =>
      SFieldAssignment(transformExpr(obj),
              sel.name,
              transformExpr(expr))

    case ClassConstructor(tpe, args) if isIdentifier("stainless.smartcontracts.Address", tpe.id) =>
      val Seq(x) = args
      SAddress(transformExpr(x))

    case ClassConstructor(tpe, args) if(enumTypeMap.isDefinedAt(tpe)) =>
      val id = enumTypeMap(tpe)
      SEnumValue(id.toString, tpe.toString)

    case ClassConstructor(tpe, args) =>
      val newArgs = args.map(transformExpr)
      SClassConstructor(transformType(tpe), newArgs)

    case ClassSelector(expr, id) =>
      val se = transformExpr(expr)
      SClassSelector(se, id.name)

    case While(cond, body, pred) => SWhile(transformExpr(cond), transformExpr(body))

    case UnitLiteral() => STerminal()

    case BooleanLiteral(b) => SLiteral(b.toString)
    case b@BVLiteral(false, _, _) => SLiteral(b.toBigInt.toString)
    case IntegerLiteral(b) => SLiteral(b.toString)

    case Variable(id, _, _) => SVariable(id.name)

    case Let(vd, _, body) if vd.flags.contains(Ghost) =>
      transformExpr(body)

    case Let(vd, value, body) =>
      val p = SParamDef(true, vd.id.name, transformType(vd.tpe))
      val v = transformExpr(value)
      val b = transformExpr(body)
      SLet(p,v,b)

    case Assignment(variable, expr) => SAssignment(transformExpr(variable), transformExpr(expr))

    case And(exprs) => SAnd(exprs.map(transformExpr))
    case Or(exprs) => SOr(exprs.map(transformExpr))
    case Not(expr) => SNot(transformExpr(expr))
    case Equals(l,r) => SEquals(transformExpr(l), transformExpr(r))
    case GreaterThan(l,r) => SGreaterThan(transformExpr(l), transformExpr(r))
    case GreaterEquals(l,r) => SGreaterEquals(transformExpr(l), transformExpr(r))
    case LessThan(l,r) => SLessThan(transformExpr(l), transformExpr(r))
    case LessEquals(l,r) => SLessEquals(transformExpr(l), transformExpr(r))

    case IfExpr(cond, thenn, elze) =>
      SIfExpr(transformExpr(cond),
          transformExpr(thenn),
          transformExpr(elze))

    case Plus(l, r) => SPlus(transformExpr(l),transformExpr(r))
    case Minus(l, r) => SMinus(transformExpr(l),transformExpr(r))
    case Division(l, r) => SDivision(transformExpr(l),transformExpr(r))
    case Times(l, r) => SMult(transformExpr(l),transformExpr(r))
    case Remainder(l, r) => SRemainder(transformExpr(l), transformExpr(r))

    case LetVar(vd, value, body) =>
      val p = SParamDef(false, vd.id.name, transformType(vd.tpe))
      val v = transformExpr(value)
      val b = transformExpr(body)
      SLet(p,v,b)

    case Assert(_,_,body) => transformExpr(body)
    case Choose(_,_) => STerminal()
    case Return(e) => SReturn(transformExpr(e))

    // Recursive Functions
    case LetRec(fds, _) => ctx.reporter.fatalError("The compiler to Solidity does not support locally defined recursive functions:\n" + fds.head)

    // Unsupported by default
    case e => ctx.reporter.fatalError("The compiler to Solidity does not support this expression : " + e + "(" + e.getClass + ")")
  }

  def transformAbstractMethods(fd: FunDef) = {
    val newParams = fd.params.map(p => SParamDef(false, p.id.name, transformType(p.tpe)))
    val rteType = transformType(fd.returnType)
    val sflags = transformFlags(fd.flags)
    SAbstractFunDef(fd.id.name, newParams, rteType, sflags)
  }

  def transformMethod(fd: FunDef) = {
    val name = if(fd.id.name == "fallback") ""
            else fd.id.name

    val newParams = fd.params
                      .filterNot(_.flags.contains(Ghost))
                      .map(p => SParamDef(false, p.id.name, transformType(p.tpe)))

    val rteType = transformType(fd.returnType)
    val sflags = transformFlags(fd.flags)

    val bodyWithoutSpec = exprOps.withoutSpecs(fd.fullBody)

    val pre = exprOps.preconditionOf(fd.fullBody)

    if (pre.isDefined) {
      ctx.reporter.warning("Ignoring require(" + pre.get.asString(new PrinterOptions()) + ").")
      ctx.reporter.warning("Replace `require` with `dynRequire` if you want the require to remain in the compiled code.\n")
    }

    if(bodyWithoutSpec.isDefined) {
      val body1 = if(fd.returnType != UnitType()) insertReturns(bodyWithoutSpec.get, fd.returnType)
                  else bodyWithoutSpec.get
      val body2 = transformExpr(body1)
      SFunDef(name, newParams, rteType, body2, sflags)
    } else {
      SFunDef(name, newParams, rteType, STerminal(), sflags)
    }
  }

  def functionShouldBeDiscarded(fd: FunDef) = {
    val id = fd.id
    val name = id.name

    if(name.startsWith("copy")) {
      ctx.reporter.warning("Ignoring a method named `copy*` (you can safely ignore this warning if you have no such method).")
      true
    } else if (name == "constructor") {
      true
    } else if (name == "$init") {
      ctx.reporter.warning("Ignoring a method named `$init` (you can safely ignore this warning if you have no such method).")
      true
    } else {
      fd.isAccessor || fd.isField
    }
  }


  def transformConstructor(cd: ClassDef) = {
    val constructors = cd.methods(symbols).filter { _.name == "constructor" }

    if (constructors.size > 1)
      ctx.reporter.fatalError("There can be only one constructor for contract " + cd.id.name + ".")

    if (constructors.isEmpty)
      SConstructorDef(Seq(), STerminal())
    else {
      val fd = symbols.functions(constructors.head)
      if(fd.returnType != UnitType()) {
        ctx.reporter.fatalError(s"The constructor must have unit type, not ${fd.returnType}.")
      }

      val classFieldsName = cd.fields.map(_.id.name).toSet
      val SFunDef(_, params, _, body, _) = transformMethod(fd)
      SConstructorDef(params, body)
    }
  }

  def transformInterface(cd: ClassDef) = {
    ctx.reporter.info("Compiling Interface : " + cd.id.name + " in file " + solFilename)
    val methods = cd.methods(symbols)
                    .map(symbols.functions)
                    .filterNot(functionShouldBeDiscarded)
                    .filter(fd => !fd.flags.contains(xt.IsInvariant) &&
                                  !isIdentifier("stainless.smartcontracts.ContractInterface.addr", fd.id))
                    .map(transformAbstractMethods)

    SContractInterface(cd.id.name, Seq(), methods)
  }

  def transformContract(cd: ClassDef) = {
    ctx.reporter.info("Compiling Contract : " + cd.id.name + " in file " + solFilename)

    val parents = cd.parents.filterNot(cd =>
      isIdentifier(contractID, cd.id) ||
      isIdentifier(contractInterfaceID, cd.id)
    ).map(_.toString)

    val fields = transformFields(cd)
    val methods = cd.methods(symbols).map(symbols.functions).filterNot(functionShouldBeDiscarded)

    val newMethods = methods.filter(fd => !fd.flags.contains(xt.IsInvariant) &&
                                          !fd.flags.contains(xt.Ghost) &&
                                          !isIdentifier("stainless.smartcontracts.ContractInterface.addr", fd.id))
                              .map(transformMethod)

    val constructor = transformConstructor(cd)
    SContractDefinition(cd.id.name, parents, constructor, Seq.empty, enums, fields, newMethods)
  }

  def transformLibraries(fds: Seq[FunDef]): Seq[SLibrary] = {
    fds.groupBy(_.solidityLibraryName).toSeq.collect {
      case (Some(name), funs) => SLibrary(name, funs.map(transformMethod))
    }
  }

  def getClassesIn(filename: String) = {
    symbols.classes.values.toSeq.filter { cd => cd.getPos.file.getCanonicalPath == filename }
  }

  def getFunctionsIn(filename: String) = {
    symbols.functions.values.toSeq.filter { fd => fd.getPos.file.getCanonicalPath == filename }
  }

  def collectInterfaces(cds: Seq[ClassDef]) = {
    cds.filter { cd =>
      val ancestors = cd.ancestors
      ancestors.exists{ case p => isIdentifier(contractInterfaceID, p.id) } &&
      !ancestors.exists{ case p => isIdentifier(contractID, p.id) }
    }
  }

  def collectContracts(cds: Seq[ClassDef]) = {
    cds.filter { cd =>
      cd.ancestors.exists{ case p => isIdentifier(contractID, p.id) }
    }
  }

  def collectLibraries(fds: Seq[FunDef]) = {
    fds.filter(_.isSolidityLibrary)
  }

  def hasSmartContractCode(filename: String): Boolean = {

    val classes = getClassesIn(filename)
    val functions = getFunctionsIn(filename)

    val interfaces = collectInterfaces(classes)
    val contracts = collectContracts(classes)
    val libraries = collectLibraries(functions)

    !interfaces.isEmpty || !contracts.isEmpty || !libraries.isEmpty
  }

  def isSmartContractLibrary(f: File): Boolean = {
    f.getName == "package.scala" &&
    f.getParentFile.getName == "smartcontracts" &&
    f.getParentFile.getParentFile.getName == "stainless"
  }

  def fileDependencies(filename: String): Set[String] = {
    val idToFile: Map[Identifier, File] =
      symbols.classes.values.map(cd => cd.id -> cd.getPos.file).toMap ++
      symbols.functions.values.map(fd => fd.id -> fd.getPos.file).toMap

    val idsInFile = idToFile.collect {
      case (id, file) if file.getCanonicalPath == filename => id
    }

    val idDependencies = idsInFile.flatMap(symbols.dependencies)
    val allFileDependencies = idDependencies.toSet.map(idToFile)

    allFileDependencies
      .filterNot(isSmartContractLibrary)
      .map(_.getCanonicalPath)
      .filterNot(_.startsWith(System.getProperty("java.io.tmpdir")))
      .filterNot(_ == filename)
      .filter(hasSmartContractCode)
      .map(scalaToSolName)
  }

  val interfaces = collectInterfaces(classes).map(transformInterface)
  val contracts = collectContracts(classes).map(transformContract)
  val libraries = transformLibraries(functions.filterNot(functionShouldBeDiscarded))

  val allDefs = interfaces ++ contracts ++ libraries

  def writeFile() = {
    ctx.reporter.info("Compiling file: " + filename)

    val transformedImports = fileDependencies(filename).map(SolidityImport(_))
    if(!transformedImports.isEmpty) {
      ctx.reporter.info("The following imports have been found :")
      transformedImports.foreach( i => ctx.reporter.info(i.path))
    }

    if(!allDefs.isEmpty)
      SolidityPrinter.writeFile(ctx, solFilename, transformedImports, allDefs)
    else {
      ctx.reporter.warning("The file " + filename + " has been discarded since it does not contain smart contract code")
    }
  }
}

object SolidityOutput {
  def apply(name: String)(implicit syms: xt.Symbols, context: inox.Context) = {
    val output = new {
      override val filename = name
      override val symbols = syms
      override val ctx = context
    } with SolidityOutput
    output.writeFile()
  }
}