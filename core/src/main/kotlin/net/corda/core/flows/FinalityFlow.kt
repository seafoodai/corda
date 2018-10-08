package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.isFulfilledBy
import net.corda.core.identity.Party
import net.corda.core.identity.groupAbstractPartyByWellKnownParty
import net.corda.core.internal.cordapp.CordappInfoResolver
import net.corda.core.internal.pushToLoggingContext
import net.corda.core.node.StatesToRecord.ALL_VISIBLE
import net.corda.core.node.StatesToRecord.ONLY_RELEVANT
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

/**
 * Verifies the given transaction, then sends it to the named notary. If the notary agrees that the transaction
 * is acceptable then it is from that point onwards committed to the ledger, and will be written through to the
 * vault. Additionally it will be distributed to the parties reflected in the participants list of the states.
 *
 * The transaction is expected to have already been resolved: if its dependencies are not available in local
 * storage, verification will fail. It must have signatures from all necessary parties other than the notary.
 *
 * A list of [FlowSession]s is required for each non-local participant of the transaction. These participants will receive
 * the final notarised transaction by calling [ReceiveFinalityFlow] in their counterpart flows. Sessions with non-participants
 * can also be included, but they must pass in true for [ReceiveFinalityFlow.recordAllStates] if they wish to record the
 * contract states in their vaults.
 *
 * The flow returns the same transaction but with the additional signatures from the notary.
 *
 * NOTE: This is an inlined flow but for backwards compatibility is annotated with [InitiatingFlow].
 *
 * @param transaction What to commit.
 * @param sessions A collection of [FlowSession]s who will be given the notarised transaction. This list **must** include
 * all participants in the transaction (excluding the local identity).
 */
@InitiatingFlow
class FinalityFlow private constructor(val transaction: SignedTransaction,
                                       private val extraRecipients: Set<Party>,
                                       override val progressTracker: ProgressTracker,
                                       private val sessions: Collection<FlowSession>?) : FlowLogic<SignedTransaction>() {
    @Deprecated(DEPRECATION_MSG)
    constructor(transaction: SignedTransaction, extraRecipients: Set<Party>, progressTracker: ProgressTracker) : this(transaction, extraRecipients, progressTracker, null)
    @Deprecated(DEPRECATION_MSG)
    constructor(transaction: SignedTransaction, extraRecipients: Set<Party>) : this(transaction, extraRecipients, tracker(), null)
    @Deprecated(DEPRECATION_MSG)
    constructor(transaction: SignedTransaction) : this(transaction, emptySet(), tracker(), null)
    @Deprecated(DEPRECATION_MSG)
    constructor(transaction: SignedTransaction, progressTracker: ProgressTracker) : this(transaction, emptySet(), progressTracker, null)

    constructor(transaction: SignedTransaction, sessions: Collection<FlowSession>, progressTracker: ProgressTracker) : this(transaction, emptySet(), progressTracker, sessions)
    constructor(transaction: SignedTransaction, sessions: Collection<FlowSession>) : this(transaction, emptySet(), tracker(), sessions)
    constructor(transaction: SignedTransaction, firstSession: FlowSession, vararg restSessions: FlowSession) : this(transaction, emptySet(), tracker(), listOf(firstSession) + restSessions.asList())

    companion object {
        private const val DEPRECATION_MSG = "It is unsafe to use this constructor as it requires nodes to automatically " +
                "accept notarised transactions without first checking their relevancy. Instead, use one of the constructors " +
                "that takes in existing FlowSessions."

        object NOTARISING : ProgressTracker.Step("Requesting signature by notary service") {
            override fun childProgressTracker() = NotaryFlow.Client.tracker()
        }

        object BROADCASTING : ProgressTracker.Step("Broadcasting transaction to participants")

        @JvmStatic
        fun tracker() = ProgressTracker(NOTARISING, BROADCASTING)
    }

    @Suspendable
    @Throws(NotaryException::class)
    override fun call(): SignedTransaction {
        if (sessions == null) {
            require(CordappInfoResolver.currentTargetVersion < 4) {
                "A flow session for each external participant to the transaction must be provided. If you wish to continue " +
                        "to use this insecure API then specify a target platform version of less than 4 for your CorDapp."
            }
            logger.warn("The current usage of FinalityFlow is unsafe. Please consider upgrading your CorDapp to use " +
                    "FinalityFlow with FlowSessions.")
        } else {
            require(sessions.none { serviceHub.myInfo.isLegalIdentity(it.counterparty) }) {
                "Do not provide flow sessions for the local node. FinalityFlow will record the notarised transaction locally."
            }
        }

        // Note: this method is carefully broken up to minimize the amount of data reachable from the stack at
        // the point where subFlow is invoked, as that minimizes the checkpointing work to be done.
        //
        // Lookup the resolved transactions and use them to map each signed transaction to the list of participants.
        // Then send to the notary if needed, record locally and distribute.

        transaction.pushToLoggingContext()
        val commandDataTypes = transaction.tx.commands.mapNotNull { it.value::class.qualifiedName }.toSet()
        logger.info("Started finalization, commands are ${commandDataTypes.joinToString(", ", "[", "]")}.")
        val externalParticipants = extractExternalParticipants(verifyTx())

        if (sessions != null) {
            val missingRecipients = externalParticipants - sessions.map { it.counterparty }
            require(missingRecipients.isEmpty()) {
                "Flow sessions were not provided for the following transaction participants: $missingRecipients"
            }
        }

        val notarised = notariseAndRecord()

        // Each transaction has its own set of recipients, but extra recipients get them all.
        progressTracker.currentStep = BROADCASTING

        if (sessions == null) {
            val recipients = externalParticipants + (extraRecipients - serviceHub.myInfo.legalIdentities)
            logger.info("Broadcasting transaction to parties ${recipients.joinToString(", ", "[", "]")}.")
            for (recipient in recipients) {
                logger.info("Sending transaction to party ${recipient.name}.")
                val session = initiateFlow(recipient)
                subFlow(SendTransactionFlow(session, notarised))
                logger.info("Party $recipient received the transaction.")
            }
        } else {
            for (session in sessions) {
                subFlow(SendTransactionFlow(session, notarised))
                logger.info("Party ${session.counterparty} received the transaction.")
            }
        }

        logger.info("All parties received the transaction successfully.")

        return notarised
    }

    @Suspendable
    private fun notariseAndRecord(): SignedTransaction {
        val notarised = if (needsNotarySignature(transaction)) {
            progressTracker.currentStep = NOTARISING
            val notarySignatures = subFlow(NotaryFlow.Client(transaction))
            transaction + notarySignatures
        } else {
            logger.info("No need to notarise this transaction.")
            transaction
        }
        logger.info("Recording transaction locally.")
        serviceHub.recordTransactions(notarised)
        logger.info("Recorded transaction locally successfully.")
        return notarised
    }

    private fun needsNotarySignature(stx: SignedTransaction): Boolean {
        val wtx = stx.tx
        val needsNotarisation = wtx.inputs.isNotEmpty() || wtx.references.isNotEmpty() || wtx.timeWindow != null
        return needsNotarisation && hasNoNotarySignature(stx)
    }

    private fun hasNoNotarySignature(stx: SignedTransaction): Boolean {
        val notaryKey = stx.tx.notary?.owningKey
        val signers = stx.sigs.map { it.by }.toSet()
        return notaryKey?.isFulfilledBy(signers) != true
    }

    private fun extractExternalParticipants(ltx: LedgerTransaction): Set<Party> {
        val participants = ltx.outputStates.flatMap { it.participants } + ltx.inputStates.flatMap { it.participants }
        return groupAbstractPartyByWellKnownParty(serviceHub, participants).keys - serviceHub.myInfo.legalIdentities
    }

    private fun verifyTx(): LedgerTransaction {
        val notary = transaction.tx.notary
        // The notary signature(s) are allowed to be missing but no others.
        if (notary != null) transaction.verifySignaturesExcept(notary.owningKey) else transaction.verifyRequiredSignatures()
        val ltx = transaction.toLedgerTransaction(serviceHub, false)
        ltx.verify()
        return ltx
    }
}

/**
 * The receiving counterpart to [FinalityFlow].
 *
 * All parties who are receiving a finalised transaction from a sender flow must subcall this flow in their own flows.
 *
 * It's typical to have already signed the transaction proposal in the same workflow using [SignTransactionFlow]. If so
 * then the transaction ID can be passed in as an extra check to ensure the finalised transaction is the one that was signed
 * before it's committed to the vault.
 *
 * @param otherSideSession The session which is providing the transaction to record.
 * @param expectedTxId Expected ID of the transcation that's about to be received. This is typically retrieved from
 * [SignTransactionFlow].
 * @param recordAllStates Whether to record all states in the transaction (i.e. [ALL_VISIBLE]) or just the ones which are
 * relevant ([ONLY_RELEVANT]). Only non-participants to the transaction will typically specify true. Defaults to false.
 */
class ReceiveFinalityFlow @JvmOverloads constructor(val otherSideSession: FlowSession,
                                                    val expectedTxId: SecureHash? = null,
                                                    val recordAllStates: Boolean = false) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        return subFlow(object : ReceiveTransactionFlow(otherSideSession, statesToRecord = if (recordAllStates) ALL_VISIBLE else ONLY_RELEVANT) {
            override fun checkBeforeRecording(stx: SignedTransaction) {
                require(expectedTxId == null || expectedTxId == stx.id) {
                    "We expected to receive transaction with ID $expectedTxId but instead got ${stx.id}. Transaction was" +
                            "not recorded and nor its states sent to the vault."
                }
            }
        })
    }
}
