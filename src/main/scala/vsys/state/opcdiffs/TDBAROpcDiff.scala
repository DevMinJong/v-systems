package vsys.state.opcdiffs

import com.google.common.primitives.{Bytes, Longs, Ints}
import com.wavesplatform.state2._
import scorex.transaction.ValidationError
import scorex.transaction.ValidationError.GenericError
import vsys.contract.{DataEntry, DataType}
import vsys.contract.ExecutionContext

import scala.util.{Left, Right}

object TDBAROpcDiff {

  // for tokenAccountBalance DB
  def balance(context: ExecutionContext)(address: DataEntry, tokenIndex: DataEntry,
                                         dataStack: Seq[DataEntry], pointer: Byte): Either[ValidationError, Seq[DataEntry]] = {
    if (tokenIndex.dataType != DataType.Int32 || address.dataType != DataType.Address) {
      Left(GenericError("Input contains invalid dataType"))
    } else if (pointer > dataStack.length || pointer < 0) {
      Left(GenericError("Out of data range"))
    } else {
      val contractTokens = context.state.contractTokens(context.contractId.bytes)
      val tokenNumber = Ints.fromByteArray(tokenIndex.data)
      val tokenID: ByteStr = ByteStr(Bytes.concat(context.contractId.bytes.arr, tokenIndex.data))
      val tokenBalanceKey = ByteStr(Bytes.concat(tokenID.arr, address.data))
      if (tokenNumber >= contractTokens || tokenNumber < 0) {
        Left(GenericError(s"Token $tokenNumber not exist"))
      } else {
        val b = context.state.tokenAccountBalance(tokenBalanceKey)
        Right(dataStack.patch(pointer, Seq(DataEntry(Longs.toByteArray(b), DataType.Amount)), 1))
      }
    }
  }


  object TDBARType extends Enumeration {
    val BalanceTBDAR= Value(1)
  }

  def parseBytes(context: ExecutionContext)
                (bytes: Array[Byte], data: Seq[DataEntry]): Either[ValidationError, Seq[DataEntry]] = bytes.head match {
    case opcType: Byte if opcType == TDBARType.BalanceTBDAR.id && checkInput(bytes.slice(0, bytes.length - 1), 3, context.stateVar.length, data.length, 1) =>
      balance(context)(data(bytes(1)), data(bytes(2)), data, bytes(3))
    case _ => Left(GenericError("Wrong TDBAR opcode"))
  }

  private def checkInput(bytes: Array[Byte], bLength: Int, stateVarLength: Int, dataLength: Int, sep: Int): Boolean = {
    bytes.length == bLength && bytes.slice(1, sep).forall(_ < stateVarLength) && bytes.slice(sep, bLength).forall(_ < dataLength) && bytes.tail.min >= 0
  }

}
