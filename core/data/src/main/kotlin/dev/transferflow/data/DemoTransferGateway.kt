package dev.transferflow.data

import androidx.room.withTransaction
import dev.transferflow.domain.AccountId
import dev.transferflow.domain.DashboardSnapshot
import dev.transferflow.domain.EpochClock
import dev.transferflow.domain.GatewayOperation
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationId
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.RejectionReason
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransferGateway
import dev.transferflow.domain.TransferPolicy
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.UncertaintyReason
import dev.transferflow.domain.ValidationCode
import dev.transferflow.domain.validateReview
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DemoScenario {
    SUCCESS,
    PENDING,
    REJECTED,
    TIMEOUT_AFTER_ACCEPTANCE,
    SLOW,
}

class DemoControls {
    private val mutableScenario = MutableStateFlow(DemoScenario.SUCCESS)
    private val mutableOffline = MutableStateFlow(false)
    val scenario: StateFlow<DemoScenario> = mutableScenario.asStateFlow()
    val offline: StateFlow<Boolean> = mutableOffline.asStateFlow()

    fun setScenario(value: DemoScenario) {
        mutableScenario.value = value
    }

    fun setOffline(value: Boolean) {
        mutableOffline.value = value
    }
}

/** A durable synthetic bank. Its transaction commits independently of the client's receipt. */
class DemoTransferGateway(
    private val database: GatewayDatabase,
    private val controls: DemoControls,
    private val clock: EpochClock,
    private val policy: TransferPolicy = TransferPolicy.DEFAULT,
    private val slowResponseMillis: Long = 2_000,
    private val operationIds: () -> OperationId = { OperationId(UUID.randomUUID().toString()) },
) : TransferGateway {
    override suspend fun dashboard(): DashboardSnapshot = database.withTransaction {
        val dao = database.dao()
        val account = ensureAccount()
        DashboardSnapshot(
            account.toDomain().copy(cachedAtEpochMillis = clock.nowMillis()),
            dao.transactions().map(TransactionEntity::toDomain),
        )
    }

    override suspend fun submit(request: TransferRequest): SubmitResult {
        var newScenario: DemoScenario? = null
        val result = database.withTransaction {
            val dao = database.dao()
            val existing = dao.operation(request.review.payerAccountId.value, request.key.value)
            if (existing != null) {
                val operation = existing.toDomain()
                return@withTransaction if (operation.request == request)
                    SubmitResult.Known(operation)
                else SubmitResult.KeyConflict
            }
            val account = ensureAccount().toDomain()
            val issues = validateReview(request.review, account, policy)
            val scenario = controls.scenario.value
            newScenario = scenario
            val rejection =
                when {
                    issues.any { it.code == ValidationCode.AMOUNT_LIMIT } ->
                        RejectionReason.TRANSFER_LIMIT
                    issues.any {
                        it.code == ValidationCode.INSUFFICIENT_FUNDS ||
                            it.code == ValidationCode.AMOUNT_OVERFLOW
                    } -> RejectionReason.INSUFFICIENT_FUNDS
                    issues.isNotEmpty() ->
                        throw IllegalArgumentException("Gateway received an invalid review")
                    scenario == DemoScenario.REJECTED -> RejectionReason.DEMO_REJECTED
                    else -> null
                }
            val status =
                when {
                    rejection != null -> OperationStatus.Rejected(rejection)
                    scenario == DemoScenario.PENDING -> OperationStatus.Pending
                    else -> OperationStatus.Succeeded
                }
            val operation = GatewayOperation(operationIds(), request, status, clock.nowMillis())
            if (rejection == null) {
                val total = request.review.amount + request.review.fee
                val updated =
                    account.copy(
                        availableBalance = account.availableBalance - total,
                        bookedBalance =
                            if (status == OperationStatus.Succeeded) account.bookedBalance - total
                            else account.bookedBalance,
                    )
                dao.putAccount(updated.toEntity())
            }
            dao.insertOperation(
                GatewayOperationEntity(
                    operation.id.value,
                    request.review.payerAccountId.value,
                    request.key.value,
                    request.review.recipient.name,
                    request.review.recipient.iban.value,
                    request.review.amount.minor,
                    request.review.fee.minor,
                    status.persistedName(),
                    rejection?.name,
                    operation.updatedAtEpochMillis,
                    scenario.name,
                ),
            )
            if (status == OperationStatus.Succeeded)
                dao.putTransaction(operation.transaction().toEntity())
            SubmitResult.Known(operation)
        }
        // Acceptance has committed. Cancellation or a missing response cannot undo the bank's
        // operation.
        if (newScenario == DemoScenario.TIMEOUT_AFTER_ACCEPTANCE && result is SubmitResult.Known) {
            return SubmitResult.Uncertain(UncertaintyReason.TRANSPORT)
        }
        if (newScenario == DemoScenario.SLOW) delay(slowResponseMillis)
        return result
    }

    override suspend fun lookup(payer: AccountId, key: RequestKey): LookupResult =
        database.withTransaction {
            val dao = database.dao()
            val existing =
                dao.operation(payer.value, key.value)
                    ?: return@withTransaction LookupResult.NotFound
            if (existing.state != "PENDING")
                return@withTransaction LookupResult.Found(existing.toDomain())
            val account = ensureAccount().toDomain()
            val operation = existing.toDomain()
            val now = clock.nowMillis()
            if (dao.settle(existing.operationId, now) == 1) {
                dao.putAccount(
                    account
                        .copy(
                            bookedBalance =
                                account.bookedBalance -
                                    (operation.request.review.amount +
                                        operation.request.review.fee),
                        )
                        .toEntity(),
                )
                dao.putTransaction(
                    operation
                        .copy(status = OperationStatus.Succeeded, updatedAtEpochMillis = now)
                        .transaction()
                        .toEntity(),
                )
            }
            LookupResult.Found(requireNotNull(dao.operation(payer.value, key.value)).toDomain())
        }

    private suspend fun ensureAccount(): AccountEntity {
        val dao = database.dao()
        return dao.account()
            ?: DemoSeed.account(clock.nowMillis()).toEntity().also { dao.putAccount(it) }
    }
}
