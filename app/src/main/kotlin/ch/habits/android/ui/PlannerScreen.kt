@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.habits.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
    calendarSection: @Composable () -> Unit = {}) {
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var selectedAssessment by remember { mutableStateOf<AcademicAssessment?>(null) }
    val semesters = snapshot.semesters.filterNot { it.archived }
    val semester = semesters.firstOrNull { it.id == selectedSemesterID } ?: semesters.firstOrNull()
    val renderedShowCompleted = showCompleted
    val tasks = snapshot.tasks.filter { !it.archived && (renderedShowCompleted || !it.completed) }
        .sortedWith(compareBy<WorkTask>({ it.completed }, { it.dueDate == null }, { it.dueDate.orEmpty() }, { it.title }))
    val allAssessments = snapshot.assessments.filter { !it.archived && !it.completed }
        .sortedWith(compareBy({ it.dueDate }, { it.dueTime.orEmpty() }))
    val appointments = snapshot.appointments.filterNot { it.archived }.sortedWith(compareBy({ it.startDate }, { it.startTime }))
    val courses = snapshot.courses.filter { it.semesterID == semester?.id && !it.archived }
    val semesterAssessments = snapshot.assessments.filter { it.semesterID == semester?.id && !it.archived }
    val modes = listOf("work", "schedule", "semester")
    val renderedMode = mode.takeIf { it in modes } ?: "work"
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    LazyColumn(Modifier.testTag("planner.screen"), contentPadding = ScreenPadding,
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
                    TaskRow(task, copy, writesEnabled, { onEditTask(task) }, { onCompleteTask(task) },
                        snapshot.assessments.firstOrNull { it.id == task.sourceAssessmentID }?.title)
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
            "semester" -> {
                item(key = "semester.header") {
                    Text(semester?.title ?: copy.semester, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("semester.title"))
                    Text(if (semester == null) copy.t("Exams, deadlines and preparation", "Prüfungen, Fristen und Vorbereitung", "Exámenes, plazos y preparación") else
                        copy.quantity(courses.size, "course", "courses", "Kurs", "Kurse", "curso", "cursos") + " · ${semesterAssessments.count { !it.completed }} " + copy.t("pending", "offen", "pendientes"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (semesters.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        semesters.forEach { item -> FilterChip(selected = item.id == semester?.id,
                            onClick = { onSelectSemester(item.id) }, label = { Text(item.title) }, modifier = Modifier.testTag("semester.pick.${item.id}")) }
                    }
                    Button(onClick = onAssistant, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("semester.button.assistant")) {
                        Text(copy.t("Plan with an assistant", "Mit Assistenten planen", "Planificar con un asistente"))
                    }
                    Text(copy.t("Review an assistant’s proposal before anything is added.", "Prüfe den Vorschlag deines Assistenten, bevor Einträge hinzukommen.", "Revisa la propuesta del asistente antes de añadir elementos."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                if (semester == null) item(key = "semester.empty") {
                    PlannerEmpty(copy.t("Bring your semester together", "Dein Semester an einem Ort", "Tu semestre en un lugar"),
                        copy.t("Share instructions with your chosen assistant, then import its proposal here. Nothing is saved until you review and confirm.",
                            "Teile die Anleitung mit deinem Assistenten und importiere seinen Vorschlag hier. Gespeichert wird erst nach deiner Prüfung und Bestätigung.",
                            "Comparte instrucciones con tu asistente y luego importa su propuesta aquí. Nada se guarda hasta que revises y confirmes."),
                        Modifier.testTag("semester.empty"))
                } else {
                    item(key = "semester.metadata.${semester.id}") {
                        Text(copy.displayDate(semester.startDate) + " · ${semester.weekCount} " + copy.t("teaching weeks", "Unterrichtswochen", "semanas lectivas"))
                        copy.zoneDetail(semester.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (courses.isEmpty()) item(key = "semester.courses.empty") { PlannerEmpty(copy.t("No courses recorded", "Keine Kurse erfasst", "Sin cursos registrados"),
                        copy.t("An assistant proposal can add courses and exam dates after your review.", "Ein Assistentenvorschlag kann nach deiner Prüfung Kurse und Prüfungsdaten ergänzen.", "Una propuesta del asistente puede añadir cursos y fechas tras tu revisión.")) }
                    for (course in courses) {
                        val assessments = semesterAssessments.filter { it.courseID == course.id }.sortedWith(compareBy({ it.completed }, { it.dueDate }, { it.dueTime.orEmpty() }))
                        item(key = "semester.course.${course.id}") { PlannerSection(course.name,
                            copy.quantity(assessments.count { !it.completed }, "pending assessment", "pending assessments", "offene Prüfung oder Frist", "offene Prüfungen und Fristen", "evaluación pendiente", "evaluaciones pendientes")) }
                        if (assessments.isEmpty()) item(key = "semester.course.empty.${course.id}") { Text(copy.t("No exams or deadlines recorded", "Keine Prüfungen oder Fristen erfasst", "Sin exámenes ni plazos registrados"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(assessments, key = { "semester-assessment-" + it.id }) { assessment ->
                            AssessmentRow(assessment, snapshot, copy, semester, showCourse = false) { selectedAssessment = assessment }
                        }
                    }
                }
            }
        }
    }
    selectedAssessment?.let { assessment ->
        val current = snapshot.assessments.firstOrNull { it.id == assessment.id } ?: assessment
        AlertDialog(onDismissRequest = { selectedAssessment = null },
            title = { Text(current.title, maxLines = 3, overflow = TextOverflow.Ellipsis) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(assessmentKindLabel(current.kind, copy), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(snapshot.courses.firstOrNull { it.id == current.courseID }?.name.orEmpty())
                Text(copy.displayDate(current.dueDate) + " · " + (current.dueTime?.let(copy::displayTime) ?: copy.unknownTime))
                copy.zoneDetail(snapshot.semesters.firstOrNull { it.id == current.semesterID }?.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(if (current.completed) copy.t("Recorded as completed", "Als erledigt erfasst", "Registrado como completado") else copy.t("Pending", "Offen", "Pendiente"))
                if (current.notes.isNotBlank()) Text(current.notes)
                Text(copy.t("This updates the assessment only. Preparation work stays separate.",
                    "Diese Änderung betrifft nur die Prüfung. Vorbereitungsaufgaben bleiben getrennt.",
                    "Esto solo actualiza la evaluación. Las tareas de preparación se mantienen separadas."), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { onCompleteAssessment(current); selectedAssessment = null }, enabled = writesEnabled,
                modifier = Modifier.testTag("assessment.complete"), colors = actionColors) {
                Text(if (current.completed) copy.t("Reopen assessment", "Prüfung wieder öffnen", "Reabrir evaluación") else copy.t("Mark assessment complete", "Prüfung als erledigt markieren", "Marcar evaluación completada"))
            } }, dismissButton = { TextButton(onClick = { selectedAssessment = null }, colors = actionColors) { Text(copy.close) } },
            modifier = Modifier.testTag("assessment.detail"))
    }
}

@Composable
internal fun TaskRow(task: WorkTask, copy: UiCopy, writesEnabled: Boolean, onEdit: () -> Unit, onComplete: () -> Unit, assessmentTitle: String? = null) {
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
            val kind = if (task.sourceAssessmentID != null) copy.t("Preparation", "Vorbereitung", "Preparación") else
                if (task.kind == "assignment") copy.t("Assignment", "Abgabe", "Entrega") else copy.t("Task", "Aufgabe", "Tarea")
            Text(kind + (if (task.minutes > 0) " · ${task.minutes} " + copy.t("min estimated", "Min. geschätzt", "min estimados") else "") +
                (if (task.priority == "high") " · " + copy.t("High priority", "Hohe Priorität", "Prioridad alta") else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            assessmentTitle?.let { Text(copy.t("For: ", "Für: ", "Para: ") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            copy.zoneDetail(task.sourceZone)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        Text(assessmentKindLabel(assessment.kind, copy) + " · " + (if (assessment.completed) copy.t("Completed", "Erledigt", "Completado") else copy.t("Pending", "Offen", "Pendiente")),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(assessment.title, style = MaterialTheme.typography.titleMedium)
        if (showCourse) snapshot.courses.firstOrNull { it.id == assessment.courseID }?.let {
            Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(copy.displayDate(assessment.dueDate, relative = true, zone = selectedSemester?.timeZoneIdentifier) +
            " · " + (assessment.dueTime?.let(copy::displayTime) ?: copy.unknownTime))
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
