package net.corda.core.flows

import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assert
import net.corda.core.flows.mixins.WithFinality
import net.corda.core.identity.Party
import net.corda.core.internal.cordapp.CordappInfoResolver
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.testing.core.*
import net.corda.testing.internal.matchers.flow.willReturn
import net.corda.testing.internal.matchers.flow.willThrow
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.cordappsForPackages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.AfterClass
import org.junit.Test

class FinalityFlowTests : WithFinality {
    companion object {
        private val CHARLIE = TestIdentity(CHARLIE_NAME, 90).party
        private val classMockNet = InternalMockNetwork(cordappsForAllNodes = cordappsForPackages(
                "net.corda.finance.contracts.asset",
                "net.corda.finance.schemas",
                "net.corda.core.flows.mixins"
        ))

        @JvmStatic
        @AfterClass
        fun tearDown() = classMockNet.stopNodes()
    }

    override val mockNet = classMockNet

    private val aliceNode = makeNode(ALICE_NAME)
    private val bobNode = makeNode(BOB_NAME)

    private val bob = bobNode.info.singleIdentity()
    private val notary = mockNet.defaultNotaryIdentity

    @Test
    fun `finalise a simple transaction`() {
        val stx = aliceNode.signCashTransactionWith(bob)

        assert.that(
            aliceNode.finalise(stx, bob),
                willReturn(
                        requiredSignatures(1)
                                and visibleTo(bobNode)))
    }

    @Test
    fun `reject a transaction with unknown parties`() {
        // Charlie isn't part of this network, so node A won't recognise them
        val stx = aliceNode.signCashTransactionWith(CHARLIE)

        assert.that(
            aliceNode.finalise(stx),
                willThrow<IllegalArgumentException>())
    }

    @Test
    fun `prevent use of the old API if the CorDapp target version is 4`() {
        val stx = aliceNode.signCashTransactionWith(bob)
        val resultFuture = CordappInfoResolver.withCordappInfo(targetPlatformVersion = 4) {
            @Suppress("DEPRECATION")
            aliceNode.startFlowAndRunNetwork(FinalityFlow(stx)).resultFuture
        }
        assertThatIllegalArgumentException().isThrownBy {
            resultFuture.getOrThrow()
        }.withMessageContaining("A flow session for each external participant to the transaction must be provided.")
    }

    @Test
    fun `allow use of the old API if the CorDapp target version is 3`() {
        val stx = aliceNode.signCashTransactionWith(bob)
        val resultFuture = CordappInfoResolver.withCordappInfo(targetPlatformVersion = 3) {
            @Suppress("DEPRECATION")
            aliceNode.startFlowAndRunNetwork(FinalityFlow(stx)).resultFuture
        }
        resultFuture.getOrThrow()
        assertThat(bobNode.services.validatedTransactions.getTransaction(stx.id)).isNotNull()
    }

    private fun TestStartedNode.signCashTransactionWith(other: Party): SignedTransaction {
        val amount = 1000.POUNDS.issuedBy(info.singleIdentity().ref(0))
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, other, notary)
        return services.signInitialTransaction(builder)
    }
}
