/* Copyright 2009-2018 EPFL, Lausanne */

package stainless

class DottyExtractionSuite extends ExtractionSuite {

  testExtractAll("verification/valid")
  testExtractAll("verification/invalid")
  testExtractAll("verification/unchecked")

  testExtractAll("imperative/valid",
    "imperative/valid/Blocks1.scala",
  )
  testExtractAll("imperative/invalid")

  testExtractAll("termination/valid")
  testExtractAll("termination/looping")

  testExtractAll("extraction/valid",
    "extraction/valid/AccessorFlags.scala",
    "extraction/valid/GhostCaseClass.scala",
    "extraction/valid/GhostMethods.scala",
    "extraction/valid/GhostEffect3.scala",
    "extraction/valid/GhostFlow1.scala",
    "extraction/valid/GhostFlow2.scala",
    "extraction/valid/GhostFlow3.scala",
    "extraction/valid/Typedef.scala", // FIXME
  )
  testRejectAll("extraction/invalid",
    "extraction/invalid/TypeMember.scala",
    "extraction/invalid/Println.scala",
    "extraction/invalid/CtorParams.scala",
    "extraction/invalid/ClassBody.scala",
    "extraction/invalid/Require.scala",
    "extraction/invalid/GhostEffect3.scala",
    "extraction/invalid/GhostPatmat.scala",
    "extraction/invalid/GhostDafny.scala",
    "extraction/invalid/SuperAbstract.scala",

    "extraction/invalid/TraitVar1.scala", // FIXME
  )

  testExtractAll("dotty-specific/valid")
  testRejectAll("dotty-specific/invalid")

}

