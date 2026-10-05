@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun TaskEditor(initial: WorkTask?, copy: UiCopy, busy: Boolean,
    onSave: (WorkTask) -> Unit, onArchive: ((WorkTask) -> Unit)?) {
    val original = remember(initial) { initial ?: WorkTask(title = "") }
    var title by remember { mutableStateOf(original.title) }
    var notes by remember { mutableStateOf(original.notes) }
    var date by remember { mutableStateOf(original.dueDate.orEmpty()) }
    var time by remember { mutableStateOf(original.dueTime.orEmpty()) }
    var zone by remember { mutableStateOf(original.sourceZone ?: ZoneId.systemDefault().id) }
    var kind by remember { mutableStateOf(original.kind) }
    var minutes by remember { mutableStateOf(original.minutes.toString()) }
    var priority by remember { mutableStateOf(original.priority) }
    var reviewed by remember { mutableStateOf<WorkTask?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.testTag("editor.task"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(copy.t("Make the next step clear", "Den nächsten Schritt klären", "Define el siguiente paso"),
            style = MaterialTheme.typography.headlineMedium) }
        val draft = reviewed
        if (draft == null) {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in listOf("task", "assignment")) FilterChip(selected = kind == value, onClick = { kind = value },
                    label = { Text(if (value == "task") copy.t("Task", "Aufgabe", "Tarea") else copy.t("Assignment", "Abgabe", "Entrega")) },
                    modifier = Modifier.testTag("editor.task.kind.$value"))
            } }
            item { EntryField(title, { title = it }, copy.title, "editor.task.title") }
            item { EntryField(notes, { notes = it }, copy.notes, "editor.task.notes", multiline = true) }
            item { EntryField(date, { date = it }, copy.t("Deadline date · YYYY-MM-DD (optional)", "Fristdatum · JJJJ-MM-TT (optional)", "Fecha límite · AAAA-MM-DD (opcional)"), "editor.task.date") }
            item { EntryField(time, { time = it }, copy.t("Deadline time · HH:mm (optional)", "Fristzeit · HH:mm (optional)", "Hora límite · HH:mm (opcional)"), "editor.task.time") }
            item { EntryField(zone, { zone = it }, copy.t("Time zone", "Zeitzone", "Zona horaria"), "editor.task.zone") }
            item { EntryField(minutes, { minutes = it }, copy.t("Estimated minutes", "Geschätzte Minuten", "Minutos estimados"), "editor.task.minutes") }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in listOf("low", "medium", "high")) FilterChip(selected = priority == value,
                    onClick = { priority = value }, label = { Text(when (value) {
                        "low" -> copy.t("Low priority", "Niedrige Priorität", "Prioridad baja")
                        "high" -> copy.t("High priority", "Hohe Priorität", "Prioridad alta")
                        else -> copy.t("Medium priority", "Mittlere Priorität", "Prioridad media")
                    }) })
            } }
            item { Button(onClick = {
                error = runCatching {
                    validateTitle(title, notes)
                    val dueDate = optionalDate(date)
                    val dueTime = optionalTime(time)
                    require(dueTime == null || dueDate != null) { "A time needs a date." }
                    val sourceZone = if (dueDate != null) ZoneId.of(zone.trim()).id else null
                    val estimate = minutes.toIntOrNull()
                    require(estimate != null && estimate in 0..1440) { "Estimated minutes must be between 0 and 1440." }
                    reviewed = original.copy(title = title.trim(), notes = notes, kind = kind, dueDate = dueDate,
                        dueTime = dueTime, sourceZone = sourceZone, minutes = estimate, priority = priority)
                }.exceptionOrNull()?.message
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("editor.task.review")) { Text(copy.review) } }
        } else {
            item {
                SectionHeading(copy.review)
                Text(draft.title, style = MaterialTheme.typography.titleLarge)
                Text(draft.dueDate?.let { "$it · ${draft.dueTime ?: copy.unknownTime}" } ?: copy.noDeadline)
                draft.sourceZone?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${draft.minutes} " + copy.t("estimated minutes", "geschätzte Minuten", "minutos estimados"))
                if (draft.notes.isNotBlank()) Text(draft.notes)
                Text(copy.t("A deadline does not reserve Calendar time. Preparation and assessment completion stay separate.",
                    "Eine Frist reserviert keine Kalenderzeit. Vorbereitung und Prüfungsabschluss bleiben getrennt.",
                    "Un plazo no reserva tiempo en el calendario. La preparación y la evaluación se completan por separado."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Button(onClick = { onSave(draft) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("editor.task.save")) { Text(copy.save) } }
            item { TextButton(onClick = { reviewed = null }) { Text(copy.t("Edit details", "Angaben bearbeiten", "Editar detalles")) } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor.error")) } }
        if (initial != null && onArchive != null) item {
            TextButton(onClick = { onArchive(original) }, enabled = !busy, modifier = Modifier.testTag("editor.task.archive")) { Text(copy.archive) }
        }
    }
}

@Composable
internal fun AppointmentEditor(initial: Appointment?, copy: UiCopy, busy: Boolean,
    onSave: (Appointment) -> Unit, onArchive: ((Appointment) -> Unit)?) {
    val today = LocalDate.now().toString()
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var startDate by remember { mutableStateOf(initial?.startDate ?: today) }
    var endDate by remember { mutableStateOf(initial?.endDate ?: today) }
    var startTime by remember { mutableStateOf(initial?.startTime ?: "09:00") }
    var endTime by remember { mutableStateOf(initial?.endTime ?: "10:00") }
    var zone by remember { mutableStateOf(initial?.timeZoneIdentifier ?: ZoneId.systemDefault().id) }
    var reviewed by remember { mutableStateOf<Appointment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.testTag("editor.appointment"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(copy.t("An appointment with a time", "Ein Termin mit Zeit", "Una cita con horario"), style = MaterialTheme.typography.headlineMedium) }
        val draft = reviewed
        if (draft == null) {
            item { EntryField(title, { title = it }, copy.title, "editor.appointment.title") }
            item { EntryField(startDate, { startDate = it }, copy.t("Start date · YYYY-MM-DD", "Startdatum · JJJJ-MM-TT", "Fecha de inicio · AAAA-MM-DD"), "editor.appointment.startDate") }
            item { EntryField(startTime, { startTime = it }, copy.t("Start time · HH:mm", "Startzeit · HH:mm", "Hora de inicio · HH:mm"), "editor.appointment.startTime") }
            item { EntryField(endDate, { endDate = it }, copy.t("End date · YYYY-MM-DD", "Enddatum · JJJJ-MM-TT", "Fecha de fin · AAAA-MM-DD"), "editor.appointment.endDate") }
            item { EntryField(endTime, { endTime = it }, copy.t("End time · HH:mm", "Endzeit · HH:mm", "Hora de fin · HH:mm"), "editor.appointment.endTime") }
            item { EntryField(zone, { zone = it }, copy.t("Time zone", "Zeitzone", "Zona horaria"), "editor.appointment.zone") }
            item { EntryField(notes, { notes = it }, copy.notes, "editor.appointment.notes", multiline = true) }
            item { Button(onClick = {
                error = runCatching {
                    validateTitle(title, notes)
                    require(optionalDate(startDate) != null && optionalDate(endDate) != null) { "Enter both dates." }
                    require(optionalTime(startTime) != null && optionalTime(endTime) != null) { "Enter both times." }
                    val zoneID = ZoneId.of(zone.trim())
                    val start = java.time.LocalDateTime.of(LocalDate.parse(startDate), LocalTime.parse(startTime))
                    val end = java.time.LocalDateTime.of(LocalDate.parse(endDate), LocalTime.parse(endTime))
                    require(zoneID.rules.getValidOffsets(start).size == 1 && zoneID.rules.getValidOffsets(end).size == 1) { "The clock time is skipped or repeated in this time zone." }
                    require(end.atZone(zoneID).toInstant() > start.atZone(zoneID).toInstant()) { "End must follow start." }
                    reviewed = initial?.copy(title = title.trim(), notes = notes, startDate = startDate, startTime = startTime,
                        endDate = endDate, endTime = endTime, timeZoneIdentifier = zoneID.id) ?:
                        Appointment(title = title.trim(), notes = notes, startDate = startDate, startTime = startTime,
                            endDate = endDate, endTime = endTime, timeZoneIdentifier = zoneID.id)
                }.exceptionOrNull()?.message
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("editor.appointment.review")) { Text(copy.review) } }
        } else {
            item { SectionHeading(copy.review)
                Text(draft.title, style = MaterialTheme.typography.titleLarge)
                Text("${draft.startDate} ${draft.startTime} → ${draft.endDate} ${draft.endTime}")
                Text(draft.timeZoneIdentifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.notes.isNotBlank()) Text(draft.notes)
            }
            item { Button(onClick = { onSave(draft) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("editor.appointment.save")) { Text(copy.save) } }
            item { TextButton(onClick = { reviewed = null }) { Text(copy.t("Edit details", "Angaben bearbeiten", "Editar detalles")) } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor.error")) } }
        if (initial != null && onArchive != null) item { TextButton(onClick = { onArchive(initial) }, enabled = !busy) { Text(copy.archive) } }
    }
}

@Composable
internal fun HabitEditor(initial: Habit?, copy: UiCopy, busy: Boolean,
    onSave: (Habit) -> Unit, onArchive: ((Habit) -> Unit)?) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var weekdays by remember { mutableStateOf(initial?.weekdays ?: (1..7).toList()) }
    var reviewed by remember { mutableStateOf<Habit?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.testTag("editor.habit"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(copy.t("A routine for your week", "Eine Routine für deine Woche", "Una rutina para tu semana"), style = MaterialTheme.typography.headlineMedium) }
        val draft = reviewed
        if (draft == null) {
            item { EntryField(title, { title = it }, copy.title, "editor.habit.title") }
            item { EntryField(notes, { notes = it }, copy.notes, "editor.habit.notes", multiline = true) }
            item { Text(copy.t("Scheduled days", "Geplante Tage", "Días programados"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (day in 1..7) FilterChip(selected = day in weekdays, onClick = {
                        weekdays = if (day in weekdays) weekdays - day else (weekdays + day).sorted()
                    }, label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, copy.locale)) },
                        modifier = Modifier.testTag("editor.habit.day.$day"))
                }
            }
            item { Button(onClick = {
                error = runCatching {
                    validateTitle(title, notes)
                    require(weekdays.isNotEmpty()) { "Choose at least one scheduled day." }
                    reviewed = initial?.copy(title = title.trim(), notes = notes, weekdays = weekdays) ?:
                        Habit(title = title.trim(), notes = notes, weekdays = weekdays)
                }.exceptionOrNull()?.message
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("editor.habit.review")) { Text(copy.review) } }
        } else {
            item { SectionHeading(copy.review)
                Text(draft.title, style = MaterialTheme.typography.titleLarge)
                Text(draft.weekdays.joinToString(" · ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, copy.locale) })
                if (draft.notes.isNotBlank()) Text(draft.notes)
            }
            item { Button(onClick = { onSave(draft) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("editor.habit.save")) { Text(copy.save) } }
            item { TextButton(onClick = { reviewed = null }) { Text(copy.t("Edit details", "Angaben bearbeiten", "Editar detalles")) } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor.error")) } }
        if (initial != null && onArchive != null) item { TextButton(onClick = { onArchive(initial) }, enabled = !busy) { Text(copy.archive) } }
    }
}

@Composable
private fun EntryField(value: String, onChange: (String) -> Unit, label: String, tag: String, multiline: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 6 else 1,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun validateTitle(title: String, notes: String) {
    require(title.trim().isNotEmpty() && title.length <= 200) { "Enter a title of 1 to 200 characters." }
    require(notes.length <= 4000) { "Notes must be at most 4000 characters." }
}
private fun optionalDate(value: String): String? {
    if (value.isBlank()) return null
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Use YYYY-MM-DD for dates." }
    LocalDate.parse(value)
    return value
}
private fun optionalTime(value: String): String? {
    if (value.isBlank()) return null
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) { "Use HH:mm for times." }
    LocalTime.parse(value)
    return value
}
