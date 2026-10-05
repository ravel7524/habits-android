package ch.habits.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.habits.android.core.AcademicPlan
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceRepository
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native Android entry point. A desktop connection is supplied by the real transport. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HabitsApp(
    repository: WorkspaceRepository,
    desktopSection: @Composable (onPlan: (AcademicPlan) -> Unit) -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val language = if (snapshot.preferences.language == "system") Locale.getDefault().language else snapshot.preferences.language
    val copy = UiCopy(language)
    val dark = when (snapshot.preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var tab by rememberSaveable { mutableStateOf("today") }
    var plannerMode by rememberSaveable { mutableStateOf("work") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var assistant by rememberSaveable { mutableStateOf(false) }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editingTask by remember { mutableStateOf<WorkTask?>(null) }
    var editingAppointment by remember { mutableStateOf<Appointment?>(null) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var semesterID by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSemester = snapshot.semesters.firstOrNull { it.id == semesterID && !it.archived } ?: snapshot.semesters.firstOrNull { !it.archived }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun closeDetail() { settings = false; assistant = false; report = null; editor = null }
    fun action(message: String, closeEditor: Boolean = false, undo: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
                if (closeEditor) editor = null
                busy = false
                val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) copy.undo else null,
                    withDismissAction = true, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed && undo != null) {
                    busy = true
                    undo()
                    busy = false
                    snackbar.showSnackbar(copy.t("Change undone", "Änderung rückgängig gemacht", "Cambio deshecho"), duration = SnackbarDuration.Short)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                busy = false
                snackbar.showSnackbar(failure.message ?: copy.t("The action could not be saved.", "Die Änderung konnte nicht gespeichert werden.", "No se pudo guardar la acción."),
                    withDismissAction = true, duration = SnackbarDuration.Long)
            } finally { busy = false }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) action(copy.t("Backup exported", "Sicherung exportiert", "Copia exportada")) {
            val bytes = repository.exportRawData()
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("The selected destination could not be opened.")
                output.use { it.write(bytes); it.flush() }
            }
        }
    }
    val detailOpen = settings || assistant || report != null || editor != null
    BackHandler(enabled = detailOpen) { closeDetail() }

    HabitsTheme(dark) {
        Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                val heading = when {
                    assistant -> copy.t("Plan with AI", "Mit KI planen", "Planificar con IA")
                    settings -> copy.settings
                    report != null -> reportTitle(report!!, copy)
                    editor == "task" -> copy.t("Task or assignment", "Aufgabe oder Abgabe", "Tarea o entrega")
                    editor == "appointment" -> copy.t("Appointment", "Termin", "Cita")
                    editor == "habit" -> copy.t("Habit", "Gewohnheit", "Hábito")
                    tab == "planner" -> copy.planner
                    tab == "habits" -> copy.habits
                    else -> copy.today
                }
                CenterAlignedTopAppBar(title = { Text(heading) }, navigationIcon = {
                    if (detailOpen) IconButton(onClick = { closeDetail() }, modifier = Modifier.testTag(if (assistant) "assistant.plan.close" else "detail.close")) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = copy.close)
                    }
                }, actions = {
                    if (!detailOpen) IconButton(onClick = { settings = true }, modifier = Modifier.testTag("settings.open")) {
                        Icon(Icons.Outlined.Settings, contentDescription = copy.settings)
                    }
                })
            },
            bottomBar = {
                if (!detailOpen) NavigationBar {
                    for (value in listOf("today", "planner", "habits")) NavigationBarItem(selected = tab == value,
                        onClick = { tab = value }, icon = {
                            Icon(when (value) { "planner" -> Icons.Outlined.ListAlt; "habits" -> Icons.Outlined.CheckCircle; else -> Icons.Outlined.Today }, contentDescription = null)
                        }, label = { Text(when (value) { "planner" -> copy.planner; "habits" -> copy.habits; else -> copy.today }) },
                        modifier = Modifier.testTag("nav.$value"))
                }
            }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.recoveryRequired) Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).testTag("workspace.recovery")
                    .semantics { liveRegion = LiveRegionMode.Polite }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(copy.t("Your local data needs recovery", "Deine lokalen Daten benötigen Wiederherstellung", "Tus datos locales necesitan recuperación"),
                            style = MaterialTheme.typography.titleMedium)
                        Text(copy.t("The original file has been preserved. Saving is paused. Open Settings to export it or restore the previous backup.",
                            "Die Originaldatei bleibt erhalten. Speichern ist angehalten. Öffne Einstellungen zum Exportieren oder Wiederherstellen der vorherigen Sicherung.",
                            "Se ha conservado el archivo original. El guardado está pausado. Abre Ajustes para exportarlo o restaurar la copia anterior."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                val writable = !busy && !state.recoveryRequired
                when {
                    assistant -> AssistantPlanScreen(repository, state, selectedSemester?.id, copy, desktopSection)
                    settings -> SettingsScreen(state, copy, busy,
                        onPreferences = { value -> action(copy.t("Settings saved", "Einstellungen gespeichert", "Ajustes guardados")) { repository.savePreferences(value) } },
                        onExport = { exporter.launch("habits-backup-${LocalDate.now()}.json") },
                        onRestoreBackup = { action(copy.t("Backup restored", "Sicherung wiederhergestellt", "Copia restaurada")) { repository.restoreBackup() } },
                        onRestoreTask = { value -> action(copy.t("Task restored", "Aufgabe wiederhergestellt", "Tarea restaurada")) { repository.saveTask(value.copy(archived = false)) } },
                        onRestoreHabit = { value -> action(copy.t("Habit restored", "Gewohnheit wiederhergestellt", "Hábito restaurado")) { repository.saveHabit(value.copy(archived = false)) } },
                        onRestoreAppointment = { value -> action(copy.t("Appointment restored", "Termin wiederhergestellt", "Cita restaurada")) { repository.saveAppointment(value.copy(archived = false)) } })
                    report != null -> ReportScreen(report!!, snapshot, copy)
                    editor == "task" -> TaskEditor(editingTask, copy, busy,
                        onSave = { value ->
                            val previous = editingTask
                            var saved: WorkTask? = null
                            action(copy.t("Task saved", "Aufgabe gespeichert", "Tarea guardada"), closeEditor = true,
                                undo = { undoTaskChange(repository, checkNotNull(saved), previous) }) {
                                repository.saveTask(value)
                                saved = repository.state.value.snapshot.tasks.first { it.id == value.id.lowercase() }
                            }
                        }, onArchive = { value ->
                            var archived: WorkTask? = null
                            action(copy.t("Task archived", "Aufgabe archiviert", "Tarea archivada"), closeEditor = true,
                                undo = { undoTaskChange(repository, checkNotNull(archived), value) }) {
                                repository.archiveTask(value.id)
                                archived = repository.state.value.snapshot.tasks.first { it.id == value.id }
                            }
                        })
                    editor == "appointment" -> AppointmentEditor(editingAppointment, copy, busy,
                        onSave = { value ->
                            val previous = editingAppointment
                            action(copy.t("Appointment saved", "Termin gespeichert", "Cita guardada"), closeEditor = true,
                                undo = { if (previous != null) repository.saveAppointment(previous) else repository.archiveAppointment(value.id) }) { repository.saveAppointment(value) }
                        }, onArchive = { value -> action(copy.t("Appointment archived", "Termin archiviert", "Cita archivada"), closeEditor = true,
                            undo = { repository.saveAppointment(value.copy(archived = false)) }) { repository.archiveAppointment(value.id) } })
                    editor == "habit" -> HabitEditor(editingHabit, copy, busy,
                        onSave = { value ->
                            val previous = editingHabit
                            action(copy.t("Habit saved", "Gewohnheit gespeichert", "Hábito guardado"), closeEditor = true,
                                undo = { if (previous != null) repository.saveHabit(previous) else repository.archiveHabit(value.id) }) { repository.saveHabit(value) }
                        }, onArchive = { value -> action(copy.t("Habit archived; logs retained", "Gewohnheit archiviert; Protokolle erhalten", "Hábito archivado; registros conservados"), closeEditor = true,
                            undo = { repository.saveHabit(value.copy(archived = false)) }) { repository.archiveHabit(value.id) } })
                    tab == "planner" -> PlannerScreen(snapshot, copy, writable, plannerMode, { plannerMode = it }, selectedSemester?.id,
                        onSelectSemester = { semesterID = it }, onAssistant = { assistant = true },
                        onNewTask = { editingTask = null; editor = "task" }, onEditTask = { editingTask = it; editor = "task" },
                        onCompleteTask = { value ->
                            var changed: WorkTask? = null
                            action(copy.t("Task updated", "Aufgabe aktualisiert", "Tarea actualizada"),
                                undo = { undoTaskChange(repository, checkNotNull(changed), value) }) {
                                repository.completeTask(value.id, !value.completed)
                                changed = repository.state.value.snapshot.tasks.first { it.id == value.id }
                            }
                        },
                        onNewAppointment = { editingAppointment = null; editor = "appointment" }, onEditAppointment = { editingAppointment = it; editor = "appointment" },
                        onCompleteAssessment = { value -> action(copy.t("Assessment updated", "Prüfung aktualisiert", "Evaluación actualizada"),
                            undo = { repository.completeAssessment(value.id, value.completed) }) { repository.completeAssessment(value.id, !value.completed) } })
                    tab == "habits" -> HabitsScreen(snapshot, copy, writable,
                        onNew = { editingHabit = null; editor = "habit" }, onEdit = { editingHabit = it; editor = "habit" },
                        onLog = { habit, completed ->
                            val today = LocalDate.now().toString()
                            val previous = WorkspaceFacts.today(snapshot).completedHabitIDs.contains(habit.id)
                            action(copy.t("Habit log updated", "Gewohnheitsprotokoll aktualisiert", "Registro actualizado"),
                                undo = { repository.logHabit(habit.id, today, previous) }) { repository.logHabit(habit.id, today, completed) }
                        })
                    else -> TodayScreen(snapshot, copy, onReport = { report = it }, onPlanner = { tab = "planner" })
                }
            }
        }
    }
}

/** Restore the reviewed version only; a later edit or completion invalidates Undo. */
private suspend fun undoTaskChange(repository: WorkspaceRepository, expected: WorkTask, previous: WorkTask?) {
    val current = repository.state.value.snapshot.tasks.firstOrNull { it.id == expected.id }
    check(current == expected) { "This task changed after saving. Keep its newer changes; Undo is unavailable." }
    if (previous == null) repository.archiveTask(expected.id)
    else repository.saveTask(previous.copy(updatedAt = expected.updatedAt))
}
