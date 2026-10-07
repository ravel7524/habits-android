@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.habits.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicSemester
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicStatus
import ch.habits.android.data.AcademicWeekEntry
import ch.habits.android.semester.SemesterBoardProjection
import ch.habits.android.data.Appointment
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import java.time.LocalDate

@Composable
internal fun PlannerScreen(snapshot: WorkspaceSnapshot, copy: UiCopy, writesEnabled: Boolean,
    mode: String, onMode: (String) -> Unit,
    selectedSemesterID: String?, onSelectSemester: (String) -> Unit, onAssistant: () -> Unit,
    onNewTask: () -> Unit, onEditTask: (WorkTask) -> Unit, onCompleteTask: (WorkTask) -> Unit,
    onNewAppointment: () -> Unit, onEditAppointment: (Appointment) -> Unit,
    onCompleteAssessment: (AcademicAssessment) -> Unit,
    calendarSection: @Composable () -> Unit = {},
    onImportBoard: () -> Unit = onAssistant,
    onLegacyAssistant: () -> Unit = onAssistant,
    onWeekStatus: (SemesterWeekSelection, String?, AcademicWeekEntry?, List<AcademicStatus>) -> Unit = { _, _, _, _ -> },
    onAssessmentState: (AcademicAssessment, String) -> Unit = { assessment, value ->
        if ((value == "completed") != assessment.completed) onCompleteAssessment(assessment)
    },
    onPrepare: (AcademicSemester, AcademicCourse, Int?, AcademicAssessment?) -> Unit = { _, _, _, _ -> },
    detailRevision: Int = 0, detailError: String? = null, onReloadDetail: () -> Unit = {},
    recoveryRequired: Boolean = false,
) {
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var selectedAssessment by remember { mutableStateOf<AcademicAssessment?>(null) }
    var selectedWeek by remember { mutableStateOf<SemesterWeekSelection?>(null) }
    var allAssessmentsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(detailRevision) {
        selectedAssessment = selectedAssessment?.let { old -> snapshot.assessments.firstOrNull { it.id == old.id && !it.archived } }
    }
    val semesters = snapshot.semesters.filterNot { it.archived }
    val semester = semesters.firstOrNull { it.id == selectedSemesterID } ?: semesters.firstOrNull()
    val renderedShowCompleted = showCompleted
    val tasks = snapshot.tasks.filter { !it.archived && (renderedShowCompleted || !it.completed) }
        .sortedWith(compareBy<WorkTask>({ it.completed }, { it.dueDate == null }, { it.dueDate.orEmpty() }, { it.title }))
    val allAssessments = snapshot.assessments.filter { it.pending }
        .sortedWith(compareBy({ it.dueDate }, { it.dueTime.orEmpty() }))
    val appointments = snapshot.appointments.filterNot { it.archived }.sortedWith(compareBy({ it.startDate }, { it.startTime }))
    val courses = snapshot.courses.filter { it.semesterID == semester?.id && !it.archived }
    val semesterAssessments = snapshot.assessments.filter { it.semesterID == semester?.id && !it.archived }
    val modes = listOf("work", "schedule", "semester")
    val renderedMode = mode.takeIf { it in modes } ?: "work"
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    if (renderedMode == "semester") {
        Column(Modifier.fillMaxSize()) {
            PlannerModeTabs(modes, renderedMode, copy, onMode)
            SemesterBoardScreen(snapshot, semester?.id, copy, writesEnabled, onSelectSemester,
                onAssistant, onImportBoard,
                onWeek = { selectedWeek = it; selectedAssessment = null; allAssessmentsOpen = false; onReloadDetail() },
                onAssessment = { selectedAssessment = it; onReloadDetail() },
                onAllAssessments = { allAssessmentsOpen = true; selectedWeek = null }, onLegacyAssistant = onLegacyAssistant)
        }
    } else LazyColumn(Modifier.testTag("planner.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "planner.modes") {
            SecondaryTabRow(selectedTabIndex = modes.indexOf(renderedMode), containerColor = MaterialTheme.colorScheme.background) {
                for (value in modes) Tab(selected = renderedMode == value,
                    onClick = { onMode(value) }, text = { Text(when (value) {
                        "schedule" -> copy.schedule; "semester" -> copy.semester; else -> copy.work
                    }) }, modifier = Modifier.testTag("planner.mode.$value"))
            }
        }
        when (renderedMode) {
            "work" -> {
                item(key = "work.header") {
                    Text(copy.t("Your next steps", "Deine nächsten Schritte", "Tus próximos pasos"), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(copy.quantity(snapshot.tasks.count { !it.archived && !it.completed }, "open task", "open tasks", "offene Aufgabe", "offene Aufgaben", "tarea pendiente", "tareas pendientes"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = onNewTask, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("planner.add.task")) {
                        Text(copy.t("Add task or assignment", "Aufgabe oder Abgabe hinzufügen", "Añadir tarea o entrega"))
                    }
                    FilterChip(selected = renderedShowCompleted, onClick = { showCompleted = !showCompleted },
                        label = { Text(copy.t("Show completed", "Erledigte anzeigen", "Mostrar completadas")) }, modifier = Modifier.testTag("planner.showCompleted"))
                }
                item(key = "work.tasks.header") { PlannerSection(copy.t("Tasks & preparation", "Aufgaben & Vorbereitung", "Tareas y preparación")) }
                if (tasks.isEmpty()) item(key = "work.empty") { PlannerEmpty(copy.t("Room for your next step", "Platz für deinen nächsten Schritt", "Espacio para tu próximo paso"),
                    if (snapshot.tasks.any { !it.archived && it.completed }) copy.t("Your open list is clear. Show completed work to see what you have finished.",
                        "Deine offene Liste ist leer. Zeige erledigte Aufgaben, um deine Abschlüsse zu sehen.",
                        "No quedan tareas pendientes. Muestra las completadas para ver lo que has terminado.") else copy.t("Add a task or assignment above. You can leave the deadline open.",
                        "Füge oben eine Aufgabe oder Abgabe hinzu. Eine Frist ist optional.",
                        "Añade una tarea o entrega arriba. La fecha límite es opcional.")) }
                items(tasks, key = { "task-" + it.id }) { task ->
                    val sourceAssessment = snapshot.assessments.firstOrNull { it.id == task.sourceAssessmentID && !it.archived }
                    val sourceCourse = snapshot.courses.firstOrNull { it.id == task.sourceCourseID && !it.archived }
                    val sourceWeek = task.sourceWeek
                    val sourceSemester = snapshot.semesters.firstOrNull { !it.archived && it.id == (sourceAssessment?.semesterID ?: sourceCourse?.semesterID) }
                    val sourceLabel = sourceAssessment?.title ?: sourceCourse?.let { it.name + " · " + copy.t("Week ", "Woche ", "Semana ") + task.sourceWeek }
                    val openSource: (() -> Unit)? = if (sourceSemester == null) null else {
                        {
                            onSelectSemester(sourceSemester.id); onMode("semester"); onReloadDetail()
                            if (sourceAssessment != null) selectedAssessment = sourceAssessment
                            else if (sourceCourse != null && sourceWeek != null) selectedWeek = SemesterWeekSelection(sourceSemester.id, sourceCourse.id, sourceWeek)
                        }
                    }
                    TaskRow(task, copy, writesEnabled, { onEditTask(task) }, { onCompleteTask(task) }, sourceLabel, openSource)
                }
                val assessments = allAssessments.take(5)
                if (assessments.isNotEmpty()) {
                    item(key = "work.assessments.header") { PlannerSection(copy.t("Exams & deadlines", "Prüfungen & Fristen", "Exámenes y plazos")) }
                    items(assessments, key = { "academic-" + it.id }) { assessment ->
                        AssessmentRow(assessment, snapshot, copy) { selectedAssessment = assessment }
                    }
                    item(key = "work.assessments.all") { Text(copy.t("Preparation and assessment completion stay separate.",
                        "Vorbereitung und Prüfungsabschluss bleiben getrennt.",
                        "La preparación y la evaluación se completan por separado."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onMode("semester") }, modifier = Modifier.testTag("planner.allAssessments"), colors = actionColors) {
                            Text(copy.t("All exams and deadlines", "Alle Prüfungen und Fristen", "Todos los exámenes y plazos") + " (${allAssessments.size})")
                        }
                    }
                }
            }
            "schedule" -> {
                item(key = "schedule.header") {
                    Text(copy.schedule, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(copy.t("Appointments you have saved", "Deine gespeicherten Termine", "Tus citas guardadas"),
                        modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNewAppointment, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("planner.add.appointment")) {
                        Text(copy.t("Add appointment", "Termin hinzufügen", "Añadir cita"))
                    }
                }
                if (appointments.isEmpty()) item(key = "schedule.empty") { PlannerEmpty(copy.t("No appointments yet", "Noch keine Termine", "Todavía no hay citas"),
                    copy.t("Add a meeting, lecture or other event. Task deadlines remain in Work.",
                        "Füge ein Treffen, eine Vorlesung oder ein Ereignis hinzu. Aufgabenfristen bleiben unter Aufgaben.",
                        "Añade una reunión, clase u otro evento. Los plazos siguen en Tareas.")) }
                appointments.groupBy { it.startDate }.forEach { (date, events) ->
                    item(key = "schedule.date.$date") { PlannerSection(copy.displayDate(date)) }
                    items(events, key = { "appointment-" + it.id }) { event ->
                        OutlinedCard(onClick = { onEditAppointment(event) }, modifier = Modifier.fillMaxWidth().testTag("planner.appointment.${event.id}"), colors = plannerCardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                Text(if (event.startDate == event.endDate) "${copy.displayTime(event.startTime)} – ${copy.displayTime(event.endTime)}" else
                                    "${copy.displayDate(event.startDate)} · ${copy.displayTime(event.startTime)}\n${copy.displayDate(event.endDate)} · ${copy.displayTime(event.endTime)}",
                                    color = MaterialTheme.colorScheme.primary)
                                copy.zoneDetail(event.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (event.notes.isNotBlank()) Text(event.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(copy.t("Tap to edit", "Antippen zum Bearbeiten", "Toca para editar"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item(key = "schedule.calendar") { calendarSection() }
                item(key = "schedule.disclosure") { Text(copy.t("Saved events do not describe your full availability.", "Gespeicherte Termine zeigen nicht deine gesamte Verfügbarkeit.", "Los eventos guardados no reflejan toda tu disponibilidad."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

        }
    }
    if (allAssessmentsOpen && selectedAssessment == null) AlertDialog(
        onDismissRequest = { allAssessmentsOpen = false },
        title = { Text(copy.t("Exams and deadlines", "Prüfungen und Fristen", "Exámenes y plazos")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp).testTag("semester.assessments.list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (semesterAssessments.isEmpty()) item { Text(copy.t("No assessments recorded", "Keine Prüfungen oder Fristen erfasst", "Sin evaluaciones registradas")) }
                items(semesterAssessments.sortedWith(compareBy({ !it.pending }, { it.dueDate }, { it.dueTime.orEmpty() })), key = { it.id }) { assessment ->
                    AssessmentRow(assessment, snapshot, copy, semester) { selectedAssessment = assessment; onReloadDetail() }
                }
            }
        },
        confirmButton = { TextButton(onClick = { allAssessmentsOpen = false }) { Text(copy.close) } },
        modifier = Modifier.testTag("semester.assessments.dialog"))
    if (selectedAssessment == null) selectedWeek?.let { selection ->
        key(selection, detailRevision) {
            SemesterWeekDetailDialog(snapshot, selection, copy, writesEnabled, detailError,
                onSetStatus = { status, expected, statuses -> onWeekStatus(selection, status, expected, statuses) },
                onReload = onReloadDetail,
                onPrepare = {
                    val sourceSemester = snapshot.semesters.firstOrNull { it.id == selection.semesterID && !it.archived }
                    val sourceCourse = snapshot.courses.firstOrNull { it.id == selection.courseID && !it.archived }
                    if (sourceSemester != null && sourceCourse != null) onPrepare(sourceSemester, sourceCourse, selection.week, null)
                },
                onAssessment = { selectedAssessment = it; onReloadDetail() },
                onDismiss = { selectedWeek = null }, recoveryRequired = recoveryRequired)
        }
    }
    selectedAssessment?.let { current ->
        val latest = snapshot.assessments.firstOrNull { it.id == current.id && !it.archived }
        val changed = latest != current
        val sourceSemester = snapshot.semesters.firstOrNull { it.id == current.semesterID }
        val sourceCourse = snapshot.courses.firstOrNull { it.id == current.courseID }
        AlertDialog(onDismissRequest = { selectedAssessment = null },
            title = { Text(current.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(assessmentKindLabel(current.kind, copy), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(sourceCourse?.name.orEmpty())
                Text(copy.displayDate(current.dueDate) + " · " + (current.dueTime?.let(copy::displayTime) ?: copy.unknownTime))
                copy.zoneDetail(sourceSemester?.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (sourceSemester != null && SemesterBoardProjection.clockNeedsReview(current, sourceSemester)) {
                    Text(copy.t("This source clock cannot be resolved uniquely. Review its date, time and zone; it is excluded from timed reminders and report priorities.",
                        "Diese Quelluhrzeit ist nicht eindeutig auflösbar. Prüfe Datum, Zeit und Zeitzone; sie wird bei zeitgebundenen Erinnerungen und Berichtprioritäten ausgelassen.",
                        "La hora de origen no se puede resolver de forma única. Revisa fecha, hora y zona; se omite en avisos horarios y prioridades del informe."),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("semester.assessment.timing"))
                }
                Text(assessmentStateLabel(current.recordedState, copy), modifier = Modifier.testTag("semester.assessment.state"))
                if (current.notes.isNotBlank()) Text(current.notes)
                if (current.recurrence != null) Text(copy.t("This saved occurrence is edited individually.", "Diese gespeicherte Wiederholung wird einzeln bearbeitet.", "Esta repetición guardada se edita individualmente."), style = MaterialTheme.typography.bodySmall)
                Text(copy.t("Assessment completion and preparation stay separate.", "Prüfungsabschluss und Vorbereitung bleiben getrennt.", "La evaluación y la preparación se completan por separado."), style = MaterialTheme.typography.bodySmall)
                if (changed || detailError != null) {
                    Text(detailError ?: copy.t("This assessment changed while open. Reload before changing it.", "Diese Prüfung wurde geändert. Lade den aktuellen Stand vor einer Änderung.", "Esta evaluación cambió. Recarga los datos antes de modificarla."), color = MaterialTheme.colorScheme.error)
                    if (latest != null) TextButton(onClick = { selectedAssessment = latest; onReloadDetail() }, modifier = Modifier.testTag("semester.assessment.reload")) { Text(copy.t("Reload current data", "Aktuellen Stand laden", "Recargar datos actuales")) }
                }
            } },
            confirmButton = { Column {
                if (current.pending) {
                    TextButton(onClick = { onAssessmentState(current, "completed") }, enabled = writesEnabled && !changed,
                        modifier = Modifier.testTag("assessment.complete"), colors = actionColors) { Text(copy.t("Mark assessment complete", "Prüfung als erledigt markieren", "Marcar evaluación completada")) }
                    TextButton(onClick = { onAssessmentState(current, "dismissed") }, enabled = writesEnabled && !changed,
                        modifier = Modifier.testTag("semester.assessment.dismiss"), colors = actionColors) { Text(copy.t("Dismiss assessment", "Prüfung ausblenden", "Descartar evaluación")) }
                } else TextButton(onClick = { onAssessmentState(current, "pending") }, enabled = writesEnabled && !changed,
                    modifier = Modifier.testTag("assessment.complete"), colors = actionColors) { Text(copy.t("Reopen assessment", "Prüfung wieder öffnen", "Reabrir evaluación")) }
                if (sourceSemester != null && sourceCourse != null) TextButton(onClick = {
                    onPrepare(sourceSemester, sourceCourse, null, current)
                }, enabled = writesEnabled && !changed, modifier = Modifier.testTag("semester.assessment.prepare"), colors = actionColors) {
                    Text(copy.t("Plan preparation", "Vorbereitung planen", "Planificar preparación"))
                }
            } },
            dismissButton = { TextButton(onClick = { selectedAssessment = null }, colors = actionColors) { Text(copy.close) } },
            modifier = Modifier.testTag("assessment.detail"))
    }
}

@Composable
internal fun TaskRow(task: WorkTask, copy: UiCopy, writesEnabled: Boolean, onEdit: () -> Unit, onComplete: () -> Unit, assessmentTitle: String? = null, onOpenSource: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = plannerCardColors()) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = task.completed, onCheckedChange = { onComplete() }, enabled = writesEnabled,
            modifier = Modifier.testTag("planner.complete.${task.id}").semantics {
                contentDescription = (if (task.completed) copy.t("Reopen task: ", "Aufgabe wieder öffnen: ", "Reabrir tarea: ") else
                    copy.t("Complete task: ", "Aufgabe erledigen: ", "Completar tarea: ")) + task.title
            })
        Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(top = 10.dp).testTag("planner.task.${task.id}"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(copy.displayDate(task.dueDate, relative = true, zone = task.sourceZone) + (task.dueTime?.let { " · " + copy.displayTime(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            val kind = if (task.isPreparation) copy.t("Preparation", "Vorbereitung", "Preparación") else
                if (task.kind == "assignment") copy.t("Assignment", "Abgabe", "Entrega") else copy.t("Task", "Aufgabe", "Tarea")
            Text(kind + (if (task.minutes > 0) " · ${task.minutes} " + copy.t("min estimated", "Min. geschätzt", "min estimados") else "") +
                (if (task.priority == "high") " · " + copy.t("High priority", "Hohe Priorität", "Prioridad alta") else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            assessmentTitle?.let { Text(copy.t("For: ", "Für: ", "Para: ") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            copy.zoneDetail(task.sourceZone)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onOpenSource != null) TextButton(onClick = onOpenSource, modifier = Modifier.testTag("planner.source.${task.id}")) {
                Text(copy.t("Open semester source", "Semesterquelle öffnen", "Abrir origen del semestre"))
            }
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End).testTag("planner.edit.${task.id}"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(copy.t("Edit", "Bearbeiten", "Editar"))
            }
        }
    }
    }
}

@Composable
private fun AssessmentRow(assessment: AcademicAssessment, snapshot: WorkspaceSnapshot, copy: UiCopy,
    semester: AcademicSemester? = null, showCourse: Boolean = true, onClick: () -> Unit) {
    val selectedSemester = semester ?: snapshot.semesters.firstOrNull { it.id == assessment.semesterID }
    val week = selectedSemester?.let { value -> runCatching {
        ((java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(value.startDate), LocalDate.parse(assessment.dueDate))) / 7 + 1).toInt()
    }.getOrNull()?.takeIf { it in 1..value.weekCount } }
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("semester.assessment.${assessment.id}"), colors = plannerCardColors()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(assessmentKindLabel(assessment.kind, copy) + " · " + assessmentStateLabel(assessment.recordedState, copy),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(assessment.title, style = MaterialTheme.typography.titleMedium)
        if (showCourse) snapshot.courses.firstOrNull { it.id == assessment.courseID }?.let {
            Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(copy.displayDate(assessment.dueDate, relative = true, zone = selectedSemester?.timeZoneIdentifier) +
            " · " + (assessment.dueTime?.let(copy::displayTime) ?: copy.unknownTime))
        if (selectedSemester != null && SemesterBoardProjection.clockNeedsReview(assessment, selectedSemester)) Text(copy.t("Source time needs review", "Quelluhrzeit prüfen", "Revisar hora de origen"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        week?.let { Text(copy.t("Teaching week ", "Unterrichtswoche ", "Semana lectiva ") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    }
}

internal fun assessmentKindLabel(kind: String, copy: UiCopy): String = when (kind) {
    "exam" -> copy.t("Exam", "Prüfung", "Examen")
    "presentation" -> copy.t("Presentation", "Präsentation", "Presentación")
    "essay" -> copy.t("Essay", "Schriftliche Arbeit", "Trabajo escrito")
    else -> copy.t("Deadline", "Frist", "Plazo")
}

@Composable
private fun PlannerSection(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PlannerEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth(), colors = plannerCardColors()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun plannerCardColors() = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun PlannerModeTabs(modes: List<String>, renderedMode: String, copy: UiCopy, onMode: (String) -> Unit) {
    SecondaryTabRow(selectedTabIndex = modes.indexOf(renderedMode), containerColor = MaterialTheme.colorScheme.background) {
        for (value in modes) Tab(selected = renderedMode == value, onClick = { onMode(value) },
            text = { Text(when (value) { "schedule" -> copy.schedule; "semester" -> copy.semester; else -> copy.work }) },
            modifier = Modifier.testTag("planner.mode.$value"))
    }
}
internal fun assessmentStateLabel(state: String, copy: UiCopy): String = when (state) {
    "completed" -> copy.t("Completed", "Erledigt", "Completado")
    "dismissed" -> copy.t("Dismissed", "Ausgeblendet", "Descartada")
    else -> copy.t("Pending", "Offen", "Pendiente")
}
