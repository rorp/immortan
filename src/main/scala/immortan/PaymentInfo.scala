package immortan

import java.util.Date
import scala.util.{Success, Failure, Try}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{DATA_CLOSING, HasNormalCommitments}
import fr.acinq.eclair.wire.{FullPaymentTag, PaymentTagTlv}
import immortan.ChannelMaster.TxConfirmedAtOpt
import immortan.crypto.Tools
import immortan.crypto.Tools.{Fiat2Btc, SEPARATOR}
import immortan.fsm.{IncomingPaymentProcessor, SendMultiPart, SplitInfo}
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.{LNUrl, PayRequestMeta, PaymentRequestExt, AES}
import org.bouncycastle.util.encoders.Base64
import scodec.bits.ByteVector
import spray.json._

object PaymentInfo {
  final val NO_ACTION = "no-action"

  sealed trait PaymentSendable
  case object Sendable extends PaymentSendable
  case object NotSendableInFlight extends PaymentSendable
  case object NotSendableSuccess extends PaymentSendable
}

object PaymentStatus {
  final val SUCCEEDED = 3
  final val ABORTED = 2
  final val PENDING = 1
  final val INIT = 0
}

case class SemanticOrder(id: String, order: Long)

case class RBFParams(ofTxid: ByteVector32, mode: Long)

case class SplitParams(
    prExt: PaymentRequestExt,
    action: Option[PaymentAction],
    description: PaymentDescription,
    cmd: SendMultiPart,
    chainFee: MilliSatoshi
)

object SemanticOrder {
  type SemanticGroup = Seq[TransactionDetails]

  private def orderIdOrBaseId(details: TransactionDetails) =
    details.description.semanticOrder.map(_.id).getOrElse(details.identity)

  private def orderOrMaxValue(details: TransactionDetails) =
    details.description.semanticOrder.map(_.order).getOrElse(Long.MaxValue)

  private def collapseChildren(items: SemanticGroup) = {
    items.tail.foreach(_.isExpandedItem = false)
    items.head.isExpandedItem = true
    items
  }

  def makeSemanticOrder(items: SemanticGroup): SemanticGroup =
    items.distinct
      .groupBy(orderIdOrBaseId)
      .view
      .mapValues(_ sortBy orderOrMaxValue)
      .mapValues(collapseChildren)
      .values
      .toList
      .sortBy(_.head.seenAt)(Ordering[Long].reverse)
      .flatten
}

sealed trait TransactionDescription {
  val semanticOrder: Option[SemanticOrder]
  val label: Option[String]
}

object EmptyTransactionDescription extends TransactionDescription {
  val semanticOrder: Option[SemanticOrder] = None
  val label: Option[String] = None
}

sealed trait TransactionDetails {
  var isExpandedItem: Boolean = true
  // We order items on UI by when they were first seen
  // We hide items depending on when they were updated
  def updatedAt: Long
  def seenAt: Long

  val date: Date = new Date(updatedAt)
  val description: TransactionDescription
  val identity: String
}

case class LNUrlDescription(
    label: Option[String],
    semanticOrder: Option[SemanticOrder],
    privKey: String,
    lastHash: ByteVector32,
    lastSecret: ByteVector32,
    lastMsat: MilliSatoshi
) extends TransactionDescription {

  lazy val fullTag: FullPaymentTag =
    FullPaymentTag(lastHash, lastSecret, PaymentTagTlv.LOCALLY_SENT)
}

case class LNUrlPayLink(
    domain: String,
    payString: String,
    payMetaString: String,
    updatedAt: Long,
    description: LNUrlDescription,
    lastNodeIdString: String,
    lastCommentString: String
) extends TransactionDetails {
  override val seenAt: Long = updatedAt
  override val identity: String = payString
  lazy val payLink: Option[LNUrl] = Try(payString).map(LNUrl.apply).toOption
  lazy val payMetaData: Try[PayRequestMeta] =
    Try(payMetaString.parseJson.asInstanceOf[JsArray].elements)
      .map(PayRequestMeta)
  lazy val imageBytes: Option[Array[Byte]] =
    payMetaData.toOption.flatMap(_.imageBase64).map(Base64.decode)
  lazy val lastComment: Option[String] =
    Option(lastCommentString).filter(_.nonEmpty)
}

case class DelayedRefunds(
    txToParent: Map[Transaction, TxConfirmedAtOpt] = Map.empty
) extends TransactionDetails {
  val totalAmount: MilliSatoshi =
    txToParent.keys.flatMap(_.txOut).map(_.amount).sum.toMilliSatoshi
  override val description: TransactionDescription = EmptyTransactionDescription
  override val updatedAt: Long = System.currentTimeMillis * 2
  override val seenAt: Long = System.currentTimeMillis * 2
  override val identity: String = "DelayedRefunds"
}

// Payment descriptions
case class PaymentDescription(
    split: Option[SplitInfo],
    label: Option[String],
    semanticOrder: Option[SemanticOrder],
    invoiceText: String,
    proofTxid: Option[String] = None,
    meta: Option[String] = None,
    holdPeriodSec: Option[Long] = None,
    toSelfPreimage: Option[ByteVector32] = None
) extends TransactionDescription {

  val queryText: String = invoiceText + SEPARATOR + label.getOrElse(
    new String
  ) + SEPARATOR + meta.getOrElse(new String)

  val externalInfo: Option[String] =
    Some(meta.getOrElse(invoiceText)).find(_.nonEmpty)
}

case class PaymentInfo(
    prString: String,
    preimage: ByteVector32,
    status: Int,
    seenAt: Long,
    updatedAt: Long,
    description: PaymentDescription,
    actionString: String,
    paymentHash: ByteVector32,
    paymentSecret: ByteVector32,
    received: MilliSatoshi,
    sent: MilliSatoshi,
    fee: MilliSatoshi,
    balanceSnapshot: MilliSatoshi,
    fiatRatesString: String,
    chainFee: MilliSatoshi,
    incoming: Long
) extends TransactionDetails {
  override val identity: String = prString
  lazy val isIncoming: Boolean = 1 == incoming
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)
  lazy val prExt: PaymentRequestExt = PaymentRequestExt.fromUri(prString)
  lazy val action: Option[PaymentAction] =
    if (actionString == PaymentInfo.NO_ACTION) None
    else Some(to[PaymentAction](actionString))
  lazy val fullTag: FullPaymentTag = FullPaymentTag(
    paymentHash,
    paymentSecret,
    if (isIncoming) PaymentTagTlv.FINAL_INCOMING else PaymentTagTlv.LOCALLY_SENT
  )
  def isActivelyHolding(fsm: IncomingPaymentProcessor): Boolean =
    fsm.state == IncomingPaymentProcessor.Receiving && prExt.isEnough(
      fsm.lastAmountIn
    ) && fsm.isHolding
  def ratio(fsm: IncomingPaymentProcessor): Long =
    Tools.ratio(received, fsm.lastAmountIn).toLong
}

// Payment actions
sealed trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
  def printable(preimage: ByteVector32): String
}

case class MessageAction(domain: Option[String], message: String)
    extends PaymentAction {
  val finalMessage = s"<br>${message take 144}"
  def printable(preimage: ByteVector32): String = message
}

case class UrlAction(domain: Option[String], description: String, url: String)
    extends PaymentAction {
  require(
    domain.map(_.toLowerCase).forall(url.toLowerCase.contains),
    "Payment action domain mismatch"
  )
  def trimmedDescription: String = description.trim().stripSuffix(":")
  val finalMessage =
    s"<br>${trimmedDescription take 144}<br><br><tt>$url</tt><br>"
  def printable(preimage: ByteVector32): String = s"$trimmedDescription: $url"
}

case class AESAction(
    domain: Option[String],
    description: String,
    ciphertext: String,
    iv: String
) extends PaymentAction {
  def trimmedDescription: String = description.trim().stripSuffix(":")
  val finalMessage = s"<br>${trimmedDescription take 144}"
  def plaintext(preimage: ByteVector32): String = {
    val ct = ByteVector
      .fromValidBase64(ciphertext)
      .take(1024 * 4)
      .toArray // up to ~2kb of encrypted data

    Try(
      AES
        .decode(
          data = ct,
          key = preimage.toArray,
          initVector =
            ByteVector.fromValidBase64(iv).take(24).toArray // 16 bytes
        )
    ) match {
      case Success(decrypted) =>
        decrypted.decodeUtf8.getOrElse(s"decrypted bytes: ${decrypted.toHex}")
      case Failure(_) => "<failed to decrypt payload>"
    }
  }
  def printable(preimage: ByteVector32): String =
    s"$trimmedDescription: ${plaintext(preimage)}"
}

// Relayed preimages
case class RelayedPreimageInfo(
    paymentHashString: String,
    paymentSecretString: String,
    preimageString: String,
    relayed: MilliSatoshi,
    earned: MilliSatoshi,
    seenAt: Long,
    updatedAt: Long
) extends TransactionDetails {
  override val identity: String = paymentHashString + paymentSecretString
  override val description: TransactionDescription = EmptyTransactionDescription
  lazy val preimage: ByteVector32 = ByteVector32.fromValidHex(preimageString)
  lazy val paymentHash: ByteVector32 =
    ByteVector32.fromValidHex(paymentHashString)
  lazy val paymentSecret: ByteVector32 =
    ByteVector32.fromValidHex(paymentSecretString)
  lazy val fullTag: FullPaymentTag =
    FullPaymentTag(paymentHash, paymentSecret, PaymentTagTlv.TRAMPLOINE_ROUTED)
}

// Tx descriptions
case class TxInfo(
    txString: String,
    txidString: String,
    pubKeyString: String,
    depth: Long,
    receivedSat: Satoshi,
    sentSat: Satoshi,
    feeSat: Satoshi,
    seenAt: Long,
    updatedAt: Long,
    description: TxDescription,
    balanceSnapshot: MilliSatoshi,
    fiatRatesString: String,
    incoming: Long,
    doubleSpent: Long
) extends TransactionDetails {
  override val identity: String = txidString
  lazy val isIncoming: Boolean = 1L == incoming
  lazy val isDoubleSpent: Boolean = 1L == doubleSpent
  lazy val isConfirmed: Boolean = depth >= 1
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)
  lazy val pubKey: PublicKey = PublicKey(ByteVector fromValidHex pubKeyString)
  lazy val txid: ByteVector32 = ByteVector32.fromValidHex(txidString)
  lazy val tx: Transaction = Transaction.read(txString)
}

sealed trait TxDescription extends TransactionDescription {
  def canBeCPFPd: Boolean = cpfpBy.isEmpty && cpfpOf.isEmpty
  def withNewOrderCond(order: Option[SemanticOrder] = None): TxDescription
  def withNewLabel(label1: Option[String] = None): TxDescription
  def withNewCPFPBy(txid: ByteVector32): TxDescription

  def queryText(txid: ByteVector32): String
  def withNodeId: Option[PublicKey] = None
  def toAddress: Option[String] = None

  val cpfpBy: Option[ByteVector32]
  val cpfpOf: Option[ByteVector32]
  val rbf: Option[RBFParams]
}

case class PlainTxDescription(
    addresses: List[String],
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends TxDescription { me =>
  override lazy val toAddress: Option[String] =
    if (addresses.size > 1) None else addresses.headOption
  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + addresses.mkString(SEPARATOR) + SEPARATOR + label
      .getOrElse(new String)
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
}

case class OpReturnTxDescription(
    preimages: List[ByteVector32],
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends TxDescription { me =>
  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + preimages
      .map(_.toHex)
      .mkString(SEPARATOR) + SEPARATOR + label.getOrElse(new String)
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
  override def canBeCPFPd: Boolean = false
}

sealed trait ChanTxDescription extends TxDescription {
  override def withNodeId: Option[PublicKey] = Some(nodeId)
  def nodeId: PublicKey
}

case class ChanFundingTxDescription(
    nodeId: PublicKey,
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends ChanTxDescription { me =>
  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + nodeId.toString + SEPARATOR + label.getOrElse(
      new String
    )
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
  override def canBeCPFPd: Boolean = false
}

case class ChanRefundingTxDescription(
    nodeId: PublicKey,
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends ChanTxDescription { me =>
  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + nodeId.toString + SEPARATOR + label.getOrElse(
      new String
    )
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
}

case class HtlcClaimTxDescription(
    nodeId: PublicKey,
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends ChanTxDescription { me =>

  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + nodeId.toString + SEPARATOR + label.getOrElse(
      new String
    )
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
}

case class PenaltyTxDescription(
    nodeId: PublicKey,
    label: Option[String] = None,
    semanticOrder: Option[SemanticOrder] = None,
    cpfpBy: Option[ByteVector32] = None,
    cpfpOf: Option[ByteVector32] = None,
    rbf: Option[RBFParams] = None
) extends ChanTxDescription { me =>
  override def queryText(txid: ByteVector32): String =
    txid.toHex + SEPARATOR + nodeId.toString + SEPARATOR + label.getOrElse(
      new String
    )
  override def withNewOrderCond(
      order: Option[SemanticOrder] = None
  ): TxDescription =
    if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription =
    copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription =
    copy(cpfpBy = Some(txid))
}

object TxDescription {
  final val RBF_CANCEL = 1
  final val RBF_BOOST = 2

  def define(
      chans: Iterable[Channel],
      walletAddresses: List[String],
      tx: Transaction
  ): TxDescription =
    defineOpeningRelation(chans, tx) orElse defineClosingRelation(
      chans,
      tx
    ) getOrElse PlainTxDescription(walletAddresses)

  def defineOpeningRelation(
      chans: Iterable[Channel],
      tx: Transaction
  ): Option[TxDescription] = chans.map(_.data).collectFirst {
    case some: HasNormalCommitments
        if some.commitments.commitInput.outPoint.txid == tx.txid =>
      ChanFundingTxDescription(some.commitments.remoteInfo.nodeId)
  }

  def defineClosingRelation(
      chans: Iterable[Channel],
      tx: Transaction
  ): Option[TxDescription] = chans.map(_.data).collectFirst {
    case closing: DATA_CLOSING
        if closing.balanceRefunds.exists(_.txid == tx.txid) =>
      ChanRefundingTxDescription(closing.commitments.remoteInfo.nodeId)
    case closing: DATA_CLOSING
        if closing.paymentLeftoverRefunds.exists(_.txid == tx.txid) =>
      HtlcClaimTxDescription(closing.commitments.remoteInfo.nodeId)
    case closing: DATA_CLOSING
        if closing.revokedCommitPublished
          .flatMap(_.penaltyTxs)
          .exists(_.txid == tx.txid) =>
      PenaltyTxDescription(closing.commitments.remoteInfo.nodeId)
    case some: HasNormalCommitments
        if tx.txIn.exists(
          _.outPoint.txid == some.commitments.commitInput.outPoint.txid
        ) =>
      ChanRefundingTxDescription(some.commitments.remoteInfo.nodeId)
  }
}
