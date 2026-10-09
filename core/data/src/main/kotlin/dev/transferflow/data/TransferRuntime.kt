package dev.transferflow.data

import android.content.Context
import androidx.room.Room
import dev.transferflow.domain.EpochClock
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.RequestKeyFactory
import dev.transferflow.domain.TransferPolicy
import dev.transferflow.domain.TransferRepository
import java.io.Closeable
import java.util.UUID

class TransferRuntime
private constructor(
    val repository: TransferRepository,
    val controls: DemoControls,
    private val clientDatabase: ClientDatabase,
    private val gatewayDatabase: GatewayDatabase,
) : Closeable {
    override fun close() {
        clientDatabase.close()
        gatewayDatabase.close()
    }

    companion object {
        fun create(
            context: Context,
            databasePrefix: String = "transferflow",
            clock: EpochClock = EpochClock(System::currentTimeMillis),
            keyFactory: RequestKeyFactory = RequestKeyFactory {
                RequestKey(UUID.randomUUID().toString())
            },
            policy: TransferPolicy = TransferPolicy.DEFAULT,
        ): TransferRuntime {
            require(databasePrefix.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid database prefix" }
            val application = context.applicationContext
            val clientName = "$databasePrefix-client.db"
            val client =
                Room.databaseBuilder(application, ClientDatabase::class.java, clientName).build()
            val ledger =
                Room.databaseBuilder(
                        application,
                        GatewayDatabase::class.java,
                        "$databasePrefix-gateway.db",
                    )
                    .build()
            val controls = DemoControls()
            val gateway = DemoTransferGateway(ledger, controls, clock, policy)
            val repository =
                RoomTransferRepository(
                    client,
                    gateway,
                    controls,
                    clock,
                    keyFactory,
                    policy,
                    application.getDatabasePath(clientName).absolutePath,
                )
            return TransferRuntime(repository, controls, client, ledger)
        }
    }
}
