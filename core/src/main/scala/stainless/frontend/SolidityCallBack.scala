package stainless
package frontend

import extraction.xlang.{trees => xt, TreeSanitizer, SmartContractsSanitizer}
import scala.concurrent.Future
import java.nio.file

import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet, ListBuffer}

import scala.language.existentials

class SolidityCallBack(implicit val context: inox.Context)
  extends CallBack { self =>
  val trees = xt
  val files = MutableSet[String]()
  val allClasses = ListBuffer[xt.ClassDef]()
  val allFunctions = ListBuffer[xt.FunDef]()
  val allTypeDefs = ListBuffer[xt.TypeDef]()

  override def apply(
    file: String,
    unit: xt.UnitDef,
    classes: Seq[xt.ClassDef],
    functions: Seq[xt.FunDef],
    typeDefs: Seq[xt.TypeDef]
  ): Unit = {
    synchronized {
      if (unit.isMain) {
        files += file
      }
      allClasses ++= classes
      allFunctions ++= functions
      allTypeDefs ++= typeDefs
    }
  }

  def beginExtractions() = {}

  // We start the compilation at the end of the extraction to ensure that
  // we have all the dependencies stored in the registry
  final override def endExtractions(): Unit = {
    context.reporter.info("Begin Compilation")
    val symbols = xt.NoSymbols.withClasses(allClasses).withFunctions(allFunctions)

    symbols.ensureWellFormed
    TreeSanitizer(xt).check(symbols)
    SmartContractsSanitizer(xt).check(symbols)

    files.foreach { file =>
      solidity.SolidityOutput(file)(symbols, context)
    }

    context.reporter.info("Compilation Done")
  }

  def failed() = {}
  def getReport: Option[AbstractReport[_]] = {
    if (!files.isEmpty) Some(new NoReport())
    else None
  }
  def join() = {}
  def stop() = {}
}