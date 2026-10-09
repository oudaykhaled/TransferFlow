package dev.transferflow.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.transferflow.data.TransferRuntime

class TransferFlowApplication : Application() {
    private val defaultRuntime: TransferRuntime by lazy { runtimeFactory(this) }
    internal var testRuntime: TransferRuntime? = null
    val runtime: TransferRuntime
        get() = if (BuildConfig.DEBUG) testRuntime ?: defaultRuntime else defaultRuntime

    companion object {
        /**
         * Instrumentation supplies an isolated database; production always uses the default
         * factory.
         */
        @Volatile var runtimeFactory: (Context) -> TransferRuntime = { TransferRuntime.create(it) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as TransferFlowApplication).runtime
        setContent {
            TransferFlowTheme {
                val factory =
                    remember(runtime) {
                        object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(
                                modelClass: Class<T>,
                                extras: CreationExtras,
                            ): T {
                                require(
                                    modelClass.isAssignableFrom(TransferFlowViewModel::class.java),
                                )
                                @Suppress("UNCHECKED_CAST")
                                return TransferFlowViewModel(
                                    runtime.repository,
                                    extras.createSavedStateHandle(),
                                )
                                    as T
                            }
                        }
                    }
                val viewModel: TransferFlowViewModel = viewModel(factory = factory)
                TransferFlowScreen(viewModel, if (BuildConfig.DEBUG) runtime.controls else null)
            }
        }
    }
}
