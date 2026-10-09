package dev.transferflow.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import dev.transferflow.domain.AccountId
import dev.transferflow.domain.AccountSnapshot
import dev.transferflow.domain.AttemptStatus
import dev.transferflow.domain.DutchIban
import dev.transferflow.domain.Euro
import dev.transferflow.domain.GatewayOperation
import dev.transferflow.domain.OperationId
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.Recipient
import dev.transferflow.domain.RejectionReason
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.TransactionRecord
import dev.transferflow.domain.TransferAttempt
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import dev.transferflow.domain.UncertaintyReason
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val singleton: Int = 1,
    val accountId: String,
    val displayName: String,
    val iban: String,
    val bookedMinor: Long,
    val availableMinor: Long,
    val cachedAt: Long,
) {
    fun toDomain() =
        AccountSnapshot(
            AccountId(accountId),
            displayName,
            requireNotNull(DutchIban.parse(iban)),
            Euro(bookedMinor),
            Euro(availableMinor),
            cachedAt,
        )
}

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val operationId: String,
    val recipientName: String,
    val recipientIban: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val bookedAt: Long,
) {
    fun toDomain() =
        TransactionRecord(
            OperationId(operationId),
            Recipient(recipientName, requireNotNull(DutchIban.parse(recipientIban))),
            Euro(amountMinor),
            Euro(feeMinor),
            bookedAt,
        )
}

@Entity(tableName = "attempts")
data class AttemptEntity(
    @PrimaryKey val requestKey: String,
    val payerId: String,
    val recipientName: String,
    val recipientIban: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val createdAt: Long,
    val state: String,
    val uncertainty: String? = null,
    val operationId: String? = null,
    val rejection: String? = null,
    val operationUpdatedAt: Long? = null,
) {
    fun request() =
        TransferRequest(
            RequestKey(requestKey),
            TransferReview(
                AccountId(payerId),
                Recipient(recipientName, requireNotNull(DutchIban.parse(recipientIban))),
                Euro(amountMinor),
                Euro(feeMinor),
            ),
        )

    fun toDomain(): TransferAttempt {
        val request = request()
        val status =
            when (state) {
                "PREPARED" -> AttemptStatus.Prepared
                "SUBMITTING" -> AttemptStatus.Submitting
                "UNKNOWN" ->
                    AttemptStatus.Unknown(UncertaintyReason.valueOf(requireNotNull(uncertainty)))
                else ->
                    AttemptStatus.Confirmed(
                        GatewayOperation(
                            OperationId(requireNotNull(operationId)),
                            request,
                            operationStatus(state, rejection),
                            requireNotNull(operationUpdatedAt),
                        ),
                    )
            }
        return TransferAttempt(request, createdAt, status)
    }

    companion object {
        fun prepared(request: TransferRequest, now: Long) =
            AttemptEntity(
                request.key.value,
                request.review.payerAccountId.value,
                request.review.recipient.name,
                request.review.recipient.iban.value,
                request.review.amount.minor,
                request.review.fee.minor,
                now,
                "PREPARED",
            )
    }
}

@Entity(tableName = "active_slot")
data class ActiveSlotEntity(@PrimaryKey val singleton: Int = 1, val requestKey: String)

@Dao
interface ClientDao {
    @Query("SELECT * FROM accounts WHERE singleton = 1") suspend fun account(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE singleton = 1") fun observeAccount(): Flow<AccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(account: AccountEntity)

    @Query("SELECT * FROM transactions ORDER BY bookedAt DESC, operationId ASC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY bookedAt DESC, operationId ASC")
    suspend fun transactions(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putTransaction(transaction: TransactionEntity): Long

    @Query("SELECT * FROM attempts WHERE requestKey = :key")
    suspend fun attempt(key: String): AttemptEntity?

    @Query("SELECT * FROM attempts WHERE requestKey = :key")
    fun observeAttempt(key: String): Flow<AttemptEntity?>

    @Insert suspend fun insertAttempt(attempt: AttemptEntity)

    @Query("SELECT requestKey FROM active_slot WHERE singleton = 1")
    suspend fun activeKey(): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claimSlot(slot: ActiveSlotEntity): Long

    @Query("DELETE FROM active_slot WHERE singleton = 1 AND requestKey = :key")
    suspend fun releaseSlot(key: String)

    @Query(
        """
        UPDATE attempts SET state = 'SUBMITTING', uncertainty = NULL
        WHERE requestKey = :key AND state = :expected
    """,
    )
    suspend fun claimAction(key: String, expected: String): Int

    @Query(
        """
        UPDATE attempts SET state = 'UNKNOWN', uncertainty = :reason
        WHERE requestKey = :key AND state IN ('PREPARED', 'SUBMITTING', 'UNKNOWN')
    """,
    )
    suspend fun markUnknown(key: String, reason: String): Int

    @Query(
        """
        UPDATE attempts SET state = :state, uncertainty = NULL, operationId = :operationId,
            rejection = :rejection, operationUpdatedAt = :updatedAt
        WHERE requestKey = :key AND state NOT IN ('SUCCEEDED', 'REJECTED')
    """,
    )
    suspend fun recordOperation(
        key: String,
        state: String,
        operationId: String,
        rejection: String?,
        updatedAt: Long,
    ): Int
}

@Database(
    entities =
        [
            AccountEntity::class,
            TransactionEntity::class,
            AttemptEntity::class,
            ActiveSlotEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class ClientDatabase : RoomDatabase() {
    abstract fun dao(): ClientDao
}

@Entity(
    tableName = "gateway_operations",
    indices = [Index(value = ["payerId", "requestKey"], unique = true)],
)
data class GatewayOperationEntity(
    @PrimaryKey val operationId: String,
    val payerId: String,
    val requestKey: String,
    val recipientName: String,
    val recipientIban: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val state: String,
    val rejection: String?,
    val updatedAt: Long,
    val scenario: String,
) {
    fun toDomain() =
        GatewayOperation(
            OperationId(operationId),
            TransferRequest(
                RequestKey(requestKey),
                TransferReview(
                    AccountId(payerId),
                    Recipient(recipientName, requireNotNull(DutchIban.parse(recipientIban))),
                    Euro(amountMinor),
                    Euro(feeMinor),
                ),
            ),
            operationStatus(state, rejection),
            updatedAt,
        )
}

@Dao
interface GatewayDao {
    @Query("SELECT * FROM accounts WHERE singleton = 1") suspend fun account(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(account: AccountEntity)

    @Query(
        """
        SELECT * FROM gateway_operations WHERE payerId = :payer AND requestKey = :key
    """,
    )
    suspend fun operation(payer: String, key: String): GatewayOperationEntity?

    @Insert suspend fun insertOperation(operation: GatewayOperationEntity)

    @Query(
        """
        UPDATE gateway_operations SET state = 'SUCCEEDED', updatedAt = :now
        WHERE operationId = :id AND state = 'PENDING'
    """,
    )
    suspend fun settle(id: String, now: Long): Int

    @Query("SELECT * FROM gateway_operations")
    suspend fun operations(): List<GatewayOperationEntity>

    @Query("SELECT * FROM transactions ORDER BY bookedAt DESC, operationId ASC")
    suspend fun transactions(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putTransaction(transaction: TransactionEntity): Long
}

@Database(
    entities = [AccountEntity::class, TransactionEntity::class, GatewayOperationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun dao(): GatewayDao
}

internal fun operationStatus(state: String, rejection: String?): OperationStatus =
    when (state) {
        "PENDING" -> OperationStatus.Pending
        "SUCCEEDED" -> OperationStatus.Succeeded
        "REJECTED" -> OperationStatus.Rejected(RejectionReason.valueOf(requireNotNull(rejection)))
        else -> throw IllegalArgumentException("Invalid operation state")
    }

internal fun OperationStatus.persistedName(): String =
    when (this) {
        OperationStatus.Pending -> "PENDING"
        OperationStatus.Succeeded -> "SUCCEEDED"
        is OperationStatus.Rejected -> "REJECTED"
    }

internal fun AccountSnapshot.toEntity() =
    AccountEntity(
        accountId = id.value,
        displayName = displayName,
        iban = iban.value,
        bookedMinor = bookedBalance.minor,
        availableMinor = availableBalance.minor,
        cachedAt = cachedAtEpochMillis,
    )

internal fun TransactionRecord.toEntity() =
    TransactionEntity(
        operationId.value,
        recipient.name,
        recipient.iban.value,
        amount.minor,
        fee.minor,
        bookedAtEpochMillis,
    )

internal fun GatewayOperation.transaction() =
    TransactionRecord(
        id,
        request.review.recipient,
        request.review.amount,
        request.review.fee,
        updatedAtEpochMillis,
    )

/** Wholly synthetic values. Format-valid IBANs do not identify live account holders. */
object DemoSeed {
    val payerIban: DutchIban = requireNotNull(DutchIban.parse("NL52DEMO0000000001"))
    val recipientIban: DutchIban = requireNotNull(DutchIban.parse("NL25DEMO0000000002"))

    fun account(now: Long = 0) =
        AccountSnapshot(
            AccountId("demo-current-account"),
            "Everyday account",
            payerIban,
            Euro(1_248_055),
            Euro(1_248_055),
            now,
        )
}
