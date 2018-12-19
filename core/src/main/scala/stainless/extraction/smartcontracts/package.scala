/* Copyright 2009-2018 EPFL, Lausanne */

package stainless
package extraction

import scala.language.existentials

package object smartcontracts {
  // Annotation used to represent the keyword payable in solidity
  val payableAnnotation = trees.Annotation("solidityPayable", List())

  object trees extends smartcontracts.Trees with oo.ClassSymbols {
    case class Symbols(
      functions: Map[Identifier, FunDef],
      sorts: Map[Identifier, ADTSort],
      classes: Map[Identifier, ClassDef]
    ) extends ClassSymbols with AbstractSymbols

    object printer extends Printer { val trees: smartcontracts.trees.type = smartcontracts.trees }
  }

  class SmartcontractException(tree: inox.ast.Trees#Tree, msg: String)
    extends MissformedStainlessCode(tree, msg)

  object SmartcontractException {
    def apply(tree: inox.ast.Trees#Tree, msg: String) = new SmartcontractException(tree, msg)
  }

  val contractID = "stainless.smartcontracts.Contract"

  def isIdentifier(name: String, id: Identifier) = id match {
    case ast.SymbolIdentifier(`name`) => true
    case _ => false
  }

  def extractor(implicit ctx: inox.Context) = {
    val lowering = ExtractionPipeline(new CheckingTransformer {
      override val s: trees.type = trees
      override val t: methods.trees.type = methods.trees
    })

    utils.DebugPipeline("HavocInjection", HavocInjection()) andThen
    utils.DebugPipeline("SmartContractInvariant", SmartContractInvariant()) andThen
    utils.DebugPipeline("EnvironmentBuilder", EnvironmentBuilder()) andThen
    lowering
  }

}
