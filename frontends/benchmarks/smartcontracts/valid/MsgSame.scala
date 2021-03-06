import stainless.smartcontracts._
import stainless.annotation._

trait MsgSame extends Contract {
  @solidityPublic
  @solidityView
  final def f() = g(Msg.sender)

  @solidityPrivate
  @solidityView
  final def g(a: Address) = {
    dynRequire(a == Msg.sender)

  }
}
