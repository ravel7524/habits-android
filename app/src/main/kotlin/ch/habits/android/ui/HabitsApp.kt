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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.LaunchedEffect
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
import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicSemester
import ch.habits.android.desktop.DesktopConnection
import ch.habits.android.semester.BoardProposal
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceRepository
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
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
    integrationsSection: @Composable (focus: String?) -> Unit = {},
    calendarSection: @Composable (day: LocalDate?, onRecovery: () -> Unit) -> Unit = { _, _ -> },
    reportAISection: @Composable (kind: String, snapshot: ch.habits.android.data.WorkspaceSnapshot, scope: CoroutineScope, onSetup: () -> Unit) -> Unit = { _, _, _, _ -> },
    onReportAIExit: () -> Unit = {},
    destination: String? = null,
    destinationRevision: Int = 0,
    desktopConnection: DesktopConnection? = null,
    desktopBoardSection: (@Composable (onPlan: (AcademicPlan) -> Unit, onBoard: (BoardProposal) -> Unit) -> Unit)? = null,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val language = if (snapshot.preferences.language == "system") Locale.getDefault().language else snapshot.preferences.language
    val copy = UiCopy(language)
    val dark = when (snapshot.preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var tab by rememberSaveable { mutableStateOf("today") }
    var plannerMode by rememberSaveable { mutableStateOf("work") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var settingsFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var assistant by rememberSaveable { mutableStateOf(false) }
    var boardImport by rememberSaveable { mutableStateOf(false) }
    var incomingBoard by remember { mutableStateOf<BoardProposal?>(null) }
    var incomingPlan by remember { mutableStateOf<AcademicPlan?>(null) }
    var boardFailure by remember { mutableStateOf<String?>(null) }
    var boardDetailRevision by remember { mutableStateOf(0) }
    var creatingTaskDraft by remember { mutableStateOf<WorkTask?>(null) }
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
    var editorFailureMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun closeDetail() { settings = false; settingsFocus = null; assistant = false; boardImport = false; incomingBoard = null; incomingPlan = null; report = null; editor = null; creatingTaskDraft = null }
    fun openSettingsSection(section: String?) { editor = null; assistant = false; boardImport = false; report = null; settingsFocus = section; settings = true }
    LaunchedEffect(destination, destinationRevision) {
        if (destination != null) {
            closeDetail()
            when (destination) {
                "planner" -> { tab = "planner"; plannerMode = "work" }
                "semester" -> { tab = "planner"; plannerMode = "semester" }
                "habits" -> tab = "habits"
                "morning", "evening", "weekly" -> report = destination
                else -> tab = "today"
            }
        }
    }
    fun action(message: String, closeEditor: Boolean = false, undo: (suspend () -> Unit)? = null, onFailure: ((String) -> Unit)? = null, block: suspend () -> Unit) {
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
                val failureMessage = failure.message ?: copy.t("The change could not be confirmed.", "Die Änderung konnte nicht bestätigt werden.", "No se pudo confirmar el cambio.")
                if (onFailure != null) onFailure(failureMessage)
                else if (editor != null) editorFailureMessage = failureMessage
                else snackbar.showSnackbar(failureMessage, withDismissAction = true, duration = SnackbarDuration.Long)
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
    fun prepare(semester: AcademicSemester, course: AcademicCourse, week: Int?, assessment: AcademicAssessment?) {
        val existing = repository.state.value.snapshot.tasks.firstOrNull { !it.archived &&
            if (assessment != null) it.sourceAssessmentID == assessment.id else it.sourceCourseID == course.id && it.sourceWeek == week }
        editingTask = existing
        creatingTaskDraft = if (existing != null) null else WorkTask(
            title = if (assessment != null) copy.t("Prepare for ", "Vorbereiten: ", "Preparar ") + assessment.title
                else copy.t("Review ", "Wiederholen: ", "Repasar ") + course.name + " · " + copy.t("Week ", "Woche ", "Semana ") + week,
            minutes = 0, sourceZone = semester.timeZoneIdentifier,
            sourceAssessmentID = assessment?.id, sourceCourseID = if (assessment == null) course.id else null,
            sourceWeek = if (assessment == null) week else null)
        creatingTaskDraft = creatingTaskDraft?.let { draft ->
            val bounded = draft.title.take(200).trimEnd().let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            draft.copy(title = bounded)
        }
        editor = "task"
    }
    val detailOpen = settings || assistant || boardImport || report != null || editor != null
    BackHandler(enabled = detailOpen) { closeDetail() }
    LaunchedEffect(editor) { editorFailureMessage = null }
    // Pending success/Undo feedback from another screen must not intercept the editor footer.
    LaunchedEffect(editor, snackbar.currentSnackbarData) {
        if (editor != null) snackbar.currentSnackbarData?.dismiss()
    }

    HabitsTheme(dark) {
        Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                val heading = when {
                    boardImport -> copy.t("Semester Board", "Semesterübersicht", "Tablero del semestre")
                    assistant -> copy.t("Plan with an assistant", "Mit einem Assistenten planen", "Planificar con un asistente")
                    settings -> copy.settings
                    report != null -> reportTitle(report!!, copy)
                    editor == "task" -> copy.t("Task or assignment", "Aufgabe oder Abgabe", "Tarea o entrega")
                    editor == "appointment" -> copy.t("Appointment", "Termin", "Cita")
                    editor == "habit" -> copy.t("Habit", "Gewohnheit", "Hábito")
                    tab == "planner" -> copy.planner
                    tab == "habits" -> copy.habits
                    else -> copy.today
                }
                CenterAlignedTopAppBar(title = { Text(heading, style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
                    if (detailOpen) IconButton(onClick = { closeDetail() }, modifier = Modifier.testTag(if (assistant) "assistant.plan.close" else "detail.close")) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = copy.close)
                    }
                }, actions = {
                    if (!detailOpen) IconButton(onClick = { openSettingsSection(null) }, modifier = Modifier.testTag("settings.open")) {
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
            }, snackbarHost = { if (editor == null) SnackbarHost(snackbar) }) { padding ->
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
                    boardImport -> SemesterBoardImportScreen(repository, copy, onBack = {
                        closeDetail(); tab = "planner"; plannerMode = "semester"
                    }, desktop = desktopConnection, providedProposal = incomingBoard,
                        onImported = { semesterID = it },
                        desktopSection = { onBoard -> desktopBoardSection?.invoke({ value ->
                            incomingPlan = value; boardImport = false; assistant = true
                        }, onBoard) })
                    assistant -> AssistantPlanScreen(repository, state, selectedSemester?.id, copy,
                        desktopSection = { onPlan ->
                            if (desktopBoardSection != null) desktopBoardSection(onPlan) { value ->
                                incomingBoard = value; assistant = false; boardImport = true
                            } else desktopSection(onPlan)
                        }, providedPlan = incomingPlan)
                    settings -> SettingsScreen(state, copy, busy, focusSection = settingsFocus, integrationsSection = { integrationsSection(settingsFocus) },
                        onPreferences = { value -> action(copy.t("Settings saved", "Einstellungen gespeichert", "Ajustes guardados")) { repository.savePreferences(value) } },
                        onExport = { exporter.launch("habits-backup-${LocalDate.now()}.json") },
                        onRestoreBackup = { action(copy.t("Backup restored", "Sicherung wiederhergestellt", "Copia restaurada")) { repository.restoreBackup() } },
                        onRestoreTask = { value -> action(copy.t("Task restored", "Aufgabe wiederhergestellt", "Tarea restaurada")) { repository.saveTask(value.copy(archived = false)) } },
                        onRestoreHabit = { value -> action(copy.t("Habit restored", "Gewohnheit wiederhergestellt", "Hábito restaurado")) { repository.saveHabit(value.copy(archived = false)) } },
                        onRestoreAppointment = { value -> action(copy.t("Appointment restored", "Termin wiederhergestellt", "Cita restaurada")) { repository.saveAppointment(value.copy(archived = false)) } })
                    report != null -> {
                        val reportKind = report!!
                        ReportScreen(reportKind, snapshot, copy, calendarSection = { calendarSection(LocalDate.now()) { openSettingsSection("calendar") } },
                            aiSection = { reportScope -> reportAISection(reportKind, snapshot, reportScope) { openSettingsSection("localai") } }, onAIExit = onReportAIExit, onCalendarRecovery = { openSettingsSection("calendar") })
                    }
                    editor == "task" -> TaskEditor(editingTask, copy, busy, initialDraft = creatingTaskDraft,
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
                        calendarSection = { calendarSection(null) { openSettingsSection("calendar") } },
                        onSelectSemester = { semesterID = it }, onAssistant = { incomingBoard = null; boardImport = true },
                        onImportBoard = { incomingBoard = null; boardImport = true },
                        onLegacyAssistant = { incomingPlan = null; boardImport = false; assistant = true },
                        onWeekStatus = { selection, status, entry, statuses ->
                            action(copy.t("Week status saved", "Wochenstatus gespeichert", "Estado semanal guardado"), onFailure = { boardFailure = it }) {
                                repository.setWeekStatus(selection.semesterID, selection.courseID, selection.week, status, entry, statuses)
                                boardFailure = null; boardDetailRevision++
                            }
                        },
                        onAssessmentState = { value, status ->
                            action(copy.t("Assessment updated", "Prüfung aktualisiert", "Evaluación actualizada"), onFailure = { boardFailure = it }) {
                                repository.setAssessmentState(value, status); boardFailure = null; boardDetailRevision++
                            }
                        },
                        onPrepare = ::prepare, detailRevision = boardDetailRevision, detailError = boardFailure,
                        recoveryRequired = state.recoveryRequired,
                        onReloadDetail = { boardFailure = null; boardDetailRevision++ },
                        onNewTask = { editingTask = null; creatingTaskDraft = null; editor = "task" }, onEditTask = { editingTask = it; creatingTaskDraft = null; editor = "task" },
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
                    else -> TodayScreen(snapshot, copy, onReport = { report = it }, onPlanner = { tab = "planner" }, calendarSection = { calendarSection(LocalDate.now()) { openSettingsSection("calendar") } }, onCalendarRecovery = { openSettingsSection("calendar") })
                }
            }
        }
        editorFailureMessage?.let { message ->
            AlertDialog(onDismissRequest = { editorFailureMessage = null },
                title = { Text(copy.t("Change could not be confirmed", "Änderung konnte nicht bestätigt werden", "No se pudo confirmar el cambio")) },
                text = { Text(message + "\n\n" + copy.t("Check your saved records before trying again. Your draft stays open.",
                    "Prüfe deine gespeicherten Einträge vor einem neuen Versuch. Dein Entwurf bleibt geöffnet.",
                    "Comprueba los registros guardados antes de reintentarlo. Tu borrador sigue abierto.")) },
                confirmButton = { TextButton(onClick = { editorFailureMessage = null }, modifier = Modifier.testTag("editor.saveError.close")) { Text(copy.close) } },
                modifier = Modifier.testTag("editor.saveError"))
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
