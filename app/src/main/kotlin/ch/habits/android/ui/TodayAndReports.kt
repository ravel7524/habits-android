package ch.habits.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.habits.android.data.Appointment
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope

internal fun reportTitle(kind: String, copy: UiCopy) = when (kind) {
    "evening" -> copy.t("Evening report", "Abendbericht", "Informe de la tarde")
    "weekly" -> copy.t("Weekly review", "Wochenrückblick", "Revisión semanal")
    else -> copy.t("Morning report", "Morgenbericht", "Informe de la mañana")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayScreen(
    snapshot: WorkspaceSnapshot,
    copy: UiCopy,
    onReport: (String) -> Unit,
    onPlanner: () -> Unit,
    calendarSection: @Composable () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCalendarRecovery: () -> Unit = {},
) {
    val today = LocalDate.now()
    val facts = WorkspaceFacts.today(snapshot, today)
    val dueTasks = facts.tasks.toList()
    val dueAssessments = facts.assessments.toList()
    val appointments = facts.appointments.toList()
    val scheduledHabits = facts.habits.toList()
    val loggedHabitIDs = facts.completedHabitIDs.toSet()
    val dueCount = dueTasks.size + dueAssessments.size
    val scheduledLogs = scheduledHabits.count { it.id in loggedHabitIDs }
    val dueWork = dueTasks.map { TodayWork("task/${it.id}", it.title, taskKind(it, copy), taskDeadline(it, copy), it.dueDate) } +
        dueAssessments.map { item ->
            val zone = snapshot.semesters.firstOrNull { it.id == item.semesterID }?.timeZoneIdentifier
            TodayWork("assessment/${item.id}", item.title, copy.t("Assessment", "Prüfung / Abgabe", "Evaluación"),
                deadline(item.dueDate, item.dueTime, zone, copy, unknownTime = true), item.dueDate)
        }
    val dueTaskIDs = dueTasks.map { it.id }.toSet()
    val futureTasks = snapshot.tasks.filter { !it.archived && !it.completed && it.id !in dueTaskIDs }
        .sortedWith(compareBy<WorkTask> { it.dueDate ?: "9999-12-31" }.thenBy { it.id })
        .map { TodayWork("task/${it.id}", it.title, taskKind(it, copy), taskDeadline(it, copy), it.dueDate) }
    val dueAssessmentIDs = dueAssessments.map { it.id }.toSet()
    val liveSemesterIDs = snapshot.semesters.filter { !it.archived }.map { it.id }.toSet()
    val liveCourseIDs = snapshot.courses.filter { !it.archived }.map { it.id }.toSet()
    val futureAssessments = snapshot.assessments.filter { !it.archived && !it.completed && it.id !in dueAssessmentIDs &&
        it.semesterID in liveSemesterIDs && it.courseID in liveCourseIDs }.sortedWith(compareBy({ it.dueDate }, { it.id })).map { item ->
        val zone = snapshot.semesters.firstOrNull { it.id == item.semesterID }?.timeZoneIdentifier
        TodayWork("assessment/${item.id}", item.title, copy.t("Assessment", "Prüfung / Abgabe", "Evaluación"),
            deadline(item.dueDate, item.dueTime, zone, copy, unknownTime = true), item.dueDate)
    }
    val work = (if (dueWork.isNotEmpty()) dueWork else futureTasks + futureAssessments)
        .sortedWith(compareBy<TodayWork> { it.orderDate ?: "9999-12-31" }.thenBy { it.key }).toList()
    val preview = work.take(2).toList()

    LazyColumn(Modifier.testTag("today.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "today.date", contentType = "header") {
            Text(copy.displayDate(today.toString()), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "today.work", contentType = "work-card") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (dueCount > 0) copy.t("Next work", "Nächste Aufgaben", "Próximas tareas")
                        else copy.t("Your work", "Deine Aufgaben", "Tus tareas"), style = MaterialTheme.typography.titleLarge)
                    Text(if (dueCount > 0) copy.quantity(dueCount, "deadline due today or earlier", "deadlines due today or earlier",
                        "Frist heute oder früher", "Fristen heute oder früher", "plazo para hoy o antes", "plazos para hoy o antes")
                    else copy.t("No open deadlines due today or earlier.", "Keine offenen Fristen für heute oder früher.", "No hay plazos abiertos para hoy o antes."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (preview.isEmpty()) Text(copy.t("Add or review work in Planner.", "Aufgaben im Planer hinzufügen oder prüfen.", "Añade o revisa tareas en el Planificador."))
                    preview.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RecordRow(item.title, item.detail, item.kind, Modifier.testTag("today.work.${item.key}"))
                    }
                    if (work.size > preview.size) Text(copy.t("Showing ${preview.size} of ${work.size} open items. Planner has the full list.",
                        "${preview.size} von ${work.size} offenen Einträgen angezeigt. Die ganze Liste steht im Planer.",
                        "Se muestran ${preview.size} de ${work.size} elementos abiertos. La lista completa está en el Planificador."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onPlanner, modifier = Modifier.fillMaxWidth().testTag("today.openPlanner")) { Text(copy.t("Open Planner", "Planer öffnen", "Abrir Planificador")) }
                }
            }
        }
        item(key = "today.summary", contentType = "summary") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FactBadge(copy.quantity(appointments.size, "saved appointment", "saved appointments", "gespeicherter Termin", "gespeicherte Termine", "cita guardada", "citas guardadas"))
                FactBadge(if (scheduledHabits.isEmpty()) copy.t("No habits scheduled", "Keine Gewohnheiten geplant", "Sin hábitos programados")
                    else copy.t("$scheduledLogs/${scheduledHabits.size} planned habits logged", "$scheduledLogs/${scheduledHabits.size} geplante Gewohnheiten protokolliert",
                        "$scheduledLogs/${scheduledHabits.size} hábitos programados registrados"))
            }
        }
        item(key = "today.schedule.heading", contentType = "heading") { SectionHeading(copy.t("Saved appointments today", "Gespeicherte Termine heute", "Citas guardadas para hoy")) }
        if (appointments.isEmpty()) item(key = "today.schedule.empty", contentType = "empty") {
            QuietEmpty(copy.t("No saved appointments today.", "Keine gespeicherten Termine heute.", "No hay citas guardadas para hoy."))
        }
        items(appointments, key = { "today.appointment.${it.id}" }, contentType = { "appointment" }) { appointment ->
            RecordRow(appointment.title, appointmentTime(appointment, copy), null)
        }
        item(key = "today.calendar", contentType = "calendar") { calendarSection() }
        item(key = "today.habits.heading", contentType = "heading") { SectionHeading(copy.habits) }
        if (scheduledHabits.isEmpty()) item(key = "today.habits.empty", contentType = "empty") {
            QuietEmpty(copy.t("No habits scheduled today. Existing logs remain in Habits.", "Heute keine Gewohnheiten geplant. Erfasste Protokolle bleiben unter Gewohnheiten.",
                "No hay hábitos programados para hoy. Los registros existentes siguen en Hábitos."))
        }
        items(scheduledHabits, key = { "today.habit.${it.id}" }, contentType = { "habit" }) { habit ->
            RecordRow(habit.title, if (habit.id in loggedHabitIDs) copy.t("Completion logged today", "Abschluss heute protokolliert", "Finalización registrada hoy")
                else copy.t("Scheduled · no completion logged today", "Geplant · heute kein Abschluss protokolliert", "Programado · sin finalización registrada hoy"), null)
        }
        item(key = "today.coverage", contentType = "coverage") {
            Text(copy.t("Missing logs do not mean a habit was missed. Calendar coverage is shown in its own section.",
                "Fehlende Protokolle bedeuten nicht, dass eine Gewohnheit verpasst wurde. Die Kalenderabdeckung steht im eigenen Abschnitt.",
                "La falta de registros no significa que se omitiera un hábito. La cobertura del calendario se muestra por separado."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "today.reports.heading", contentType = "heading") { SectionHeading(copy.t("Your reports", "Deine Berichte", "Tus informes")) }
        item(key = "today.reports.description", contentType = "description") {
            Text(copy.t("Plan the day or review what you recorded.", "Den Tag planen oder erfasste Einträge ansehen.", "Planifica el día o revisa lo registrado."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (kind in listOf("morning", "evening", "weekly")) item(key = "today.report.$kind", contentType = "report-action") {
            OutlinedButton(onClick = { onReport(kind) }, modifier = Modifier.fillMaxWidth().testTag("today.report.$kind"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text(reportTitle(kind, copy)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportScreen(
    kind: String,
    snapshot: WorkspaceSnapshot,
    copy: UiCopy,
    calendarSection: @Composable () -> Unit = {},
    aiSection: @Composable (CoroutineScope) -> Unit = {},
    onAIExit: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCalendarRecovery: () -> Unit = {},
) {
    val reportScope = rememberCoroutineScope()
    DisposableEffect(kind) { onDispose { onAIExit() } }
    val today = LocalDate.now()
    val start = if (kind == "weekly") today.minusDays(6) else today
    val recorded = WorkspaceFacts.report(snapshot, start, today.plusDays(1))
    val plan = WorkspaceFacts.today(snapshot, today)
    val tasks = plan.tasks.toList()
    val assessments = plan.assessments.toList()
    val appointments = plan.appointments.toList()
    val habits = plan.habits.toList()
    val loggedHabitIDs = plan.completedHabitIDs.toSet()
    val completedTasks = recorded.completedTasks.toList()
    val completedAssessments = recorded.completedAssessments.toList()
    val habitLogs = recorded.habitLogs.toList()
    val habitNames = snapshot.habits.associate { it.id to it.title }.toMap()
    val untimed = snapshot.tasks.count { it.completed && it.completedAt == null } + snapshot.assessments.count { it.completed && it.completedAt == null }
    var allDeadlines by rememberSaveable(kind) { mutableStateOf(false) }
    var allCompletions by rememberSaveable(kind) { mutableStateOf(false) }
    var allLogs by rememberSaveable(kind) { mutableStateOf(false) }
    var details by rememberSaveable(kind) { mutableStateOf(false) }
    val shownTasks = (if (allDeadlines) tasks else tasks.take(6)).toList()
    val shownAssessments = (if (allDeadlines) assessments else assessments.take(4)).toList()
    val shownCompletedTasks = (if (allCompletions) completedTasks else completedTasks.take(8)).toList()
    val shownCompletedAssessments = (if (allCompletions) completedAssessments else completedAssessments.take(4)).toList()
    val shownLogs = (if (allLogs) habitLogs else habitLogs.take(8)).toList()

    LazyColumn(Modifier.testTag("report.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "report.period", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (kind == "weekly") "${copy.displayDate(start.toString())} – ${copy.displayDate(today.toString())}"
                    else copy.displayDate(today.toString()), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FactBadge(copy.t("Offline · deterministic summary", "Offline · deterministische Zusammenfassung", "Sin conexión · resumen determinista"),
                    Modifier.testTag("report.source"))
            }
        }
        if (kind == "morning") {
            item(key = "report.deadlines.heading", contentType = "heading") {
                SectionHeading(copy.t("Due today or earlier", "Heute oder früher fällig", "Para hoy o antes"))
            }
            if (tasks.isEmpty() && assessments.isEmpty()) item(key = "report.deadlines.empty", contentType = "empty") {
                QuietEmpty(copy.t("No open deadlines are listed for today or earlier.", "Keine offenen Fristen für heute oder früher aufgeführt.",
                    "No se muestran plazos abiertos para hoy o antes."))
            }
            items(shownTasks, key = { "morning-task-${it.id}" }, contentType = { "work" }) { task ->
                RecordRow(task.title, taskDeadline(task, copy), taskKind(task, copy), Modifier.testTag("report.task.${task.id}"))
            }
            items(shownAssessments, key = { "morning-assessment-${it.id}" }, contentType = { "assessment" }) { assessment ->
                val zone = snapshot.semesters.firstOrNull { it.id == assessment.semesterID }?.timeZoneIdentifier
                RecordRow(assessment.title, deadline(assessment.dueDate, assessment.dueTime, zone, copy, unknownTime = true),
                    copy.t("Assessment", "Prüfung / Abgabe", "Evaluación"))
            }
            if (shownTasks.size + shownAssessments.size < tasks.size + assessments.size) item(key = "report.deadlines.more", contentType = "expansion") {
                ShowAll(shownTasks.size + shownAssessments.size, tasks.size + assessments.size, copy, "report.deadlines.showAll") { allDeadlines = true }
            }
            item(key = "report.schedule.heading", contentType = "heading") { SectionHeading(copy.t("Saved schedule", "Gespeicherte Termine", "Agenda guardada")) }
            if (appointments.isEmpty()) item(key = "report.schedule.empty", contentType = "empty") {
                QuietEmpty(copy.t("No saved appointments today.", "Keine gespeicherten Termine heute.", "No hay citas guardadas para hoy."))
            }
            items(appointments, key = { "morning-appointment-${it.id}" }, contentType = { "appointment" }) { appointment ->
                RecordRow(appointment.title, appointmentTime(appointment, copy), null)
            }
            item(key = "report.habits.heading", contentType = "heading") { SectionHeading(copy.habits) }
            item(key = "report.habits.summary", contentType = "summary") {
                Text(if (habits.isEmpty()) copy.t("No habits scheduled today.", "Heute keine Gewohnheiten geplant.", "Sin hábitos programados para hoy.") else
                    copy.t("${habits.count { it.id in loggedHabitIDs }}/${habits.size} scheduled habits have a completion log today.",
                        "${habits.count { it.id in loggedHabitIDs }}/${habits.size} geplante Gewohnheiten haben heute ein Abschlussprotokoll.",
                        "${habits.count { it.id in loggedHabitIDs }}/${habits.size} hábitos programados tienen un registro de finalización hoy."),
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(habits, key = { "morning-habit-${it.id}" }, contentType = { "habit" }) { habit ->
                RecordRow(habit.title, if (habit.id in loggedHabitIDs) copy.t("Completion logged", "Abschluss protokolliert", "Finalización registrada")
                    else copy.t("Scheduled · nothing logged yet", "Geplant · noch kein Protokoll", "Programado · aún sin registro"), null)
            }
            item(key = "report.calendar", contentType = "calendar") { calendarSection() }
        } else {
            item(key = "report.completions.heading", contentType = "heading") { SectionHeading(copy.t("Recorded completions", "Erfasste Abschlüsse", "Finalizaciones registradas")) }
            item(key = "report.completions.summary", contentType = "summary") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactBadge(copy.quantity(completedTasks.size, "task or assignment", "tasks or assignments", "Aufgabe oder Abgabe", "Aufgaben oder Abgaben", "tarea o entrega", "tareas o entregas"),
                        Modifier.testTag("report.completedTasks"))
                    FactBadge(copy.quantity(completedAssessments.size, "assessment", "assessments", "Prüfung / Abgabe", "Prüfungen / Abgaben", "evaluación", "evaluaciones"))
                    FactBadge(copy.quantity(habitLogs.size, "habit log", "habit logs", "Gewohnheitsprotokoll", "Gewohnheitsprotokolle", "registro de hábito", "registros de hábitos"),
                        Modifier.testTag("report.habitLogs"))
                }
            }
            if (completedTasks.isEmpty() && completedAssessments.isEmpty() && habitLogs.isEmpty()) item(key = "report.completions.empty", contentType = "empty") {
                QuietEmpty(copy.t("No timestamped completions or completed habit logs are listed for this period.",
                    "Für diesen Zeitraum sind keine Abschlüsse mit Zeitstempel oder abgeschlossene Gewohnheitsprotokolle aufgeführt.",
                    "No se muestran finalizaciones con fecha ni registros de hábitos completados para este periodo."))
            }
            items(shownCompletedTasks, key = { "recorded-task-${it.id}" }, contentType = { "completed-work" }) { task ->
                RecordRow(task.title, completionDate(task.completedAt, copy), taskKind(task, copy))
            }
            items(shownCompletedAssessments, key = { "recorded-assessment-${it.id}" }, contentType = { "completed-assessment" }) { assessment ->
                RecordRow(assessment.title, completionDate(assessment.completedAt, copy), copy.t("Assessment completed", "Prüfung / Abgabe abgeschlossen", "Evaluación completada"))
            }
            if (shownCompletedTasks.size + shownCompletedAssessments.size < completedTasks.size + completedAssessments.size) item(key = "report.completions.more", contentType = "expansion") {
                ShowAll(shownCompletedTasks.size + shownCompletedAssessments.size, completedTasks.size + completedAssessments.size, copy, "report.completions.showAll") { allCompletions = true }
            }
            if (habitLogs.isNotEmpty()) item(key = "report.logs.heading", contentType = "heading") { SectionHeading(copy.t("Habit logs", "Gewohnheitsprotokolle", "Registros de hábitos")) }
            items(shownLogs, key = { "recorded-habit-${it.habitID}-${it.date}" }, contentType = { "habit-log" }) { log ->
                RecordRow(habitNames[log.habitID] ?: copy.t("Recorded habit", "Erfasste Gewohnheit", "Hábito registrado"),
                    copy.displayDate(log.date), copy.t("Completion logged", "Abschluss protokolliert", "Finalización registrada"))
            }
            if (shownLogs.size < habitLogs.size) item(key = "report.logs.more", contentType = "expansion") {
                ShowAll(shownLogs.size, habitLogs.size, copy, "report.logs.showAll") { allLogs = true }
            }
        }
        item(key = "report.coverage.summary", contentType = "coverage") {
            Text(copy.t("Missing logs never mean missed habits. Preparation and assessment completion stay separate.",
                "Fehlende Protokolle bedeuten keine verpassten Gewohnheiten. Vorbereitung und Prüfungsabschluss bleiben getrennt.",
                "La falta de registros no significa hábitos omitidos. La preparación y las evaluaciones se completan por separado."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (untimed > 0) Text(copy.quantity(untimed, "completed item has no completion date and is excluded", "completed items have no completion date and are excluded",
                "abgeschlossener Eintrag ohne Abschlussdatum ist ausgeschlossen", "abgeschlossene Einträge ohne Abschlussdatum sind ausgeschlossen",
                "elemento completado sin fecha de finalización se excluye", "elementos completados sin fecha de finalización se excluyen"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).testTag("report.untimed"))
        }
        item(key = "report.ai", contentType = "ai") { aiSection(reportScope) }
        item(key = "report.details.toggle", contentType = "disclosure") {
            TextButton(onClick = { details = !details }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("report.details")) {
                Text(if (details) copy.t("Hide report details", "Berichtsdetails ausblenden", "Ocultar detalles")
                    else copy.t("About these facts", "Über diese Fakten", "Acerca de estos datos"))
            }
        }
        if (details) item(key = "report.details.body", contentType = "coverage") {
            Text(copy.t("These facts come from records on this phone. Completed tasks and assessments need a recorded completion date to enter this period. Habit logs reflect the dates you recorded. " +
                "Calendar coverage is reported in its own block. None of these sources establishes attendance, free time or time studied; task estimates describe plans. Optional AI only chooses existing fact IDs.",
                "Diese Fakten stammen aus Einträgen auf diesem Telefon. Aufgaben und Prüfungen benötigen ein erfasstes Abschlussdatum für diesen Zeitraum. Gewohnheitsprotokolle zeigen deine erfassten Daten. " +
                    "Die Kalenderabdeckung steht im eigenen Abschnitt. Keine dieser Quellen belegt Teilnahme, freie Zeit oder Lernzeit; Aufwandsschätzungen beschreiben Pläne. Optionale KI wählt nur vorhandene Fakten-IDs.",
                "Estos datos proceden de registros de este teléfono. Las tareas y evaluaciones necesitan una fecha de finalización para este periodo. Los hábitos muestran las fechas registradas. " +
                    "La cobertura del calendario se indica por separado. Ninguna fuente demuestra asistencia, tiempo libre ni tiempo estudiado; las estimaciones describen planes. La IA opcional solo elige datos existentes."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class TodayWork(val key: String, val title: String, val kind: String, val detail: String, val orderDate: String?)

@Composable
private fun RecordRow(title: String, detail: String, kind: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        kind?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FactBadge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun QuietEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ShowAll(shown: Int, total: Int, copy: UiCopy, tag: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(copy.t("Showing $shown of $total records", "$shown von $total Einträgen angezeigt", "Se muestran $shown de $total registros"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick, modifier = Modifier.testTag(tag), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
            Text(copy.t("Show all $total", "Alle $total anzeigen", "Mostrar los $total"))
        }
    }
}

private fun taskKind(task: WorkTask, copy: UiCopy): String = if (task.sourceAssessmentID != null) copy.t("Preparation", "Vorbereitung", "Preparación")
    else if (task.kind == "assignment") copy.t("Assignment", "Abgabe", "Entrega") else copy.t("Task", "Aufgabe", "Tarea")
private fun taskDeadline(task: WorkTask, copy: UiCopy): String = deadline(task.dueDate, task.dueTime, task.sourceZone, copy)
private fun deadline(date: String?, time: String?, zone: String?, copy: UiCopy, unknownTime: Boolean = false): String = listOfNotNull(
    copy.displayDate(date, relative = true, zone = zone),
    if (time != null) copy.displayTime(time) else if (unknownTime) copy.unknownTime else null,
    copy.zoneDetail(zone),
).filter { it.isNotBlank() }.joinToString(" · ")
private fun appointmentTime(appointment: Appointment, copy: UiCopy): String {
    val first = "${copy.displayDate(appointment.startDate, relative = true, zone = appointment.timeZoneIdentifier)} · ${copy.displayTime(appointment.startTime)}"
    val end = if (appointment.endDate == appointment.startDate) copy.displayTime(appointment.endTime)
        else "${copy.displayDate(appointment.endDate, relative = true, zone = appointment.timeZoneIdentifier)} · ${copy.displayTime(appointment.endTime)}"
    return "$first – $end" + copy.zoneDetail(appointment.timeZoneIdentifier)?.let { " · $it" }.orEmpty()
}
private fun completionDate(value: String?, copy: UiCopy): String = runCatching {
    val finished = Instant.parse(value).atZone(ZoneId.systemDefault())
    copy.displayDate(finished.toLocalDate().toString()) + " · " + copy.displayTime(finished.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
}.getOrElse { copy.t("Completion date not recorded", "Abschlussdatum nicht erfasst", "Fecha de finalización no registrada") }
