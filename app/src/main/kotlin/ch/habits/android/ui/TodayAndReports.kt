package ch.habits.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun reportTitle(kind: String, copy: UiCopy) = when (kind) {
    "evening" -> copy.t("Evening report", "Abendbericht", "Informe de la tarde")
    "weekly" -> copy.t("Weekly review", "Wochenrückblick", "Revisión semanal")
    else -> copy.t("Morning report", "Morgenbericht", "Informe de la mañana")
}

@Composable
internal fun TodayScreen(snapshot: WorkspaceSnapshot, copy: UiCopy, onReport: (String) -> Unit, onPlanner: () -> Unit) {
    val facts = WorkspaceFacts.today(snapshot)
    LazyColumn(Modifier.testTag("today.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(facts.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", copy.locale)),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (snapshot.preferences.userName.isBlank()) copy.t("A little clarity for today", "Etwas Klarheit für heute", "Un poco de claridad para hoy")
                else copy.t("Hello, ", "Hallo, ", "Hola, ") + snapshot.preferences.userName,
                style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(copy.t("From your recorded plans", "Aus deinen erfassten Plänen", "De tus planes registrados"), style = MaterialTheme.typography.titleMedium)
                    Text("${facts.tasks.size} " + copy.t("open tasks due today or earlier", "offene Aufgaben mit Frist heute oder früher", "tareas abiertas con plazo hoy o antes"))
                    Text("${facts.appointments.size} " + copy.t("appointments today", "Termine heute", "citas hoy"))
                    Text("${facts.completedHabitIDs.intersect(facts.habits.map { it.id }.toSet()).size}/${facts.habits.size} " +
                        copy.t("scheduled habits logged today", "geplante Gewohnheiten heute protokolliert", "hábitos programados registrados hoy"))
                    OutlinedButton(onClick = onPlanner, modifier = Modifier.testTag("today.openPlanner")) { Text(copy.planner) }
                }
            }
        }
        if (facts.appointments.isNotEmpty()) {
            item { SectionHeading(copy.t("Appointments today", "Termine heute", "Citas hoy")) }
            items(facts.appointments, key = { it.id }) { appointment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appointment.title, style = MaterialTheme.typography.titleMedium)
                    Text("${appointment.startDate} ${appointment.startTime} → ${appointment.endDate} ${appointment.endTime}")
                    Text(appointment.timeZoneIdentifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeading(copy.t("Plan and reflect", "Planen und reflektieren", "Planifica y reflexiona"))
            Text(copy.t("Reports use recorded facts. Nothing logged means nothing recorded; it does not mean a habit was missed.",
                "Berichte nutzen erfasste Fakten. Ohne Protokoll ist nichts erfasst; eine Gewohnheit gilt dadurch nicht als verpasst.",
                "Los informes usan hechos registrados. Sin registro no hay un dato; eso no significa que se haya omitido un hábito."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (kind in listOf("morning", "evening", "weekly")) item {
            OutlinedButton(onClick = { onReport(kind) }, modifier = Modifier.fillMaxWidth().testTag("today.report.$kind")) {
                Text(reportTitle(kind, copy))
            }
        }
    }
}

@Composable
internal fun ReportScreen(kind: String, snapshot: WorkspaceSnapshot, copy: UiCopy) {
    val today = LocalDate.now()
    val start = if (kind == "weekly") today.minusDays(6) else today
    val recorded = WorkspaceFacts.report(snapshot, start, today.plusDays(1))
    val plan = WorkspaceFacts.today(snapshot, today)
    LazyColumn(Modifier.testTag("report.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(reportTitle(kind, copy), style = MaterialTheme.typography.headlineLarge)
            Text(copy.t("Offline · deterministic summary", "Offline · deterministische Zusammenfassung", "Sin conexión · resumen determinista"),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 10.dp).testTag("report.source"))
            Text(if (kind == "weekly") "$start → $today" else today.toString())
            Text(copy.t("Generated from this phone's recorded plans, completions and habit logs. No AI model is used for this report.",
                "Aus den auf diesem Telefon erfassten Plänen, Abschlüssen und Gewohnheitsprotokollen erstellt. Dieser Bericht verwendet kein KI-Modell.",
                "Generado a partir de los planes, las finalizaciones y los hábitos registrados en este teléfono. Este informe no usa un modelo de IA."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (kind == "morning") {
            item { SectionHeading(copy.t("Today's recorded deadlines", "Heute erfasste Fristen", "Plazos registrados para hoy")) }
            if (plan.tasks.isEmpty() && plan.assessments.isEmpty()) item {
                Text(copy.t("No open deadlines due today or earlier are recorded.", "Keine offenen Fristen für heute oder früher erfasst.", "No hay plazos abiertos para hoy o antes registrados."))
            }
            items(plan.tasks, key = { "morning-task-" + it.id }) { task ->
                Column { Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text(task.dueDate.orEmpty() + (task.dueTime?.let { " · $it" } ?: ""))
                    task.sourceZone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(plan.assessments, key = { "morning-assessment-" + it.id }) { assessment ->
                Column { Text(assessment.title, style = MaterialTheme.typography.titleMedium)
                    Text("${assessment.dueDate} · ${assessment.dueTime ?: copy.unknownTime}")
                    Text(snapshot.semesters.firstOrNull { it.id == assessment.semesterID }?.timeZoneIdentifier.orEmpty(),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item { SectionHeading(copy.schedule) }
            if (plan.appointments.isEmpty()) item { Text(copy.t("No appointments today are recorded.", "Keine Termine heute erfasst.", "No hay citas registradas para hoy.")) }
            items(plan.appointments, key = { "morning-appointment-" + it.id }) { appointment ->
                Text("${appointment.title} · ${appointment.startTime}–${appointment.endTime} · ${appointment.timeZoneIdentifier}")
            }
            item { SectionHeading(copy.habits)
                Text("${plan.habits.size} " + copy.t("habits scheduled today. Mark only what you record.",
                    "Gewohnheiten heute geplant. Markiere nur, was du erfasst.", "hábitos programados para hoy. Marca solo lo que registres.")) }
        } else {
            item { SectionHeading(copy.t("Recorded completions", "Erfasste Abschlüsse", "Finalizaciones registradas"))
                Text("${recorded.completedTasks.size} " + copy.t("tasks or assignments", "Aufgaben oder Abgaben", "tareas o entregas"),
                    modifier = Modifier.testTag("report.completedTasks"))
                Text("${recorded.completedAssessments.size} " + copy.t("assessments", "Prüfungen oder Fristen", "evaluaciones"))
                Text("${recorded.habitLogs.size} " + copy.t("habit logs", "Gewohnheitsprotokolle", "registros de hábitos"),
                    modifier = Modifier.testTag("report.habitLogs"))
            }
            items(recorded.completedTasks, key = { "recorded-task-" + it.id }) { Text(it.title) }
            items(recorded.completedAssessments, key = { "recorded-assessment-" + it.id }) { Text(it.title) }
            if (recorded.completedTasks.isEmpty() && recorded.completedAssessments.isEmpty() && recorded.habitLogs.isEmpty()) item {
                Text(copy.t("No completion or habit logs are recorded for this period.", "Keine Abschlüsse oder Gewohnheitsprotokolle für diesen Zeitraum erfasst.",
                    "No se han registrado finalizaciones ni hábitos para este período."))
            }
        }
        item {
            SectionHeading(copy.t("What this report can tell you", "Aussagekraft dieses Berichts", "Lo que puede decir este informe"))
            Text(copy.t("Missing records do not prove missed habits, attendance, free time or time studied. Task estimates describe plans. Preparation and exam completion stay separate.",
                "Fehlende Einträge beweisen weder verpasste Gewohnheiten, Anwesenheit, freie Zeit noch Lernzeit. Aufwandsschätzungen beschreiben Pläne. Vorbereitung und Prüfungsabschluss bleiben getrennt.",
                "Los registros ausentes no prueban hábitos omitidos, asistencia, tiempo libre ni tiempo estudiado. Las estimaciones describen planes. La preparación y los exámenes se completan por separado."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val untimed = snapshot.tasks.count { it.completed && it.completedAt == null } + snapshot.assessments.count { it.completed && it.completedAt == null }
            if (untimed > 0) Text("$untimed " + copy.t("completed items lack a completion date and are excluded from this period.",
                "erledigte Einträge ohne Abschlussdatum sind von diesem Zeitraum ausgeschlossen.", "elementos completados sin fecha de finalización se excluyen de este período."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
