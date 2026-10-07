package ch.habits.android

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.habits.android.calendar.CalendarConnection
import ch.habits.android.calendar.CalendarSettingsSection
import ch.habits.android.calendar.CalendarAgendaSection
import ch.habits.android.reminders.ReminderSettingsSection
import ch.habits.android.integrations.WidgetSettingsSection
import ch.habits.android.integrations.IntegrationPanel
import ch.habits.android.localai.ModelSettingsSection
import ch.habits.android.localai.ReportAISection
import ch.habits.android.localai.ReportFacts
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import java.util.Locale
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
    private var destination by mutableStateOf<String?>(null)
    private var destinationRevision by mutableIntStateOf(0)

    private fun readDestination(value: Intent?) {
        destination = value?.getStringExtra("habits.widget.route") ?: when (value?.getStringExtra("habits.destination")) {
            "report" -> value.getStringExtra("habits.report.kind")?.takeIf { it in setOf("morning", "evening", "weekly") }
            "planner", "semester", "habits", "today" -> value.getStringExtra("habits.destination")
            else -> null
        }
        destinationRevision++
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readDestination(intent) }

    override fun onResume() {
        super.onResume()
        runtime.localAI.setForeground(true)
        HabitsAndroidRuntime.scope.launch {
            runtime.repository.refresh()
            HabitsAndroidRuntime.refreshDerived(applicationContext)
        }
        HabitsAndroidRuntime.scope.launch { runtime.calendar.refresh() }
    }
    override fun onStop() { runtime.localAI.setForeground(false); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDestination(intent)
        val repository = runtime.repository
        val desktop = runtime.desktop
        setContent {
            val currentClock by produceState(initialValue = reportClock()) {
                while (true) { value = reportClock(); delay(1_000) }
            }
            // Capture at the parent so deterministic and AI report sections recompose together.
            val reportPeriod = currentClock
            val workspace by repository.state.collectAsStateWithLifecycle()
            val remote by desktop.state.collectAsStateWithLifecycle()
            val calendars by runtime.calendar.state.collectAsStateWithLifecycle()
            val language = workspace.snapshot.preferences.language.let { if (it == "system") Locale.getDefault().language else it }
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
            LaunchedEffect(workspace.snapshot.boardReceipts, remote.paired, remote.baseURL, remote.refreshRevision, workspace.recoveryRequired) {
                if (!workspace.recoveryRequired && remote.paired) for (local in workspace.snapshot.boardReceipts) {
                    val state = if (local.state == ReceiptState.UNDONE) "undone" else "applied"
                    if (!desktop.needsAcknowledgement(local, state)) continue
                    if (runCatching { desktop.acknowledge(local, state) }.isFailure) break
                }
            }
            HabitsApp(repository = repository,
                desktopSection = { onPlan -> DesktopConnectionSection(desktop, repository, onPlan, runtime.viewModelScope) },
                desktopConnection = desktop,
                desktopBoardSection = { onPlan, onBoard -> DesktopConnectionSection(desktop, repository, onPlan, runtime.viewModelScope, onBoard) },
                integrationsSection = { focus ->
                    fun label(en: String, de: String, es: String) = when (language) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
                    IntegrationPanel("calendar", label("Calendar", "Kalender", "Calendario"), focus = focus == "calendar", language = language) { CalendarSettingsSection(runtime.calendar, language) }
                    IntegrationPanel("reminders", label("Notifications", "Mitteilungen", "Notificaciones"), focus = focus == "reminders", language = language) { ReminderSettingsSection(applicationContext, language) }
                    IntegrationPanel("widgets", label("Home-screen widgets", "Widgets auf dem Startbildschirm", "Widgets de inicio"), focus = focus == "widgets", language = language) { WidgetSettingsSection(this@MainActivity, language) }
                    IntegrationPanel("localai", label("On-device AI", "Lokale KI", "IA local"), focus = focus == "localai", language = language) { ModelSettingsSection(runtime.localAI, language) }
                },
                calendarSection = { day, onRecovery -> CalendarAgendaSection(calendars, language, day, onOpenSettings = onRecovery) },
                onReportAIExit = runtime.localAI::cancelCurrentSelection,
                reportAISection = { kind, snapshot, reportScope, onSetup ->
                    val facts = remember(kind, snapshot, calendars, reportPeriod, language) {
                        ReportFacts.build(kind, snapshot, calendars, reportPeriod.first, reportPeriod.second, language)
                    }
                    ReportAISection(runtime.localAI, facts, language, reportScope, snapshot = snapshot, calendar = calendars, onModelSetup = onSetup, currentFingerprint = {
                        val current = repository.state.value.snapshot
                        val currentLanguage = current.preferences.language.let { if (it == "system") Locale.getDefault().language else it }
                        val fresh = reportClock()
                        ReportFacts.build(kind, current, runtime.calendar.state.value, fresh.first, fresh.second, currentLanguage).fingerprint
                    })
                },
                destination = destination, destinationRevision = destinationRevision)
        }
    }
}

class HabitsRuntime(application: Application) : AndroidViewModel(application) {
    val repository = HabitsAndroidRuntime.repository(application)
    val calendar = CalendarConnection(application)
    val localAI = HabitsAndroidRuntime.localAI(application)
    val desktop = DesktopConnection(KeystoreDesktopCredentialStore(File(application.filesDir, "desktop-connection.enc")))
}

private fun reportClock(): Pair<LocalDate, ZoneId> {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone) to zone
}
