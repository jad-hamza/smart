/* Copyright 2009-2019 EPFL, Lausanne */

package stainless
package annotation

import scala.annotation.meta._
import scala.annotation.{Annotation, StaticAnnotation}

/** The annotated symbols is not extracted at all. For internal usage only. */
class ignore extends Annotation


/** The annotated function or class' methods are not verified
  * by default (use --functions=... to override this). */
@ignore
class library extends Annotation

/** Apply the "induct" tactic during verification of the annotated function. */
@ignore
class induct extends Annotation

/** Apply the "traceInduct" tactic during verification of the annotated function. */
@ignore
class traceInduct(fun: String) extends Annotation

/** Only extract the contracts and replace the annotated function's body with a choose. */
@ignore @field @getter @setter @param
class extern extends Annotation

/** Don't unfold the function's body during verification. */
@ignore
class opaque extends Annotation

/** Denotes the annotated method as an invariant of its class */
@ignore
class invariant extends Annotation

/** Specify that the annotated function is pure, which will be checked. */
@ignore @field @getter @setter @param
class pure extends Annotation

/** Disable overflow checks for sized integer arithmetic operations within the annotated function. */
@ignore
class wrapping extends Annotation

/** Inline this function, but only once.
  * This might be useful if one wants to eg. inline a recursive function.
  * Note: A recursive function will not be inlined within itself. */
@ignore
class inlineOnce extends Annotation

/** Add an @inline flag to the invariant generated for this class. */
@ignore
class inlineInvariant extends Annotation

/** Instruct Stainless to partially evaluate calls to the annotated function. */
@ignore
class partialEval extends Annotation

/** Smart contract annotation
  * A function declared payable can receive ether */
@ignore
class solidityPayable extends Annotation

/** Annotation for smart contracts verification. Annotated a call to a contract
  * with `@ignoreReentrancy` to ask Stainless to ignore potential reentrancy
  * issues on that particular call. */
@ignore
class ignoreReentrancy extends Annotation

/** Annotation for the compiler to Solidity, translated to "pure" in Solidity.
  * A function with the solidityPure annotation should not read or write from
  * the smart contract state. This is not checked by Stainless.  */
@ignore
class solidityPure extends Annotation

/** Annotation for the compiler to Solidity, translated to "view" in Solidity.
  * A function with the view annotation should not write from the smart
  * contract state (but can read it). This is not checked by Stainless. */
@ignore
class solidityView extends Annotation

/** Annotation for the compiler to Solidity.
  * An object annotated with @solidityLibrary("LibName") will be compiled to 
  * a library called `LibName` in Solidity */
@ignore
class solidityLibrary(name: String) extends Annotation

/** Annotation for the compiler to Solidity, used for "public" functions in Solidity. */
@ignore
class solidityPublic extends Annotation

/** Annotation for the compiler to Solidity, used for "public" functions in Solidity. */
@ignore
class solidityPrivate extends Annotation

/** Mark this function as expressing a law that must be satisfied
  * by instances or subclasses of the enclosing class.
  */
@ignore
class law extends Annotation

/** Used to mark non-sealed classes that must be considered mutable.
  * Can also be used to mark a type parameter T to announce that it can be
  * instantiated with mutable types
  */
@ignore
class mutable extends Annotation

/** Can be used to mark a library function/class/object so that it is not
  * filtered out by the keep objects. Use the command-line option `--keep=g` to
  * keep all objects marked by `@keep(g)`
  */
@ignore
class keep(g: String) extends Annotation

/**
 * Code annotated with @ghost is removed after stainless extraction.
 *
 * Code that can be annotated with @ghost: classes, method and value definitions, method parameters.
 *
 * See the Stainless specification for details.
 */
@ignore @field @getter @setter @param
class ghost extends StaticAnnotation

@ignore
class erasable extends Annotation

@ignore
class indexedAt(n: BigInt) extends Annotation
