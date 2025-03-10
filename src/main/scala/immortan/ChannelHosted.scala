package immortan

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.{ByteVector64, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.electrum.CurrentBlockCount
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.HashToPreimage
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import immortan.Channel._
import immortan.ErrorCodes._
import immortan.crypto.Tools._
import immortan.fsm.PreimageCheck
import scodec.bits.ByteVector

object ChannelHosted {
  def make(
      initListeners: Set[ChannelListener],
      hostedData: HostedCommits,
      bag: ChannelBag
  ): ChannelHosted = new ChannelHosted {
    def SEND(msgs: LightningMessage*): Unit = CommsTower.sendMany(
      msgs.map(LightningMessageCodecs.prepareNormal),
      hostedData.remoteInfo.nodeSpecificPair
    )
    def STORE(hostedData: PersistentChannelData): PersistentChannelData =
      bag.put(hostedData)
    listeners = initListeners
    doProcess(hostedData)
  }

  def restoreCommits(
      localLCSS: LastCrossSignedState,
      remoteInfo: RemoteNodeInfo
  ): HostedCommits = {
    val inFlightHtlcs = localLCSS.incomingHtlcs.map(
      IncomingHtlc
    ) ++ localLCSS.outgoingHtlcs.map(OutgoingHtlc)
    HostedCommits(
      remoteInfo.safeAlias,
      CommitmentSpec(
        feeratePerKw = FeeratePerKw(0L.sat),
        localLCSS.localBalanceMsat,
        localLCSS.remoteBalanceMsat,
        inFlightHtlcs.toSet
      ),
      localLCSS,
      nextLocalUpdates = Nil,
      nextRemoteUpdates = Nil,
      updateOpt = None,
      postErrorOutgoingResolvedIds = Set.empty,
      localError = None,
      remoteError = None
    )
  }
}

abstract class ChannelHosted extends Channel { me =>
  def isOutOfSync(blockDay: Long): Boolean =
    math.abs(blockDay - LNParams.currentBlockDay) > 1

  def doProcess(change: Any): Unit = (data, change, state) match {
    case (
          wait: WaitRemoteHostedReply,
          CMD_SOCKET_ONLINE,
          Channel.WaitForInit
        ) =>
      me SEND InvokeHostedChannel(
        LNParams.chainHash,
        wait.refundScriptPubKey,
        wait.secret
      )
      BECOME(wait, Channel.WaitForAccept)

    case (
          WaitRemoteHostedReply(remoteInfo, refundScriptPubKey, _),
          init: InitHostedChannel,
          Channel.WaitForAccept
        ) =>
      if (init.initialClientBalanceMsat > init.channelCapacityMsat)
        throw new RuntimeException(
          s"Their init balance for us=${init.initialClientBalanceMsat}, is larger than capacity"
        )
      if (UInt64(100000000L) > init.maxHtlcValueInFlightMsat)
        throw new RuntimeException(
          s"Their max value in-flight=${init.maxHtlcValueInFlightMsat}, is too low"
        )
      if (init.htlcMinimumMsat > 546000L.msat)
        throw new RuntimeException(
          s"Their minimal payment size=${init.htlcMinimumMsat}, is too high"
        )
      if (init.maxAcceptedHtlcs < 1)
        throw new RuntimeException(
          "They can accept too few in-flight payments"
        )

      val lcss = LastCrossSignedState(
        isHost = false,
        refundScriptPubKey,
        init,
        LNParams.currentBlockDay,
        init.initialClientBalanceMsat,
        init.channelCapacityMsat - init.initialClientBalanceMsat,
        localUpdates = 0L,
        remoteUpdates = 0L,
        incomingHtlcs = Nil,
        outgoingHtlcs = Nil,
        localSigOfRemote = ByteVector64.Zeroes,
        remoteSigOfLocal = ByteVector64.Zeroes
      ).withLocalSigOfRemote(remoteInfo.nodeSpecificPrivKey)

      val localHalfSignedHC = ChannelHosted.restoreCommits(lcss, remoteInfo)
      BECOME(
        WaitRemoteHostedStateUpdate(remoteInfo, localHalfSignedHC),
        Channel.WaitForAccept
      )
      SEND(localHalfSignedHC.lastCrossSignedState.stateUpdate)

    case (
          WaitRemoteHostedStateUpdate(_, localHalfSignedHC),
          remoteSU: StateUpdate,
          Channel.WaitForAccept
        ) =>
      val localCompleteLCSS = localHalfSignedHC.lastCrossSignedState
        .copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      val isRightRemoteUpdateNumber =
        localHalfSignedHC.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
      val isRightLocalUpdateNumber =
        localHalfSignedHC.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
      val isRemoteSigOk =
        localCompleteLCSS.verifyRemoteSig(localHalfSignedHC.remoteInfo.nodeId)
      val askBrandingInfo = AskBrandingInfo(localHalfSignedHC.channelId)
      val isBlockDayWrong = isOutOfSync(remoteSU.blockDay)

      if (isBlockDayWrong)
        throw new RuntimeException("Their blockday is wrong")
      if (!isRemoteSigOk)
        throw new RuntimeException("Their signature is wrong")
      if (!isRightLocalUpdateNumber)
        throw new RuntimeException("Their local update number is wrong")
      if (!isRightRemoteUpdateNumber)
        throw new RuntimeException("Their remote update number is wrong")
      StoreBecomeSend(
        localHalfSignedHC.copy(lastCrossSignedState = localCompleteLCSS),
        Channel.Open,
        askBrandingInfo
      )

    case (
          wait: WaitRemoteHostedReply,
          remoteLCSS: LastCrossSignedState,
          Channel.WaitForAccept
        ) =>
      val isLocalSigOk =
        remoteLCSS.verifyRemoteSig(wait.remoteInfo.nodeSpecificPubKey)
      val isRemoteSigOk =
        remoteLCSS.reverse.verifyRemoteSig(wait.remoteInfo.nodeId)
      val hc =
        ChannelHosted.restoreCommits(remoteLCSS.reverse, wait.remoteInfo)
      val askBrandingInfo = AskBrandingInfo(hc.channelId)

      if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
      else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
      else {
        // We have expected InitHostedChannel but got LastCrossSignedState so this channel exists already
        // make sure our signature match and if so then become Channel.Open using host supplied state data
        StoreBecomeSend(
          hc,
          Channel.Open,
          hc.lastCrossSignedState,
          askBrandingInfo
        )
        // Remote LCSS could contain pending incoming
        events.notifyResolvers()
      }

    // CHANNEL IS ESTABLISHED
    case (
          hc: HostedCommits,
          CurrentBlockCount(tip),
          Channel.Open | Channel.Sleeping
        ) =>
      // Keep in mind that we may have many outgoing HTLCs which have the same preimage
      val sentExpired = hc.allOutgoing
        .filter(tip > _.cltvExpiry.underlying)
        .groupBy(_.paymentHash)
      val hasReceivedRevealedExpired =
        hc.revealedFulfills.exists(tip > _.theirAdd.cltvExpiry.underlying)

      if (hasReceivedRevealedExpired) {
        // We have incoming payments for which we have revealed a preimage but they are still unresolved and completely expired
        // unless we have published a preimage on chain we can not prove we have revealed a preimage in time at this point
        // at the very least it makes sense to halt further usage of this potentially malicious channel
        localSuspend(hc, ERR_HOSTED_MANUAL_SUSPEND)
      }

      if (sentExpired.nonEmpty) {
        val checker = new PreimageCheck {
          override def onComplete(hash2preimage: HashToPreimage): Unit = {
            val settledOutgoingHtlcIds: Iterable[Long] =
              sentExpired.values.flatten.map(_.id)
            val (fulfilled, failed) =
              sentExpired.values.flatten.partition(add =>
                hash2preimage contains add.paymentHash
              )
            localSuspend(
              hc.modify(_.postErrorOutgoingResolvedIds)
                .using(_ ++ settledOutgoingHtlcIds),
              ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC
            )
            for (add <- fulfilled)
              events fulfillReceived RemoteFulfill(
                theirPreimage = hash2preimage(add.paymentHash),
                ourAdd = add
              )
            for (add <- failed)
              events addRejectedLocally InPrincipleNotSendable(localAdd = add)
            // There will be no state update
            events.notifyResolvers()
          }
        }

        // Our peer might have published a preimage on chain instead of directly sending it to us
        // if it turns out that preimage is not present on chain at this point we can safely fail an HTLC
        checker process PreimageCheck.CMDStart(
          sentExpired.keySet,
          LNParams.syncParams.phcSyncNodes
        )
      }

    case (hc: HostedCommits, theirAdd: UpdateAddHtlc, Channel.Open)
        if hc.error.isEmpty =>
      val theirAddExt = UpdateAddHtlcExt(theirAdd, hc.remoteInfo)
      BECOME(hc.receiveAdd(theirAdd), Channel.Open)
      events addReceived theirAddExt

    case (hc: HostedCommits, msg: UpdateFailHtlc, Channel.Open)
        if hc.error.isEmpty =>
      receiveHtlcFail(hc, msg, msg.id)
    case (hc: HostedCommits, msg: UpdateFailMalformedHtlc, Channel.Open)
        if hc.error.isEmpty =>
      receiveHtlcFail(hc, msg, msg.id)

    case (
          hc: HostedCommits,
          msg: UpdateFulfillHtlc,
          Channel.Open | Channel.Sleeping
        ) if hc.error.isEmpty =>
      val remoteFulfill = hc.makeRemoteFulfill(msg)
      BECOME(hc.addRemoteProposal(msg), state)
      events fulfillReceived remoteFulfill

    case (
          hc: HostedCommits,
          msg: UpdateFulfillHtlc,
          Channel.Open | Channel.Sleeping
        ) if hc.error.isDefined =>
      // We may get into error state with this HTLC not expired yet so they may fulfill it afterwards
      // This will throw if HTLC has already been settled post-error
      val remoteFulfill = hc.makeRemoteFulfill(msg)
      BECOME(
        hc.modify(_.postErrorOutgoingResolvedIds)
          .using(_ + msg.id)
          .addRemoteProposal(msg),
        state
      )
      events fulfillReceived remoteFulfill
      // There will be no state update
      events.notifyResolvers()

    case (hc: HostedCommits, CMD_SIGN, Channel.Open)
        if (hc.nextLocalUpdates.nonEmpty || hc.resizeProposal.isDefined) && hc.error.isEmpty =>
      val nextLocalLCSS = hc.resizeProposal
        .map(hc.withResize)
        .getOrElse(hc)
        .nextLocalUnsignedLCSS(LNParams.currentBlockDay)
      SEND(
        nextLocalLCSS
          .withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey)
          .stateUpdate
      )

    // First attempt a normal state update, then a resized state update if original signature check fails and we have a pending resize proposal
    case (hc: HostedCommits, remoteSU: StateUpdate, Channel.Open)
        if (remoteSU.localSigOfRemoteLCSS != hc.lastCrossSignedState.remoteSigOfLocal) && hc.error.isEmpty =>
      attemptStateUpdate(remoteSU, hc)

    case (
          hc: HostedCommits,
          cmd: CMD_ADD_HTLC,
          Channel.Open | Channel.Sleeping
        ) =>
      hc.sendAdd(cmd, blockHeight = LNParams.blockCount.get) match {
        case _ if hc.error.isDefined =>
          events addRejectedLocally ChannelNotAbleToSend(cmd.incompleteAdd)
        case _ if state == Channel.Sleeping =>
          events addRejectedLocally ChannelOffline(cmd.incompleteAdd)
        case Left(reason) => events addRejectedLocally reason
        case Right(newHCState ~ updateAddHtlcMsg) => {
          StoreBecomeSend(newHCState, Channel.Open, updateAddHtlcMsg)
          process(CMD_SIGN)
        }
      }

    case (_, cmd: CMD_ADD_HTLC, _) => {
      // Instruct upstream to skip this channel in such a state
      val reason = ChannelNotAbleToSend(cmd.incompleteAdd)
      events addRejectedLocally reason
    }

    // Fulfilling is allowed even in error state
    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FULFILL_HTLC, Channel.Open)
        if hc.nextLocalSpec.findIncomingHtlcById(cmd.theirAdd.id).isDefined =>
      val msg = UpdateFulfillHtlc(hc.channelId, cmd.theirAdd.id, cmd.preimage)
      StoreBecomeSend(hc.addLocalProposal(msg), Channel.Open, msg)

    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FAIL_HTLC, Channel.Open)
        if hc.nextLocalSpec
          .findIncomingHtlcById(cmd.theirAdd.id)
          .isDefined && hc.error.isEmpty =>
      val msg =
        OutgoingPaymentPacket.buildHtlcFailure(cmd, theirAdd = cmd.theirAdd)
      StoreBecomeSend(hc.addLocalProposal(msg), Channel.Open, msg)

    // CMD_SIGN will be sent from ChannelMaster strictly after outgoing FSM sends this command
    case (hc: HostedCommits, cmd: CMD_FAIL_MALFORMED_HTLC, Channel.Open)
        if hc.nextLocalSpec
          .findIncomingHtlcById(cmd.theirAdd.id)
          .isDefined && hc.error.isEmpty =>
      val msg = UpdateFailMalformedHtlc(
        hc.channelId,
        cmd.theirAdd.id,
        cmd.onionHash,
        cmd.failureCode
      )
      StoreBecomeSend(hc.addLocalProposal(msg), Channel.Open, msg)

    case (hc: HostedCommits, CMD_SOCKET_ONLINE, Channel.Sleeping) =>
      val origRefundPubKey = hc.lastCrossSignedState.refundScriptPubKey
      val invokeMsg = InvokeHostedChannel(
        LNParams.chainHash,
        origRefundPubKey,
        ByteVector.empty
      )
      SEND(hc.error getOrElse invokeMsg)

    case (hc: HostedCommits, CMD_SOCKET_OFFLINE, Channel.Open) =>
      BECOME(hc, Channel.Sleeping)

    case (hc: HostedCommits, _: InitHostedChannel, Channel.Sleeping) =>
      SEND(hc.lastCrossSignedState)

    case (
          hc: HostedCommits,
          remoteLCSS: LastCrossSignedState,
          Channel.Sleeping
        ) if hc.error.isEmpty =>
      attemptInitResync(hc, remoteLCSS)

    case (hc: HostedCommits, remoteInfo: RemoteNodeInfo, _)
        if hc.remoteInfo.nodeId == remoteInfo.nodeId =>
      StoreBecomeSend(hc.copy(remoteInfo = remoteInfo.safeAlias), state)

    case (
          hc: HostedCommits,
          update: ChannelUpdate,
          Channel.Open | Channel.Sleeping
        ) if hc.updateOpt.forall(_.core != update.core) && hc.error.isEmpty =>
      val shortIdMatches = hostedShortChanId(
        hc.remoteInfo.nodeSpecificPubKey.value,
        hc.remoteInfo.nodeId.value
      ) == update.shortChannelId
      if (shortIdMatches)
        StoreBecomeSend(hc.copy(updateOpt = Some(update)), state)

    case (
          hc: HostedCommits,
          resize: ResizeChannel,
          Channel.Open | Channel.Sleeping
        ) if hc.resizeProposal.isEmpty && hc.error.isEmpty =>
      // Can happen if we have sent a resize earlier, but then lost channel data and restored from their
      val isLocalSigOk: Boolean =
        resize.verifyClientSig(hc.remoteInfo.nodeSpecificPubKey)
      if (isLocalSigOk)
        StoreBecomeSend(hc.copy(resizeProposal = Some(resize)), state)
      else localSuspend(hc, ERR_HOSTED_INVALID_RESIZE)

    case (
          hc: HostedCommits,
          remoteSO: StateOverride,
          Channel.Open | Channel.Sleeping
        ) if hc.error.isDefined && !hc.overrideProposal.contains(remoteSO) =>
      StoreBecomeSend(hc.copy(overrideProposal = Some(remoteSO)), state)

    case (
          hc: HostedCommits,
          remote: Fail,
          Channel.WaitForAccept | Channel.Open
        ) if hc.remoteError.isEmpty =>
      StoreBecomeSend(
        hc.copy(remoteError = Some(remote)),
        Channel.Open
      )
      throw RemoteErrorException(ErrorExt extractDescription remote)

    case (_, remote: Fail, _) =>
      // Convert remote error to local exception, it will be dealt with upstream
      throw RemoteErrorException(ErrorExt extractDescription remote)

    case (null, wait: WaitRemoteHostedReply, Channel.Initial) =>
      super.become(wait, Channel.WaitForInit)
    case (null, hc: HostedCommits, Channel.Initial) =>
      super.become(hc, Channel.Sleeping)
    case _ =>
  }

  def acceptOverride(): Either[String, Unit] =
    data match {
      case hc: HostedCommits
          if hc.error.isDefined && hc.overrideProposal.isDefined => {
        val remoteSO = hc.overrideProposal.get
        val overriddenLocalBalance =
          hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat
        val completeLocalLCSS = hc.lastCrossSignedState
          .copy(
            incomingHtlcs = Nil,
            outgoingHtlcs = Nil,
            localBalanceMsat = overriddenLocalBalance,
            remoteBalanceMsat = remoteSO.localBalanceMsat,
            localUpdates = remoteSO.remoteUpdates,
            remoteUpdates = remoteSO.localUpdates,
            blockDay = remoteSO.blockDay,
            remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS
          )
          .withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey)

        val isRemoteSigOk =
          completeLocalLCSS.verifyRemoteSig(hc.remoteInfo.nodeId)
        val newHCState =
          ChannelHosted.restoreCommits(completeLocalLCSS, hc.remoteInfo)

        if (completeLocalLCSS.localBalanceMsat < 0L.msat)
          return Left(
            "Override impossible: new local balance is larger than capacity"
          )
        if (remoteSO.localUpdates < hc.lastCrossSignedState.remoteUpdates)
          return Left(
            "Override impossible: new local update number from remote host is wrong"
          )
        if (remoteSO.remoteUpdates < hc.lastCrossSignedState.localUpdates)
          return Left(
            "Override impossible: new remote update number from remote host is wrong"
          )
        if (remoteSO.blockDay < hc.lastCrossSignedState.blockDay)
          return Left(
            "Override impossible: new override blockday from remote host is not acceptable"
          )
        if (!isRemoteSigOk)
          return Left(
            "Override impossible: new override signature from remote host is wrong"
          )

        StoreBecomeSend(newHCState, Channel.Open, completeLocalLCSS.stateUpdate)
        rejectOverriddenOutgoingAdds(hc, newHCState)

        // We may have pending incoming
        events.notifyResolvers()

        Right(())
      }
      case _ => Left("No override proposal available")
    }

  def proposeResize(delta: Satoshi): Either[String, Unit] = data match {
    case hc: HostedCommits if hc.resizeProposal.isEmpty && hc.error.isEmpty => {
      val capacitySat =
        hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat.truncateToSatoshi
      val resize = ResizeChannel(capacitySat + delta)
        .sign(hc.remoteInfo.nodeSpecificPrivKey)
      StoreBecomeSend(hc.copy(resizeProposal = Some(resize)), state, resize)
      process(CMD_SIGN)

      Right(())
    }
    case _ => Left("Channel not in clean state or resize proposal already sent")
  }

  def rejectOverriddenOutgoingAdds(
      old: HostedCommits,
      updated: HostedCommits
  ): Unit =
    for (add <- old.allOutgoing -- updated.allOutgoing)
      events addRejectedLocally InPrincipleNotSendable(add)

  def localSuspend(hc: HostedCommits, errCode: String): Unit = {
    val localError =
      Fail(data = ByteVector.fromValidHex(errCode), channelId = hc.channelId)

    if (hc.localError.isEmpty)
      StoreBecomeSend(
        hc.copy(localError = Some(localError)),
        state,
        localError
      )
  }

  def attemptInitResync(
      hc: HostedCommits,
      remoteLCSS: LastCrossSignedState
  ): Unit = {
    val updated = hc.resizeProposal
      .filter(_ isRemoteResized remoteLCSS)
      .map(hc.withResize)
      .getOrElse(hc) // They may have a resized LCSS
    val weAreEven =
      hc.lastCrossSignedState.remoteUpdates == remoteLCSS.localUpdates && hc.lastCrossSignedState.localUpdates == remoteLCSS.remoteUpdates
    val weAreAhead =
      hc.lastCrossSignedState.remoteUpdates > remoteLCSS.localUpdates || hc.lastCrossSignedState.localUpdates > remoteLCSS.remoteUpdates
    val isLocalSigOk =
      remoteLCSS.verifyRemoteSig(updated.remoteInfo.nodeSpecificPubKey)
    val isRemoteSigOk =
      remoteLCSS.reverse.verifyRemoteSig(updated.remoteInfo.nodeId)

    if (!isRemoteSigOk) localSuspend(updated, ERR_HOSTED_WRONG_REMOTE_SIG)
    else if (!isLocalSigOk) localSuspend(updated, ERR_HOSTED_WRONG_LOCAL_SIG)
    else if (weAreAhead || weAreEven) {
      SEND(
        List(
          hc.lastCrossSignedState
        ) ++ updated.resizeProposal ++ updated.nextLocalUpdates: _*
      )
      // Forget about their unsigned updates, they are expected to resend
      BECOME(updated.copy(nextRemoteUpdates = Nil), Channel.Open)
      // There will be no state update
      events.notifyResolvers()
    } else {
      val localUpdatesAcked =
        remoteLCSS.remoteUpdates - updated.lastCrossSignedState.localUpdates
      val remoteUpdatesAcked =
        remoteLCSS.localUpdates - updated.lastCrossSignedState.remoteUpdates

      val remoteUpdatesAccounted =
        updated.nextRemoteUpdates take remoteUpdatesAcked.toInt
      val localUpdatesAccounted =
        updated.nextLocalUpdates take localUpdatesAcked.toInt
      val localUpdatesLeftover =
        updated.nextLocalUpdates drop localUpdatesAcked.toInt

      val updated2 = updated.copy(
        nextLocalUpdates = localUpdatesAccounted,
        nextRemoteUpdates = remoteUpdatesAccounted
      )
      val syncedLCSS = updated2
        .nextLocalUnsignedLCSS(remoteLCSS.blockDay)
        .copy(
          localSigOfRemote = remoteLCSS.remoteSigOfLocal,
          remoteSigOfLocal = remoteLCSS.localSigOfRemote
        )

      if (syncedLCSS.reverse == remoteLCSS) {
        // We have fallen behind a bit but have all the data required to successfully synchronize such that an updated state is reached
        val updated3 = updated2.copy(
          lastCrossSignedState = syncedLCSS,
          localSpec = updated2.nextLocalSpec,
          nextLocalUpdates = localUpdatesLeftover,
          nextRemoteUpdates = Nil
        )
        StoreBecomeSend(
          updated3,
          Channel.Open,
          List(
            syncedLCSS
          ) ++ updated2.resizeProposal ++ localUpdatesLeftover: _*
        )
      } else {
        // We are too far behind, restore from their future data, nothing else to do
        val newHCState =
          ChannelHosted.restoreCommits(remoteLCSS.reverse, updated2.remoteInfo)
        StoreBecomeSend(newHCState, Channel.Open, remoteLCSS.reverse)
        rejectOverriddenOutgoingAdds(updated, newHCState)
      }

      // There will be no state update
      events.notifyResolvers()
    }
  }

  def attemptStateUpdate(remoteSU: StateUpdate, hc: HostedCommits): Unit = {
    val lcssNew = hc
      .nextLocalUnsignedLCSS(remoteSU.blockDay)
      .copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      .withLocalSigOfRemote(hc.remoteInfo.nodeSpecificPrivKey)
    val isRemoteSigOk = lcssNew.verifyRemoteSig(hc.remoteInfo.nodeId)
    val isBlockDayWrong = isOutOfSync(remoteSU.blockDay)

    if (isBlockDayWrong) {
      disconnectAndBecomeSleeping(hc)
    } else if (remoteSU.remoteUpdates < lcssNew.localUpdates) {
      // Persist unsigned remote updates to use them on re-sync
      // we do not update runtime data because ours is newer one
      process(CMD_SIGN)
      me STORE hc
    } else if (!isRemoteSigOk) {
      s"their updates are different from ours: remote=${remoteSU.remoteUpdates}/${remoteSU.localUpdates}, local=${lcssNew.localUpdates}/${lcssNew.remoteUpdates}"
      hc.resizeProposal.map(hc.withResize) match {
        case Some(resizedHC) => attemptStateUpdate(remoteSU, resizedHC)
        case None            => localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
      }
    } else {
      val remoteRejects: Seq[RemoteReject] = hc.nextRemoteUpdates.collect {
        case fail: UpdateFailHtlc =>
          RemoteUpdateFail(
            fail,
            hc.localSpec.findOutgoingHtlcById(fail.id).get.add
          )
        case malform: UpdateFailMalformedHtlc =>
          RemoteUpdateMalform(
            malform,
            hc.localSpec.findOutgoingHtlcById(malform.id).get.add
          )
      }

      StoreBecomeSend(
        hc.copy(
          lastCrossSignedState = lcssNew,
          localSpec = hc.nextLocalSpec,
          nextLocalUpdates = Nil,
          nextRemoteUpdates = Nil
        ),
        Channel.Open,
        lcssNew.stateUpdate
      )
      for (reject <- remoteRejects) events addRejectedRemotely reject
      events.notifyResolvers()
    }
  }

  def receiveHtlcFail(hc: HostedCommits, msg: UpdateMessage, id: Long): Unit =
    hc.localSpec.findOutgoingHtlcById(id) match {
      case None if hc.nextLocalSpec.findOutgoingHtlcById(id).isDefined =>
        disconnectAndBecomeSleeping(hc)
      case _ if hc.postErrorOutgoingResolvedIds.contains(id) =>
        throw ChannelTransitionFail(hc.channelId, msg)
      case None => throw ChannelTransitionFail(hc.channelId, msg)
      case _    => BECOME(hc.addRemoteProposal(msg), Channel.Open)
    }

  def disconnectAndBecomeSleeping(hc: HostedCommits): Unit = {
    // Could have implemented a more involved partially-signed LCSS resolution
    // but for now we will just disconnect and resolve on reconnect if it gets too busy
    CommsTower.workers
      .get(hc.remoteInfo.nodeSpecificPair)
      .foreach(_.disconnect())
    StoreBecomeSend(hc, Channel.Sleeping)
  }
}
