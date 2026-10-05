@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    onCompleteAssessment: (AcademicAssessment) -> Unit) {
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var selectedAssessment by remember { mutableStateOf<AcademicAssessment?>(null) }
    val semesters = snapshot.semesters.filterNot { it.archived }
    val semester = semesters.firstOrNull { it.id == selectedSemesterID } ?: semesters.firstOrNull()
    val tasks = snapshot.tasks.filter { !it.archived && (showCompleted || !it.completed) }
        .sortedWith(compareBy<WorkTask>({ it.completed }, { it.dueDate == null }, { it.dueDate.orEmpty() }, { it.title }))
    LazyColumn(Modifier.testTag("planner.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in listOf("work", "schedule", "semester")) FilterChip(selected = mode == value,
                    onClick = { onMode(value) }, label = { Text(when (value) {
                        "schedule" -> copy.schedule; "semester" -> copy.semester; else -> copy.work
                    }) }, modifier = Modifier.testTag("planner.mode.$value"))
            }
        }
        when (mode) {
            "work" -> {
                item {
                    Text(copy.t("Your next steps", "Deine nächsten Schritte", "Tus próximos pasos"), style = MaterialTheme.typography.headlineMedium)
                    Button(onClick = onNewTask, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("planner.add.task")) {
                        Text(copy.t("Add task or assignment", "Aufgabe oder Abgabe hinzufügen", "Añadir tarea o entrega"))
                    }
                    FilterChip(selected = showCompleted, onClick = { showCompleted = !showCompleted },
                        label = { Text(copy.t("Show completed", "Erledigte anzeigen", "Mostrar completadas")) }, modifier = Modifier.testTag("planner.showCompleted"))
                }
                val allAssessments = snapshot.assessments.filter { !it.archived && !it.completed }
                    .sortedWith(compareBy({ it.dueDate }, { it.dueTime.orEmpty() }))
                val assessments = allAssessments.take(5)
                if (assessments.isNotEmpty()) {
                    item { SectionHeading(copy.t("Academic deadlines", "Akademische Fristen", "Plazos académicos")) }
                    items(assessments, key = { "academic-" + it.id }) { assessment ->
                        AssessmentRow(assessment, snapshot, copy) { selectedAssessment = assessment }
                    }
                    item { Text(copy.t("Preparation tasks appear below. Completing them leaves the assessment pending.",
                        "Vorbereitungsaufgaben stehen darunter. Ihr Abschluss lässt die Prüfung offen.",
                        "Las tareas de preparación aparecen abajo. Completarlas mantiene pendiente la evaluación."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onMode("semester") }, modifier = Modifier.testTag("planner.allAssessments")) {
                            Text(copy.t("All exams and deadlines", "Alle Prüfungen und Fristen", "Todos los exámenes y plazos") + " (${allAssessments.size})")
                        }
                    }
                }
                if (tasks.isEmpty()) item { EmptyMessage(copy.t("A clear list", "Eine übersichtliche Liste", "Una lista clara"),
                    copy.t("Add your next task or assignment. Undated work can stay undated.",
                        "Füge deine nächste Aufgabe oder Abgabe hinzu. Aufgaben ohne Frist können ohne Datum bleiben.",
                        "Añade tu siguiente tarea o entrega. Las tareas sin plazo pueden quedarse sin fecha.")) }
                items(tasks, key = { "task-" + it.id }) { task ->
                    TaskRow(task, copy, writesEnabled, { onEditTask(task) }, { onCompleteTask(task) })
                }
            }
            "schedule" -> {
                item {
                    Text(copy.t("Time you have booked", "Deine geplanten Termine", "El tiempo que has reservado"), style = MaterialTheme.typography.headlineMedium)
                    Text(copy.t("Appointments are separate from task deadlines. This view shows saved events; it does not infer free time.",
                        "Termine sind von Aufgabenfristen getrennt. Diese Ansicht zeigt gespeicherte Ereignisse und leitet keine freie Zeit ab.",
                        "Las citas están separadas de los plazos de tareas. Esta vista muestra eventos guardados; no deduce tiempo libre."),
                        modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNewAppointment, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("planner.add.appointment")) {
                        Text(copy.t("Add appointment", "Termin hinzufügen", "Añadir cita"))
                    }
                }
                val appointments = snapshot.appointments.filterNot { it.archived }.sortedWith(compareBy({ it.startDate }, { it.startTime }))
                if (appointments.isEmpty()) item { EmptyMessage(copy.t("No appointments saved", "Keine Termine gespeichert", "Sin citas guardadas"),
                    copy.t("Add a meeting, lecture or other event with a start and end time.",
                        "Füge ein Treffen, eine Vorlesung oder ein anderes Ereignis mit Anfang und Ende hinzu.",
                        "Añade una reunión, una clase u otro evento con hora de inicio y fin.")) }
                items(appointments, key = { "appointment-" + it.id }) { item ->
                    Column(Modifier.fillMaxWidth().clickable { onEditAppointment(item) }.padding(vertical = 12.dp)
                        .testTag("planner.appointment.${item.id}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text("${item.startDate} ${item.startTime} → ${item.endDate} ${item.endTime}")
                        Text(item.timeZoneIdentifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(top = 10.dp))
                    }
                }
            }
            "semester" -> {
                item {
                    Text(semester?.title ?: copy.semester, style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag("semester.title"))
                    Text(copy.localOnly, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (semesters.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        semesters.forEach { item -> FilterChip(selected = item.id == semester?.id,
                            onClick = { onSelectSemester(item.id) }, label = { Text(item.title) }, modifier = Modifier.testTag("semester.pick.${item.id}")) }
                    }
                    Button(onClick = onAssistant, enabled = writesEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("semester.button.assistant")) {
                        Text(copy.t("Plan with AI", "Mit KI planen", "Planificar con IA"))
                    }
                }
                if (semester == null) item {
                    EmptyMessage(copy.t("Bring your semester together", "Dein Semester an einem Ort", "Tu semestre en un lugar"),
                        copy.t("Share instructions with your chosen assistant, then import its proposal here. Nothing is saved until you review and confirm.",
                            "Teile die Anleitung mit deinem Assistenten und importiere seinen Vorschlag hier. Gespeichert wird erst nach deiner Prüfung und Bestätigung.",
                            "Comparte instrucciones con tu asistente y luego importa su propuesta aquí. Nada se guarda hasta que revises y confirmes."),
                        Modifier.testTag("semester.empty"))
                } else {
                    item {
                        Text("${semester.startDate} · ${semester.weekCount} " + copy.t("teaching weeks", "Unterrichtswochen", "semanas lectivas"))
                        Text(semester.timeZoneIdentifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val courses = snapshot.courses.filter { it.semesterID == semester.id && !it.archived }
                    for (course in courses) {
                        item { SectionHeading(course.name) }
                        val assessments = snapshot.assessments.filter { it.courseID == course.id && !it.archived }.sortedBy { it.dueDate }
                        if (assessments.isEmpty()) item { Text(copy.t("No exams or deadlines recorded", "Keine Prüfungen oder Fristen erfasst", "Sin exámenes ni plazos registrados"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(assessments, key = { "semester-assessment-" + it.id }) { assessment ->
                            AssessmentRow(assessment, snapshot, copy, semester) { selectedAssessment = assessment }
                        }
                    }
                }
            }
        }
    }
    selectedAssessment?.let { assessment ->
        val current = snapshot.assessments.firstOrNull { it.id == assessment.id } ?: assessment
        AlertDialog(onDismissRequest = { selectedAssessment = null },
            title = { Text(current.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${current.dueDate} · ${current.dueTime ?: copy.unknownTime}")
                Text(snapshot.semesters.firstOrNull { it.id == current.semesterID }?.timeZoneIdentifier.orEmpty())
                Text(if (current.completed) copy.t("Recorded as completed", "Als erledigt erfasst", "Registrado como completado") else copy.t("Pending", "Offen", "Pendiente"))
                if (current.notes.isNotBlank()) Text(current.notes)
                Text(copy.t("This updates the assessment only. Preparation work stays separate.",
                    "Diese Änderung betrifft nur die Prüfung. Vorbereitungsaufgaben bleiben getrennt.",
                    "Esto solo actualiza la evaluación. Las tareas de preparación se mantienen separadas."), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { onCompleteAssessment(current); selectedAssessment = null }, enabled = writesEnabled,
                modifier = Modifier.testTag("assessment.complete")) {
                Text(if (current.completed) copy.t("Reopen assessment", "Prüfung wieder öffnen", "Reabrir evaluación") else copy.t("Mark assessment complete", "Prüfung als erledigt markieren", "Marcar evaluación completada"))
            } }, dismissButton = { TextButton(onClick = { selectedAssessment = null }) { Text(copy.close) } },
            modifier = Modifier.testTag("assessment.detail"))
    }
}

@Composable
internal fun TaskRow(task: WorkTask, copy: UiCopy, writesEnabled: Boolean, onEdit: () -> Unit, onComplete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = task.completed, onCheckedChange = { onComplete() }, enabled = writesEnabled,
            modifier = Modifier.testTag("planner.complete.${task.id}").semantics {
                contentDescription = copy.t("Complete task: ", "Aufgabe erledigen: ", "Completar tarea: ") + task.title
            })
        Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 10.dp).testTag("planner.task.${task.id}"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text((if (task.kind == "assignment") copy.t("Assignment", "Abgabe", "Entrega") else copy.t("Task", "Aufgabe", "Tarea")) + " · " +
                (task.dueDate?.let { "$it${task.dueTime?.let { time -> " · $time" }.orEmpty()}" } ?: copy.noDeadline),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            task.sourceZone?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun AssessmentRow(assessment: AcademicAssessment, snapshot: WorkspaceSnapshot, copy: UiCopy,
    semester: AcademicSemester? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag("semester.assessment.${assessment.id}"),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(assessment.title, style = MaterialTheme.typography.titleMedium)
        Text("${assessment.dueDate} · ${assessment.dueTime ?: copy.unknownTime}")
        val selectedSemester = semester ?: snapshot.semesters.firstOrNull { it.id == assessment.semesterID }
        val week = selectedSemester?.let { ((java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.startDate), LocalDate.parse(assessment.dueDate))) / 7 + 1).toInt() }
        Text((if (assessment.completed) copy.t("Completed", "Erledigt", "Completado") else copy.t("Pending", "Offen", "Pendiente")) +
            (week?.let { " · " + copy.t("Week ", "Woche ", "Semana ") + it } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}
