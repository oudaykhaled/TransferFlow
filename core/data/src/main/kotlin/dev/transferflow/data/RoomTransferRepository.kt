package dev.transferflow.data

import androidx.room.withTransaction
import dev.transferflow.domain.AttemptStatus
import dev.transferflow.domain.DashboardSnapshot
import dev.transferflow.domain.EpochClock
import dev.transferflow.domain.GatewayOperation
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.PrepareResult
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.RequestKeyFactory
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransferAttempt
import dev.transferflow.domain.TransferGateway
import dev.transferflow.domain.TransferPolicy
import dev.transferflow.domain.TransferRepository
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import dev.transferflow.domain.UncertaintyReason
import dev.transferflow.domain.isTerminal
import dev.transferflow.domain.validateReview
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The journal owns the immutable confirmed request. A singleton row enforces one active request,
 * and conditional writes preserve terminal outcomes even when another coordinator has old data.
 */
class RoomTransferRepository(
    private val database: ClientDatabase,
    private val gateway: TransferGateway,
    private val controls: DemoControls,
    private val clock: EpochClock,
    private val keys: RequestKeyFactory,
    private val policy: TransferPolicy = TransferPolicy.DEFAULT,
    coordinationKey: String =
        database.openHelper.databaseName ?: "memory-${System.identityHashCode(database)}",
) : TransferRepository {
    // Multiple repository instances sharing a file also share action serialization. The database
    // slot and conditional transitions remain the durable guards after process recreation.
    private val actions = coordinators.getOrPut(coordinationKey) { Mutex() }
    private val dao
        get() = database.dao()

    override fun observeDashboard(): Flow<DashboardSnapshot> = flow {
        ensureCache()
        emitAll(
            combine(dao.observeAccount().filterNotNull(), dao.observeTransactions()) {
                account,
                transactions ->
                DashboardSnapshot(account.toDomain(), transactions.map(TransactionEntity::toDomain))
            },
        )
    }

    override fun observeAttempt(key: RequestKey): Flow<TransferAttempt?> =
        dao.observeAttempt(key.value).map { it?.toDomain() }

    override suspend fun findUnresolvedAttempt(): TransferAttempt? = actions.withLock {
        database.withTransaction {
            val key = dao.activeKey() ?: return@withTransaction null
            val row = requireNotNull(dao.attempt(key)) { "Active journal slot has no request" }
            if (row.state == "SUBMITTING") dao.markUnknown(key, UncertaintyReason.CANCELLED.name)
            requireNotNull(dao.attempt(key)).toDomain()
        }
    }

    override suspend fun refreshDashboard() {
        ensureCache()
        if (controls.offline.value) return
        val snapshot = gateway.dashboard()
        database.withTransaction {
            val cached = requireNotNull(dao.account()).toDomain()
            require(snapshot.account.id == cached.id && snapshot.account.iban == cached.iban) {
                "Dashboard changed the account identity"
            }
            dao.putAccount(snapshot.account.toEntity())
            snapshot.transactions.forEach { dao.putTransaction(it.toEntity()) }
        }
    }

    override suspend fun prepare(review: TransferReview): PrepareResult = actions.withLock {
        ensureCache()
        database.withTransaction {
            val active = dao.activeKey()
            if (active != null)
                return@withTransaction PrepareResult.AlreadyActive(
                    requireNotNull(dao.attempt(active)).toDomain(),
                )
            val issues = validateReview(review, requireNotNull(dao.account()).toDomain(), policy)
            if (issues.isNotEmpty()) return@withTransaction PrepareResult.Invalid(issues)
            if (controls.offline.value) return@withTransaction PrepareResult.Offline
            val row =
                AttemptEntity.prepared(TransferRequest(keys.create(), review), clock.nowMillis())
            check(dao.claimSlot(ActiveSlotEntity(requestKey = row.requestKey)) != -1L) {
                "Active slot changed inside transaction"
            }
            dao.insertAttempt(row)
            PrepareResult.Created(row.toDomain())
        }
    }

    override suspend fun submitPrepared(key: RequestKey) = actions.withLock {
        val request =
            database.withTransaction {
                val row = dao.attempt(key.value) ?: return@withTransaction null
                if (row.state != "PREPARED" || controls.offline.value) return@withTransaction null
                if (dao.claimAction(key.value, "PREPARED") != 1) return@withTransaction null
                row.request()
            } ?: return@withLock
        dispatch(request)
    }

    override suspend fun checkStatus(key: RequestKey) = actions.withLock {
        val row = dao.attempt(key.value) ?: return@withLock
        if (row.state != "UNKNOWN" && row.state != "PENDING") return@withLock
        when (val result = lookup(row.request())) {
            is LookupResult.Found -> accept(row.request(), result.operation)
            LookupResult.NotFound -> unknown(key, UncertaintyReason.NOT_FOUND)
            is LookupResult.Unavailable -> unknown(key, result.reason)
        }
        refreshAfterOutcome()
    }

    override suspend fun retryUnknown(key: RequestKey) = actions.withLock {
        val row = dao.attempt(key.value) ?: return@withLock
        if (row.state != "UNKNOWN") return@withLock
        val request = row.request()
        when (val result = lookup(request)) {
            is LookupResult.Found -> accept(request, result.operation)
            is LookupResult.Unavailable -> unknown(key, result.reason)
            LookupResult.NotFound -> {
                if (controls.offline.value) {
                    unknown(key, UncertaintyReason.NOT_FOUND)
                } else if (dao.claimAction(key.value, "UNKNOWN") == 1) {
                    dispatch(request)
                }
            }
        }
        refreshAfterOutcome()
    }

    private suspend fun dispatch(request: TransferRequest) {
        try {
            when (val result = gateway.submit(request)) {
                is SubmitResult.Known -> accept(request, result.operation)
                is SubmitResult.Uncertain -> unknown(request.key, result.reason)
                SubmitResult.KeyConflict -> unknown(request.key, UncertaintyReason.KEY_CONFLICT)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { unknown(request.key, UncertaintyReason.CANCELLED) }
            throw cancelled
        } catch (_: IOException) {
            unknown(request.key, UncertaintyReason.TRANSPORT)
        } catch (_: Exception) {
            unknown(request.key, UncertaintyReason.SERVER)
        }
        refreshAfterOutcome()
    }

    private suspend fun lookup(request: TransferRequest): LookupResult {
        if (controls.offline.value) return LookupResult.Unavailable(UncertaintyReason.TRANSPORT)
        return try {
            gateway.lookup(request.review.payerAccountId, request.key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            LookupResult.Unavailable(UncertaintyReason.TRANSPORT)
        } catch (_: Exception) {
            LookupResult.Unavailable(UncertaintyReason.SERVER)
        }
    }

    private suspend fun accept(request: TransferRequest, operation: GatewayOperation) =
        database.withTransaction {
            val current = dao.attempt(request.key.value) ?: return@withTransaction
            val previous = (current.toDomain().status as? AttemptStatus.Confirmed)?.operation
            if (operation.request != request || (previous != null && previous.id != operation.id)) {
                dao.markUnknown(request.key.value, UncertaintyReason.INVALID_RESPONSE.name)
                return@withTransaction
            }
            if (current.toDomain().isTerminal()) return@withTransaction
            if (
                previous != null &&
                    operation.status == OperationStatus.Pending &&
                    operation.updatedAtEpochMillis < previous.updatedAtEpochMillis
            )
                return@withTransaction
            val changed =
                dao.recordOperation(
                    request.key.value,
                    operation.status.persistedName(),
                    operation.id.value,
                    (operation.status as? OperationStatus.Rejected)?.reason?.name,
                    operation.updatedAtEpochMillis,
                )
            if (changed != 1) return@withTransaction
            if (operation.status == OperationStatus.Succeeded)
                dao.putTransaction(operation.transaction().toEntity())
            if (operation.status.isTerminal()) dao.releaseSlot(request.key.value)
        }

    private suspend fun unknown(key: RequestKey, reason: UncertaintyReason) {
        dao.markUnknown(key.value, reason.name)
    }

    private suspend fun ensureCache() = database.withTransaction {
        if (dao.account() == null) dao.putAccount(DemoSeed.account().toEntity())
    }

    private suspend fun refreshAfterOutcome() {
        try {
            refreshDashboard()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed read leaves the last cache and freshness timestamp intact.
        }
    }

    private companion object {
        val coordinators = ConcurrentHashMap<String, Mutex>()
    }
}
