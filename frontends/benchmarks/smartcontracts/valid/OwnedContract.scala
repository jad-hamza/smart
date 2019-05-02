import stainless.smartcontracts._
import stainless.annotation._
import stainless.lang._

trait OwnedContract extends Contract {
  var owner: PayableAddress

  @solidityPayable
  @solidityPublic
  def sendBalance() = {
    require(
      !(addr.equals(Msg.sender)) &&
      addr.balance == Uint256("20")
    )

    if(Msg.sender == owner) {
      owner.transfer(addr.balance)
    }
  } ensuring { _ =>
    ((Msg.sender.equals(owner)) ==> (addr.balance == Uint256.ZERO)) &&
    (!(Msg.sender.equals(owner)) ==> (addr.balance == Uint256("20")))
  }
}
