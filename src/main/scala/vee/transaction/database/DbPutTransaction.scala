package vee.transaction.database

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.wavesplatform.state2.ByteStr
import play.api.libs.json.{JsObject, Json}
import scorex.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import vee.database.{DataType, Entry}
import scorex.serialization.{BytesSerializable, Deser}
import scorex.transaction.TransactionParser.{KeyLength, TransactionType}
import scorex.transaction.ValidationError.DbDataTypeError
import scorex.transaction.{AssetId, SignedTransaction, ValidationError}

import scala.util.{Failure, Success, Try}

case class DbPutTransaction private(sender: PublicKeyAccount,
                                    dbKey: String,
                                    entry: Entry,
                                    fee: Long,
                                    feeScale: Short,
                                    timestamp: Long,
                                    signature: ByteStr)
  extends SignedTransaction {

  override val transactionType: TransactionType.Value = TransactionType.DbPutTransaction

  lazy val toSign: Array[Byte] = Bytes.concat(
    Array(transactionType.id.toByte),
    sender.publicKey,
    BytesSerializable.arrayWithSize(Deser.serilizeString(dbKey)),
    BytesSerializable.arrayWithSize(entry.bytes.arr),
    Longs.toByteArray(fee),
    Shorts.toByteArray(feeScale),
    Longs.toByteArray(timestamp))

  override lazy val json: JsObject = jsonBase() ++ Json.obj(
    "dbKey" -> dbKey,
    "entry" -> entry.json,
    "fee" -> fee,
    "feeScale" -> feeScale,
    "timestamp" -> timestamp
  )

  lazy val storageKey: ByteStr = DbPutTransaction.generateKey(sender.toAddress, dbKey)
  override val assetFee: (Option[AssetId], Long, Short) = (None, fee, feeScale)
  override lazy val bytes: Array[Byte] = Bytes.concat(toSign, signature.arr)

}

object DbPutTransaction {

  val MaxDbKeyLength = 30
  val MinDbKeyLength = 1

  def generateKey(owner: Address, key: String):ByteStr =
    ByteStr(owner.bytes.arr ++ Deser.serilizeString(key))

  def parseTail(bytes: Array[Byte]): Try[DbPutTransaction] = Try {
    import EllipticCurveImpl._
    val sender = PublicKeyAccount(bytes.slice(0, KeyLength))
    val (nameBytes, nameEnd) = Deser.parseArraySize(bytes, KeyLength)
    val (dbEntryBytes, dbEntryEnd) = Deser.parseArraySize(bytes, nameEnd)
    (for {
      dbEntry <- Entry.fromBytes(dbEntryBytes)
      fee = Longs.fromByteArray(bytes.slice(dbEntryEnd, dbEntryEnd + 8))
      feeScale = Shorts.fromByteArray(bytes.slice(dbEntryEnd + 8, dbEntryEnd + 10))
      timestamp = Longs.fromByteArray(bytes.slice(dbEntryEnd + 10, dbEntryEnd + 18))
      signature = ByteStr(bytes.slice(dbEntryEnd + 18, dbEntryEnd + 18 + SignatureLength))
      tx <- DbPutTransaction.create(sender, Deser.deserilizeString(nameBytes), dbEntry, fee, feeScale, timestamp, signature)
    } yield tx).fold(left => Failure(new Exception(left.toString)), right => Success(right))
  }.flatten

  def create(sender: PublicKeyAccount,
             dbKey: String,
             dbEntry: Entry,
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             signature: ByteStr): Either[ValidationError, DbPutTransaction] =
    if (dbKey.length > MaxDbKeyLength || dbKey.length < MinDbKeyLength) {
      Left(ValidationError.InvalidDbKey)
    } else if (!Deser.validUTF8(dbKey)) {
      Left(ValidationError.InvalidUTF8String("dbKey"))
    } else if(fee <= 0) {
      Left(ValidationError.InsufficientFee)
    } else if (feeScale != 100) {
      Left(ValidationError.WrongFeeScale(feeScale))
    } else {
      Right(DbPutTransaction(sender, dbKey, dbEntry, fee, feeScale, timestamp, signature))
    }

  def create(sender: PublicKeyAccount,
             dbKey: String,
             dbDataType: String,
             dbData: String,
             fee: Long,
             feeScale: Short,
             timestamp: Long,
             signature: ByteStr): Either[ValidationError, DbPutTransaction] =
    for {
      datatype <- DataType.values.find(_.toString == dbDataType) match {
          case Some(x) => Right(x)
          case None =>Left(DbDataTypeError(dbDataType))
      }
      dbEntry <- Entry.buildEntry(dbData, datatype)
      tx <- create(sender, dbKey, dbEntry, fee, feeScale, timestamp, signature)
    } yield tx

  def create(sender: PrivateKeyAccount,
             dbKey: String,
             dbEntry: Entry,
             fee: Long,
             feeScale: Short,
             timestamp: Long): Either[ValidationError, DbPutTransaction] = {
    create(sender, dbKey, dbEntry, fee, feeScale, timestamp, ByteStr.empty).right.map { unsigned =>
      unsigned.copy(signature = ByteStr(EllipticCurveImpl.sign(sender, unsigned.toSign)))
    }
  }

  def create(sender: PrivateKeyAccount,
             dbKey: String,
             dbDataType: String,
             dbData: String,
             fee: Long,
             feeScale: Short,
             timestamp: Long): Either[ValidationError, DbPutTransaction] = {
    create(sender, dbKey, dbDataType, dbData, fee, feeScale, timestamp, ByteStr.empty).right.map { unsigned =>
      unsigned.copy(signature = ByteStr(EllipticCurveImpl.sign(sender, unsigned.toSign)))
    }
  }
}
