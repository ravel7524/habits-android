package ch.habits.android

import android.os.Bundle
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.habits.android.data.ReceiptState
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.desktop.DesktopConnection
import ch.habits.android.desktop.DesktopConnectionSection
import ch.habits.android.desktop.KeystoreDesktopCredentialStore
import ch.habits.android.ui.HabitsApp
import java.io.File

class MainActivity : ComponentActivity() {
    private val runtime by viewModels<HabitsRuntime>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = runtime.repository
        val desktop = runtime.desktop
        setContent {
            val workspace by repository.state.collectAsStateWithLifecycle()
            val remote by desktop.state.collectAsStateWithLifecycle()
            // ACK only a matching durable local receipt; desktop proposals remain pending until then.
            // Delivery reportedState changes cannot trigger automatic retry loops.
            // Refresh revision is the explicit retry signal after a failed batch.
            LaunchedEffect(workspace.snapshot.receipts, remote.paired, remote.baseURL, remote.refreshRevision, workspace.recoveryRequired) {
                if (!workspace.recoveryRequired && remote.paired) for (local in workspace.snapshot.receipts) {
                    val state = if (local.state == ReceiptState.UNDONE) "undone" else "applied"
                    if (!desktop.needsAcknowledgement(local.plan, state)) continue
                    val envelope = desktop.rememberedProposal(local.plan) ?: continue
                    if (runCatching { desktop.acknowledge(envelope, state) }.isFailure) break
                }
            }
            HabitsApp(repository = repository, desktopSection = { onPlan -> DesktopConnectionSection(desktop, repository, onPlan, runtime.viewModelScope) })
        }
    }
}

class HabitsRuntime(application: Application) : AndroidViewModel(application) {
    val repository = WorkspaceRepository(File(application.filesDir, "workspace.json"))
    val desktop = DesktopConnection(KeystoreDesktopCredentialStore(File(application.filesDir, "desktop-connection.enc")))
}
