/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment.send

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, Crypto, Satoshi}
import fr.acinq.eclair.TestConstants.{TestFeeEstimator, defaultBlockHeight}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.PaymentRequest
import fr.acinq.eclair.payment.receive.PaymentRequest.Features
import fr.acinq.eclair.payment.relay.{GetUsableBalances, UsableBalance, UsableBalances}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PAYMENT_ABORTED, PAYMENT_INIT, PAYMENT_IN_PROGRESS, PAYMENT_SUCCEEDED, PaymentAborted, PaymentProgress, _}
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentConfig, SendPaymentRequest}
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.Onion.createMultiPartPayload
import fr.acinq.eclair.wire.{ChannelUpdate, PaymentTimeout}
import org.scalatest.{Outcome, Tag, fixture}

import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentLifecycleSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  import MultiPartPaymentLifecycleSpec._

  case class FixtureParam(paymentId: UUID,
                          nodeParams: NodeParams,
                          payFsm: TestFSMRef[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data, MultiPartPaymentLifecycle],
                          router: TestProbe,
                          relayer: TestProbe,
                          sender: TestProbe,
                          childPayFsm: TestProbe,
                          eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val id = UUID.randomUUID()
    val cfg = SendPaymentConfig(id, id, Some("42"), paymentHash, b, None, storeInDb = true, publishEvent = true)
    val nodeParams = TestConstants.Alice.nodeParams
    nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(500))
    val (childPayFsm, router, relayer, sender, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    class TestMultiPartPaymentLifecycle extends MultiPartPaymentLifecycle(nodeParams, cfg, relayer.ref, router.ref, TestProbe().ref) {
      override def spawnChildPaymentFsm(childId: UUID): ActorRef = childPayFsm.ref
    }
    val paymentHandler = TestFSMRef(new TestMultiPartPaymentLifecycle().asInstanceOf[MultiPartPaymentLifecycle])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    withFixture(test.toNoArgTest(FixtureParam(id, nodeParams, paymentHandler, router, relayer, sender, childPayFsm, eventListener)))
  }

  def initPayment(f: FixtureParam, request: SendPaymentRequest, networkStats: NetworkStats, balances: UsableBalances): Unit = {
    import f._
    sender.send(payFsm, request)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(Some(networkStats)))
    relayer.expectMsg(GetUsableBalances)
    relayer.send(payFsm, balances)
  }

  def waitUntilAmountSent(f: FixtureParam, amount: MilliSatoshi): Unit = {
    Iterator.iterate(0 msat)(sent => {
      sent + f.childPayFsm.expectMsgType[SendPayment].finalPayload.amount
    }).takeWhile(sent => sent < amount)
  }

  test("get network statistics and usable balances before paying") { f =>
    import f._

    assert(payFsm.stateName === PAYMENT_INIT)
    val payment = SendPaymentRequest(1500 * 1000 msat, paymentHash, b, 1, paymentRequest = Some(createMultiPartInvoice(1500 * 1000 msat)))
    sender.send(payFsm, payment)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(None))
    // we should retry if network stats aren't available yet, and ask the router to compute them.
    router.expectMsg(TickComputeNetworkStats)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(Some(emptyStats)))
    relayer.expectMsg(GetUsableBalances)
    awaitCond(payFsm.stateName === PAYMENT_IN_PROGRESS)
  }

  test("send to peer node via multiple channels") { f =>
    import f._
    val pr = createMultiPartInvoice(2000 * 1000 msat)
    val payment = SendPaymentRequest(2000 * 1000 msat, paymentHash, b, 1, paymentRequest = Some(pr))
    // Network statistics should be ignored when sending to peer.
    initPayment(f, payment, emptyStats, usableBalances)

    // The payment should be split in two, using direct channels with b.
    childPayFsm.expectMsgAllOf(
      SendPayment(paymentHash, b, createMultiPartPayload(1000 * 1000 msat, payment.amount, CltvExpiry(defaultBlockHeight + 1 + 9), pr.paymentSecret.get), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_1))),
      SendPayment(paymentHash, b, createMultiPartPayload(1000 * 1000 msat, payment.amount, CltvExpiry(defaultBlockHeight + 1 + 9), pr.paymentSecret.get), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_2)))
    )
    childPayFsm.expectNoMsg(50 millis)
    val childIds = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.toSeq
    assert(childIds.length === 2)

    val pp1 = PartialPayment(childIds.head, 1000 * 1000 msat, 0 msat, randomBytes32, None)
    val pp2 = PartialPayment(childIds(1), 1000 * 1000 msat, 0 msat, randomBytes32, None)
    childPayFsm.send(payFsm, PaymentSent(childIds.head, paymentHash, paymentPreimage, Seq(pp1)))
    childPayFsm.send(payFsm, PaymentSent(childIds(1), paymentHash, paymentPreimage, Seq(pp2)))
    val expectedMsg = PaymentSent(paymentId, paymentHash, paymentPreimage, Seq(pp1, pp2))
    sender.expectMsg(expectedMsg)
    eventListener.expectMsg(expectedMsg)
  }

  test("send to peer node via single big channel") { f =>
    import f._
    val pr = createMultiPartInvoice(1000 * 1000 msat)
    val payment = SendPaymentRequest(1000 * 1000 msat, paymentHash, b, 1, paymentRequest = Some(pr))
    // Network statistics should be ignored when sending to peer (otherwise we should have split into multiple payments).
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(100), d => Satoshi(d.toLong))), usableBalances)
    childPayFsm.expectMsg(SendPayment(paymentHash, b, createMultiPartPayload(payment.amount, payment.amount, CltvExpiry(defaultBlockHeight + 1 + 9), pr.paymentSecret.get), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_1))))
    childPayFsm.expectNoMsg(50 millis)
  }

  test("send to peer node via remote channels") { f =>
    import f._
    // d only has a single channel with capacity 1000 sat, we try to send more.
    val pr = createMultiPartInvoice(2000 * 1000 msat)
    val payment = SendPaymentRequest(2000 * 1000 msat, paymentHash, d, 1, paymentRequest = Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(500), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val payments = payFsm.stateData.asInstanceOf[PaymentProgress].pending.values
    assert(payments.size > 1)
    val directPayments = payments.filter(p => p.routePrefix.head.nextNodeId == d)
    assert(directPayments.size === 1)
    assert(directPayments.head.finalPayload.amount === (1000 * 1000).msat)
  }

  test("send to remote node without splitting") { f =>
    import f._
    val pr = createMultiPartInvoice(300 * 1000 msat)
    val payment = SendPaymentRequest(300 * 1000 msat, paymentHash, e, 1, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1500), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    payFsm.stateData.asInstanceOf[PaymentProgress].pending.foreach {
      case (id, payment) => childPayFsm.send(payFsm, PaymentSent(id, paymentHash, paymentPreimage, Seq(PartialPayment(id, payment.finalPayload.amount, 5 msat, randomBytes32, None))))
    }

    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment.amount)
    assert(result.parts.length === 1)
  }

  test("send to remote node via multiple channels") { f =>
    import f._
    val pr = createMultiPartInvoice(3000 * 1000 msat)
    val payment = SendPaymentRequest(3200 * 1000 msat, paymentHash, e, 3, CltvExpiryDelta(12), Some(pr))
    // A network capacity of 1000 sat should split the payment in at least 3 parts.
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), usableBalances)

    val payments = Iterator.iterate(0 msat)(sent => {
      val child = childPayFsm.expectMsgType[SendPayment]
      assert(child.paymentHash === paymentHash)
      assert(child.targetNodeId === e)
      assert(child.maxAttempts === 3)
      assert(child.finalPayload.expiry === CltvExpiry(defaultBlockHeight + 1 + 12))
      assert(child.finalPayload.paymentSecret === pr.paymentSecret)
      assert(child.finalPayload.totalAmount === payment.amount)
      assert(child.routePrefix.length === 1 && child.routePrefix.head.nodeId === nodeParams.nodeId)
      assert(sent + child.finalPayload.amount <= payment.amount)
      sent + child.finalPayload.amount
    }).toSeq.takeWhile(sent => sent != payment.amount)
    assert(payments.length > 2)
    assert(payments.length < 10)
    childPayFsm.expectNoMsg(50 millis)

    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    val partialPayments = pending.map {
      case (id, payment) => PartialPayment(id, payment.finalPayload.amount, 1 msat, randomBytes32, Some(hop_ac_1 :: hop_ab_2 :: Nil))
    }
    partialPayments.foreach(pp => childPayFsm.send(payFsm, PaymentSent(pp.id, paymentHash, paymentPreimage, Seq(pp))))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.paymentPreimage === paymentPreimage)
    assert(result.parts === partialPayments)
    assert(result.amount === (3200 * 1000).msat)
    assert(result.feesPaid === partialPayments.map(_.feesPaid).sum)
  }

  test("retry after error") { f =>
    import f._
    val pr = createMultiPartInvoice(3000 * 1000 msat)
    val payment = SendPaymentRequest(3000 * 1000 msat, paymentHash, e, 3, CltvExpiryDelta(12), Some(pr))
    // A network capacity of 1000 sat should split the payment in at least 3 parts.
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    val childIds = pending.keys.toSeq
    assert(pending.size > 2)

    // Simulate two failures.
    val failures = Seq(LocalFailure(new RuntimeException("418 I'm a teapot")), UnreadableRemoteFailure(Nil))
    childPayFsm.send(payFsm, PaymentFailed(childIds.head, paymentHash, failures.slice(0, 1)))
    childPayFsm.send(payFsm, PaymentFailed(childIds(1), paymentHash, failures.slice(1, 2)))
    // We should ask for updated balance to take into account pending payments.
    relayer.expectMsg(GetUsableBalances)
    relayer.send(payFsm, usableBalances.copy(balances = usableBalances.balances.dropRight(2)))

    // New payments should be sent that match the failed amount.
    waitUntilAmountSent(f, pending(childIds.head).finalPayload.amount + pending(childIds(1)).finalPayload.amount)
    assert(payFsm.stateData.asInstanceOf[PaymentProgress].failures.toSet === failures.toSet)
  }

  test("cannot send (not enough capacity on local channels)") { f =>
    import f._
    val pr = createMultiPartInvoice(4000 * 1000 msat)
    val payment = SendPaymentRequest(3000 * 1000 msat, paymentHash, e, 3, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), UsableBalances(Seq(
      UsableBalance(b, channelId_ab_1, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ab_1),
      UsableBalance(c, channelId_ac_2, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ac_2),
      UsableBalance(d, channelId_ad_1, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ad_1)))
    )
    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.length === 1)
    assert(result.failures.head.asInstanceOf[LocalFailure].t.getMessage === "balance is too low")
  }

  test("payment timeout") { f =>
    import f._
    val pr = createMultiPartInvoice(3000 * 1000 msat)
    val payment = SendPaymentRequest(3000 * 1000 msat, paymentHash, e, 5, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val (childId1, _) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head

    // If we receive a timeout failure, we directly abort the payment instead of retrying.
    childPayFsm.send(payFsm, PaymentFailed(childId1, paymentHash, RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)) :: Nil))
    relayer.expectNoMsg(50 millis)
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)
  }

  test("fail after too many attempts") { f =>
    import f._
    val pr = createMultiPartInvoice(3000 * 1000 msat)
    val payment = SendPaymentRequest(3000 * 1000 msat, paymentHash, e, 2, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val (childId1, childPayment1) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head

    // We retry one failure.
    val failures = Seq(UnreadableRemoteFailure(hop_ab_1 :: Nil), UnreadableRemoteFailure(hop_ac_1 :: hop_ab_2 :: Nil))
    childPayFsm.send(payFsm, PaymentFailed(childId1, paymentHash, failures.slice(0, 1)))
    relayer.expectMsg(GetUsableBalances)
    relayer.send(payFsm, usableBalances)
    waitUntilAmountSent(f, childPayment1.finalPayload.amount)

    // But another failure occurs...
    val (childId2, _) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    childPayFsm.send(payFsm, PaymentFailed(childId2, paymentHash, failures.slice(1, 2)))
    relayer.expectNoMsg(50 millis)
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)

    // And then all other payments time out.
    payFsm.stateData.asInstanceOf[PaymentAborted].pending.foreach(childId => childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Nil)))
    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.length === 3)
    assert(result.failures.slice(0, 2) === failures)
    assert(result.failures.last.asInstanceOf[LocalFailure].t.getMessage === "payment attempts exhausted without success")
  }

  test("receive partial failure after success (recipient spec violation)") { f =>
    import f._
    val pr = createMultiPartInvoice(4000 * 1000 msat)
    val payment = SendPaymentRequest(4000 * 1000 msat, paymentHash, e, 2, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1500), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending

    // If one of the payments succeeds, the recipient MUST succeed them all: we can consider the whole payment succeeded.
    val (id1, payment1) = pending.head
    childPayFsm.send(payFsm, PaymentSent(id1, paymentHash, paymentPreimage, Seq(PartialPayment(id1, payment1.finalPayload.amount, 10 msat, randomBytes32, None))))
    awaitCond(payFsm.stateName === PAYMENT_SUCCEEDED)

    // A partial failure should simply be ignored.
    val (id2, payment2) = pending.tail.head
    childPayFsm.send(payFsm, PaymentFailed(id2, paymentHash, Nil))

    pending.tail.tail.foreach {
      case (id, payment) => childPayFsm.send(payFsm, PaymentSent(id, paymentHash, paymentPreimage, Seq(PartialPayment(id, payment.finalPayload.amount, 10 msat, randomBytes32, None))))
    }
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment.amount - payment2.finalPayload.amount)
  }

  test("receive partial success after abort (recipient spec violation)") { f =>
    import f._
    val pr = createMultiPartInvoice(5000 * 1000 msat)
    val payment = SendPaymentRequest(5000 * 1000 msat, paymentHash, e, 1, CltvExpiryDelta(12), Some(pr))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(2000), d => Satoshi(d.toLong))), usableBalances)
    waitUntilAmountSent(f, payment.amount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending

    // One of the payments failed and we configured maxAttempts = 1, so we abort.
    val (id1, _) = pending.head
    childPayFsm.send(payFsm, PaymentFailed(id1, paymentHash, Nil))
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)

    // The in-flight HTLC set doesn't pay the full amount, so the recipient MUST not fulfill any of those.
    // But if he does, it's too bad for him as we have obtained a cheaper proof of payment.
    val (id2, payment2) = pending.tail.head
    childPayFsm.send(payFsm, PaymentSent(id2, paymentHash, paymentPreimage, Seq(PartialPayment(id2, payment2.finalPayload.amount, 5 msat, randomBytes32, None))))
    awaitCond(payFsm.stateName === PAYMENT_SUCCEEDED)

    // Even if all other child payments fail, we obtained the preimage so the payment is a success from our point of view.
    pending.tail.tail.foreach {
      case (id, _) => childPayFsm.send(payFsm, PaymentFailed(id, paymentHash, Nil))
    }
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment2.finalPayload.amount)
    assert(result.feesPaid === 5.msat)
  }

  test("split payment", Tag("fuzzy")) { f =>
    // The fees for a single HTLC will be 100 * 172 / 1000 = 17 satoshis.
    f.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(100))
    for (_ <- 1 to 100) {
      // We have a total of 6500 satoshis across all channels. We try to send lower amounts to take fees into account.
      val toSend = ((1 + Random.nextInt(3500)) * 1000).msat
      val networkStats = emptyStats.copy(capacity = Stats(Seq(400 + Random.nextInt(1600)), d => Satoshi(d.toLong)))
      val routeParams = RouteParams(randomize = true, Random.nextInt(1000).msat, Random.nextInt(10).toDouble / 100, 20, CltvExpiryDelta(144), None)
      val request = SendPaymentRequest(toSend, paymentHash, e, 1, routeParams = Some(routeParams), paymentRequest = Some(createMultiPartInvoice(toSend)))
      val fuzzParams = s"(sending $toSend with network capacity ${networkStats.capacity.percentile75.toMilliSatoshi}, fee base ${routeParams.maxFeeBase} and fee percentage ${routeParams.maxFeePct})"
      val (remaining, payments) = splitPayment(f.nodeParams, toSend, usableBalances.balances, networkStats, request, randomize = true)
      assert(remaining === 0.msat, fuzzParams)
      assert(payments.nonEmpty, fuzzParams)
      assert(payments.map(_.finalPayload.amount).sum === toSend, fuzzParams)
    }
  }

}

object MultiPartPaymentLifecycleSpec {

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  /**
   * We simulate a multi-part-friendly network:
   * .-----> b -------.
   * |                |
   * a ----> c -----> e
   * |                |
   * '-----> d -------'
   * where a has multiple channels with each of his peers.
   */

  val a :: b :: c :: d :: e :: Nil = Seq.fill(5)(PrivateKey(randomBytes32).publicKey)
  val channelId_ab_1 = ShortChannelId(1)
  val channelId_ab_2 = ShortChannelId(2)
  val channelId_ac_1 = ShortChannelId(11)
  val channelId_ac_2 = ShortChannelId(12)
  val channelId_ac_3 = ShortChannelId(13)
  val channelId_ad_1 = ShortChannelId(21)
  val defaultChannelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(12), 1 msat, 0 msat, 0, Some(2000 * 1000 msat))
  val channelUpdate_ab_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_1, cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 100 msat, feeProportionalMillionths = 70)
  val channelUpdate_ab_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_2, cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 100 msat, feeProportionalMillionths = 70)
  val channelUpdate_ac_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_1, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ac_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_2, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ac_3 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_3, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ad_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ad_1, cltvExpiryDelta = CltvExpiryDelta(6), feeBaseMsat = 200 msat, feeProportionalMillionths = 50)
  val usableBalances = UsableBalances(Seq(
    UsableBalance(b, channelId_ab_1, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ab_1),
    UsableBalance(b, channelId_ab_2, 1500 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ab_2),
    UsableBalance(c, channelId_ac_1, 500 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ac_1),
    UsableBalance(c, channelId_ac_2, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ac_2),
    UsableBalance(c, channelId_ac_3, 1500 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ac_3),
    UsableBalance(d, channelId_ad_1, 1000 * 1000 msat, 0 msat, isPublic = true, channelUpdate_ad_1)))

  val hop_ab_1 = Hop(a, b, channelUpdate_ab_1)
  val hop_ab_2 = Hop(a, b, channelUpdate_ab_2)
  val hop_ac_1 = Hop(a, c, channelUpdate_ac_1)

  val emptyStats = NetworkStats(0, 0, Stats(Seq(0), d => Satoshi(d.toLong)), Stats(Seq(0), d => CltvExpiryDelta(d.toInt)), Stats(Seq(0), d => MilliSatoshi(d.toLong)), Stats(Seq(0), d => d.toLong))

  def createMultiPartInvoice(amount: MilliSatoshi): PaymentRequest = {
    PaymentRequest(Block.LivenetGenesisBlock.hash, Some(amount), paymentHash, randomKey, "Some multi-part invoice", features = Some(Features(Features.BASIC_MULTI_PART_PAYMENT_OPTIONAL)))
  }

}