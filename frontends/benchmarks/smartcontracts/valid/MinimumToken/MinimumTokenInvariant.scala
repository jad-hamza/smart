import stainless.smartcontracts._
import stainless.lang._
import stainless.collection._
import stainless.annotation._
import stainless.equations._

@wrapping // disable --strict-arithmetic checks inside the object
object MinimumTokenInvariant {
  def distinctAddresses(l: List[Address]): Boolean = l match {
    case Nil() => true
    case Cons(a, as) => (!as.contains(a)) && distinctAddresses(as)
  }

  def sumBalances(addresses: List[Address], balances: MutableMap[Address, Uint256]): Uint256 = addresses match {
    case Nil() => Uint256.ZERO
    case Cons(x,xs) => balances(x) + sumBalances(xs, balances)
  }

  def nonZeroContained(
    participants: List[Address],
    balanceOf: MutableMap[Address, Uint256],
    a: Address
  ): Boolean = {
    participants.contains(a) || balanceOf(a) == Uint256.ZERO
  }

  @opaque
  def balancesUnchangedLemma(
    to: Address,
    newBalance: Uint256,
    participants: List[Address],
    balances: MutableMap[Address, Uint256]
  ): Unit = {
    require(
      !participants.contains(to) &&
      distinctAddresses(participants)
    )

    val b1 = balances.updated(to, newBalance)

    participants match {
      case Nil() =>
        ()
      case Cons(x,xs) =>
        balancesUnchangedLemma(to, newBalance, xs, balances)
    }

  } ensuring(_ => {
    val b1 = balances.updated(to, newBalance)
    sumBalances(participants, balances) == sumBalances(participants, b1)
  })

  @opaque
  def balancesUpdatedLemma(
    participants: List[Address],
    balances: MutableMap[Address, Uint256],
    to: Address,
    newBalance: Uint256
  ): Unit = {
    require(
      participants.contains(to) &&
      distinctAddresses(participants)
    )

    val b1 = balances.updated(to, newBalance)

    participants match {
      case Cons(x, xs) if (x == to) =>
        (
          sumBalances(participants, b1)                     ==:| trivial |:
          sumBalances(xs, b1) + b1(x)                       ==:| balancesUnchangedLemma(to, newBalance, xs, balances) |:
          sumBalances(xs, balances) + b1(x)                 ==:| trivial |:
          sumBalances(xs, balances) + newBalance            ==:| trivial |:
          sumBalances(participants, balances) - balances(to) + newBalance
        ).qed
      case Cons(x, xs) =>
        (
          sumBalances(participants, b1)                                        ==:| trivial |:
          sumBalances(xs, b1) + b1(x)                                          ==:| balancesUpdatedLemma(xs, balances, to, newBalance) |:
          sumBalances(xs, balances) - balances(to) + newBalance + b1(x)        ==:| (b1(x) == balances(x)) |:
          sumBalances(xs, balances) + balances(x) - balances(to) + newBalance  ==:| trivial |:
          sumBalances(participants, balances) - balances(to) + newBalance
        ).qed
    }

  } ensuring(_ => {
    val b1 = balances.updated(to, newBalance)
    sumBalances(participants, b1) == sumBalances(participants, balances) - balances(to) + newBalance
  })

  // Proof that the sum of balances is maintained after two updates
  @ghost
  @opaque
  def transferProof(
    @ghost b0: MutableMap[Address,Uint256],
    @ghost b1: MutableMap[Address,Uint256],
    @ghost b2: MutableMap[Address,Uint256],
    @ghost from: Address,
    @ghost to: Address,
    @ghost amount: Uint256,
    @ghost participants: List[Address],
    @ghost total: Uint256
  ): Unit = {
    require(
      distinctAddresses(participants) &&
      sumBalances(participants, b0) == total &&
      b1 == b0.updated(from, b0(from) - amount) &&
      b2 == b1.updated(to, b1(to) + amount) &&
      participants.contains(from) &&
      participants.contains(to)
    )

    (
      sumBalances(participants, b2)                                             ==:| balancesUpdatedLemma(participants, b1, to, b1(to) + amount) |:
      sumBalances(participants, b1) - b1(to) + (b1(to) + amount)                       ==:| trivial |:
      sumBalances(participants, b1) + amount                                           ==:|
        balancesUpdatedLemma(participants, b0, from, b0(from) - amount)
        //  &&
        // sumBalances(participants, b1) == sumBalances(participants, b0) - b0(from) + (b0(from) - amount))
        |:
      sumBalances(participants, b0) - b0(from) + (b0(from) - amount) + amount         ==:| ((b0(from) - amount) + amount == b0(from)) |:
      sumBalances(participants, b0) - b0(from) + b0(from)                             ==:| trivial |:
      sumBalances(participants, b0)                                                   ==:| trivial |:
      total
    ).qed
  } ensuring( _ => sumBalances(participants, b2) == total)
}
