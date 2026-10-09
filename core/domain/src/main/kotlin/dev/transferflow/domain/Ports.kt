package dev.transferflow.domain

import kotlinx.coroutines.flow.Flow

sealed interface SubmitResult {
    data class Known(val operation: GatewayOperation) : SubmitResult

    data class Uncertain(val reason: UncertaintyReason) : SubmitResult

    data object KeyConflict : SubmitResult
}

sealed interface LookupResult {
    data class Found(val operation: GatewayOperation) : LookupResult

    data object NotFound : LookupResult

    data class Unavailable(val reason: UncertaintyReason) : LookupResult
}

interface TransferGateway {
    suspend fun dashboard(): DashboardSnapshot

    suspend fun submit(request: TransferRequest): SubmitResult

    suspend fun lookup(payer: AccountId, key: RequestKey): LookupResult
}

sealed interface PrepareResult {
    data class Created(val attempt: TransferAttempt) : PrepareResult

    data class AlreadyActive(val attempt: TransferAttempt) : PrepareResult

    data class Invalid(val issues: List<ValidationIssue>) : PrepareResult

    data object Offline : PrepareResult
}

interface TransferRepository {
    fun observeDashboard(): Flow<DashboardSnapshot>

    fun observeAttempt(key: RequestKey): Flow<TransferAttempt?>

    suspend fun findUnresolvedAttempt(): TransferAttempt?

    suspend fun refreshDashboard()

    suspend fun prepare(review: TransferReview): PrepareResult

    suspend fun submitPrepared(key: RequestKey)

    suspend fun checkStatus(key: RequestKey)

    suspend fun retryUnknown(key: RequestKey)
}

fun interface EpochClock {
    fun nowMillis(): Long
}

fun interface RequestKeyFactory {
    fun create(): RequestKey
}
