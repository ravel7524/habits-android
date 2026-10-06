@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

@Composable
internal fun TaskEditor(initial: WorkTask?, copy: UiCopy, busy: Boolean,
    onSave: (WorkTask) -> Unit, onArchive: ((WorkTask) -> Unit)?) {
    val original = remember(initial) { initial ?: WorkTask(title = "") }
    var title by remember(original.id) { mutableStateOf(original.title) }
    var notes by remember(original.id) { mutableStateOf(original.notes) }
    var date by remember(original.id) { mutableStateOf(original.dueDate.orEmpty()) }
    var time by remember(original.id) { mutableStateOf(original.dueTime.orEmpty()) }
    var zone by remember(original.id) { mutableStateOf(original.sourceZone ?: ZoneId.systemDefault().id) }
    var kind by remember(original.id) { mutableStateOf(original.kind) }
    var minutes by remember(original.id) { mutableStateOf(initial?.minutes?.takeIf { it > 0 }?.toString().orEmpty()) }
    var priority by remember(original.id) { mutableStateOf(original.priority) }
    var reviewed by remember(original.id) { mutableStateOf<WorkTask?>(null) }
    var error by remember(original.id) { mutableStateOf<String?>(null) }
    val draft = reviewed
    // Freeze interval shape before LazyColumn evaluates its deferred item builder.
    val hasDueDate = date.isNotBlank()
    EditorFrame("editor.task", copy, busy, draft != null, title.isNotBlank(), error, onAction = {
        if (draft != null) onSave(draft) else error = runCatching {
            validateTitle(title, notes, copy)
            val dueDate = optionalDate(date, copy)
            val dueTime = optionalTime(time, copy)
            require(dueTime == null || dueDate != null) { copy.t("Choose a date before adding a time.", "Wähle vor der Uhrzeit ein Datum.", "Elige una fecha antes de añadir una hora.") }
            val sourceZone = if (dueDate != null) validZone(zone, copy) else null
            if (dueDate != null && dueTime != null) validateClock(dueDate, dueTime, checkNotNull(sourceZone), copy)
            val estimate = if (minutes.isBlank()) 0 else minutes.toIntOrNull()
            require(estimate != null && estimate in 0..1440) { copy.t("Enter an estimate from 0 to 1440 minutes.", "Gib eine Schätzung von 0 bis 1440 Minuten ein.", "Introduce una estimación de 0 a 1440 minutos.") }
            reviewed = original.copy(title = title.trim(), notes = notes, kind = kind, dueDate = dueDate,
                dueTime = dueTime, sourceZone = sourceZone, minutes = estimate, priority = priority)
        }.exceptionOrNull()?.message
    }, onEdit = { reviewed = null; error = null }) {
        if (draft == null) {
            item { EntryField(title, { title = it; error = null }, copy.t("Task title", "Aufgabentitel", "Título de la tarea"),
                "editor.task.title", placeholder = copy.t("What needs doing?", "Was ist zu erledigen?", "¿Qué necesitas hacer?"), enabled = !busy) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(kind == "task", { kind = "task" }, copy.t("Task", "Aufgabe", "Tarea"), "editor.task.kind.task", !busy)
                ChoiceChip(kind == "assignment", { kind = "assignment" }, copy.t("Assignment", "Abgabe", "Entrega"), "editor.task.kind.assignment", !busy)
            } }
            item { FieldGroup(copy.t("Deadline · optional", "Frist · optional", "Plazo · opcional")) {
                DateField(date.ifBlank { null }, { date = it.orEmpty(); if (it == null) time = ""; error = null }, copy,
                    copy.t("Date", "Datum", "Fecha"), "editor.task.date", optional = true, enabled = !busy)
                if (date.isNotBlank()) TimeField(time.ifBlank { null }, { time = it.orEmpty(); error = null }, copy,
                    copy.t("Time · optional", "Uhrzeit · optional", "Hora · opcional"), "editor.task.time", optional = true, enabled = !busy)
                if (date.isBlank()) Text(copy.t("Undated work stays in your list.", "Aufgaben ohne Datum bleiben in deiner Liste.", "Las tareas sin fecha permanecen en tu lista."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { NotesSection(notes, { notes = it; error = null }, copy, "editor.task.notes", !busy) }
            item { ExpandableSection(copy.t("Work details", "Aufwand und Priorität", "Detalles de la tarea"), "editor.task.details", !busy) {
                EntryField(minutes, { minutes = it; error = null }, copy.t("Estimate in minutes · optional", "Geschätzte Minuten · optional", "Minutos estimados · opcional"),
                    "editor.task.minutes", keyboard = KeyboardType.Number, enabled = !busy)
                Text(copy.t("Priority", "Priorität", "Prioridad"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (value in listOf("low", "medium", "high")) ChoiceChip(priority == value, { priority = value }, when (value) {
                        "low" -> copy.t("Low", "Niedrig", "Baja"); "high" -> copy.t("High", "Hoch", "Alta"); else -> copy.t("Normal", "Normal", "Normal")
                    }, "editor.task.priority.$value", !busy)
                }
            } }
            if (hasDueDate) item(key = "editor.task.zone.section") { ZoneSection(zone, { zone = it; error = null }, copy, "editor.task.zone", !busy) }
        } else {
            item { ReviewHeading(draft.title, copy) }
            item { FieldGroup(copy.t("Task details", "Aufgabendetails", "Detalles de la tarea")) {
                Text(if (draft.kind == "assignment") copy.t("Assignment", "Abgabe", "Entrega") else copy.t("Task", "Aufgabe", "Tarea"))
                Text(draft.dueDate?.let { copy.displayDate(it) } ?: copy.noDeadline, style = MaterialTheme.typography.titleMedium)
                if (draft.dueDate != null) Text(draft.dueTime?.let { copy.displayTime(it) } ?: copy.t("Date only", "Nur Datum", "Solo fecha"))
                copy.zoneDetail(draft.sourceZone)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (draft.minutes > 0) Text("${draft.minutes} " + copy.t("planned minutes", "geplante Minuten", "minutos previstos"))
                Text(copy.t("Priority: ", "Priorität: ", "Prioridad: ") + when (draft.priority) {
                    "low" -> copy.t("Low", "Niedrig", "Baja"); "high" -> copy.t("High", "Hoch", "Alta"); else -> copy.t("Normal", "Normal", "Normal") })
                if (draft.notes.isNotBlank()) Text(draft.notes)
            } }
            item { Text(copy.t("A deadline does not reserve Calendar time. Preparation and assessment completion stay separate.",
                "Eine Frist reserviert keine Kalenderzeit. Vorbereitung und Prüfungsabschluss bleiben getrennt.",
                "Un plazo no reserva tiempo en el calendario. La preparación y la evaluación se completan por separado."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (initial != null && onArchive != null) item { ArchiveAction(copy.t("Archive task", "Aufgabe archivieren", "Archivar tarea"),
            "editor.task.archive", !busy) { onArchive(original) } }
    }
}

@Composable
internal fun AppointmentEditor(initial: Appointment?, copy: UiCopy, busy: Boolean,
    onSave: (Appointment) -> Unit, onArchive: ((Appointment) -> Unit)?) {
    val today = LocalDate.now().toString()
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    var startDate by remember(initial?.id) { mutableStateOf(initial?.startDate ?: today) }
    var endDate by remember(initial?.id) { mutableStateOf(initial?.endDate ?: today) }
    var startTime by remember(initial?.id) { mutableStateOf(initial?.startTime ?: "09:00") }
    var endTime by remember(initial?.id) { mutableStateOf(initial?.endTime ?: "10:00") }
    var zone by remember(initial?.id) { mutableStateOf(initial?.timeZoneIdentifier ?: ZoneId.systemDefault().id) }
    var reviewed by remember(initial?.id) { mutableStateOf<Appointment?>(null) }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }
    val draft = reviewed
    EditorFrame("editor.appointment", copy, busy, draft != null, title.isNotBlank(), error, onAction = {
        if (draft != null) onSave(draft) else error = runCatching {
            validateTitle(title, notes, copy)
            require(optionalDate(startDate, copy) != null && optionalDate(endDate, copy) != null) { copy.t("Choose a start and end date.", "Wähle ein Start- und Enddatum.", "Elige una fecha de inicio y fin.") }
            require(optionalTime(startTime, copy) != null && optionalTime(endTime, copy) != null) { copy.t("Choose a start and end time.", "Wähle eine Start- und Endzeit.", "Elige una hora de inicio y fin.") }
            val zoneID = validZone(zone, copy)
            val start = validateClock(startDate, startTime, zoneID, copy)
            val end = validateClock(endDate, endTime, zoneID, copy)
            require(end.atZone(ZoneId.of(zoneID)).toInstant() > start.atZone(ZoneId.of(zoneID)).toInstant()) {
                copy.t("Choose an end after the start.", "Wähle ein Ende nach dem Beginn.", "Elige un final posterior al inicio.") }
            reviewed = initial?.copy(title = title.trim(), notes = notes, startDate = startDate, startTime = startTime,
                endDate = endDate, endTime = endTime, timeZoneIdentifier = zoneID) ?: Appointment(title = title.trim(), notes = notes,
                startDate = startDate, startTime = startTime, endDate = endDate, endTime = endTime, timeZoneIdentifier = zoneID)
        }.exceptionOrNull()?.message
    }, onEdit = { reviewed = null; error = null }) {
        if (draft == null) {
            item { EntryField(title, { title = it; error = null }, copy.t("Appointment title", "Termintitel", "Título de la cita"),
                "editor.appointment.title", placeholder = copy.t("What is planned?", "Was ist geplant?", "¿Qué está previsto?"), enabled = !busy) }
            item { FieldGroup(copy.t("Starts", "Beginn", "Inicio")) {
                DateField(startDate, { chosen -> if (chosen != null) { val sameDay = endDate == startDate; startDate = chosen; if (sameDay) endDate = chosen; error = null } },
                    copy, copy.t("Date", "Datum", "Fecha"), "editor.appointment.startDate", enabled = !busy)
                TimeField(startTime, { it?.let { value -> startTime = value }; error = null }, copy,
                    copy.t("Time", "Uhrzeit", "Hora"), "editor.appointment.startTime", enabled = !busy)
            } }
            item { FieldGroup(copy.t("Ends", "Ende", "Fin")) {
                DateField(endDate, { it?.let { value -> endDate = value }; error = null }, copy, copy.t("Date", "Datum", "Fecha"), "editor.appointment.endDate", enabled = !busy)
                TimeField(endTime, { it?.let { value -> endTime = value }; error = null }, copy, copy.t("Time", "Uhrzeit", "Hora"), "editor.appointment.endTime", enabled = !busy)
            } }
            item { NotesSection(notes, { notes = it; error = null }, copy, "editor.appointment.notes", !busy) }
            item { ZoneSection(zone, { zone = it; error = null }, copy, "editor.appointment.zone", !busy) }
        } else {
            item { ReviewHeading(draft.title, copy) }
            item { FieldGroup(copy.t("Appointment", "Termin", "Cita")) {
                Text(copy.t("Starts", "Beginn", "Inicio"), style = MaterialTheme.typography.labelLarge)
                Text("${copy.displayDate(draft.startDate)} · ${copy.displayTime(draft.startTime)}", style = MaterialTheme.typography.titleMedium)
                Text(copy.t("Ends", "Ende", "Fin"), style = MaterialTheme.typography.labelLarge)
                Text("${copy.displayDate(draft.endDate)} · ${copy.displayTime(draft.endTime)}", style = MaterialTheme.typography.titleMedium)
                copy.zoneDetail(draft.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (draft.notes.isNotBlank()) Text(draft.notes)
            } }
        }
        if (initial != null && onArchive != null) item { ArchiveAction(copy.t("Archive appointment", "Termin archivieren", "Archivar cita"),
            "editor.appointment.archive", !busy) { onArchive(initial) } }
    }
}

@Composable
internal fun HabitEditor(initial: Habit?, copy: UiCopy, busy: Boolean,
    onSave: (Habit) -> Unit, onArchive: ((Habit) -> Unit)?) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    var weekdays by remember(initial?.id) { mutableStateOf(initial?.weekdays ?: (1..7).toList()) }
    var reviewed by remember(initial?.id) { mutableStateOf<Habit?>(null) }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }
    val draft = reviewed
    EditorFrame("editor.habit", copy, busy, draft != null, title.isNotBlank(), error, onAction = {
        if (draft != null) onSave(draft) else error = runCatching {
            validateTitle(title, notes, copy)
            require(weekdays.isNotEmpty()) { copy.t("Choose at least one day.", "Wähle mindestens einen Tag.", "Elige al menos un día.") }
            reviewed = initial?.copy(title = title.trim(), notes = notes, weekdays = weekdays) ?: Habit(title = title.trim(), notes = notes, weekdays = weekdays)
        }.exceptionOrNull()?.message
    }, onEdit = { reviewed = null; error = null }) {
        if (draft == null) {
            item { EntryField(title, { title = it; error = null }, copy.t("Habit title", "Gewohnheit", "Título del hábito"),
                "editor.habit.title", placeholder = copy.t("What would you like to repeat?", "Was möchtest du wiederholen?", "¿Qué te gustaría repetir?"), enabled = !busy) }
            item { FieldGroup(copy.t("Scheduled days", "Geplante Tage", "Días programados")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(weekdays.toSet() == (1..7).toSet(), { weekdays = (1..7).toList(); error = null }, copy.t("Every day", "Jeden Tag", "Cada día"), "editor.habit.schedule.daily", !busy)
                    ChoiceChip(weekdays.toSet() == (1..5).toSet(), { weekdays = (1..5).toList(); error = null }, copy.t("Weekdays", "Werktags", "Días laborables"), "editor.habit.schedule.weekdays", !busy)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in 1..7) ChoiceChip(day in weekdays, { weekdays = if (day in weekdays) weekdays - day else (weekdays + day).sorted(); error = null },
                        DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, copy.locale), "editor.habit.day.$day", !busy, DayOfWeek.of(day).getDisplayName(TextStyle.FULL, copy.locale))
                }
            } }
            item { NotesSection(notes, { notes = it; error = null }, copy, "editor.habit.notes", !busy) }
        } else {
            item { ReviewHeading(draft.title, copy) }
            item { FieldGroup(copy.t("Routine", "Routine", "Rutina")) {
                Text(draft.weekdays.joinToString(" · ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, copy.locale) }, style = MaterialTheme.typography.titleMedium)
                if (draft.notes.isNotBlank()) Text(draft.notes)
            } }
        }
        if (initial != null && onArchive != null) item { ArchiveAction(copy.t("Archive habit · keep logs", "Gewohnheit archivieren · Protokolle behalten", "Archivar hábito · conservar registros"),
            "editor.habit.archive", !busy) { onArchive(initial) } }
    }
}

/** The action stays outside the scroll area, including while the keyboard is shown. */
@Composable
private fun EditorFrame(tag: String, copy: UiCopy, busy: Boolean, reviewing: Boolean, hasTitle: Boolean, error: String?,
    onAction: () -> Unit, onEdit: () -> Unit, content: LazyListScope.() -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(reviewing) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding().testTag("$tag.screen")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), state = list, contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("editor.error").semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (reviewing) TextButton(onClick = onEdit, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("$tag.edit")) { Text(copy.t("Edit details", "Angaben bearbeiten", "Editar detalles")) }
                    Button(onClick = { focus.clearFocus(); keyboard?.hide(); onAction() }, enabled = !busy && hasTitle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(if (reviewing) "$tag.save" else "$tag.review")) { Text(if (reviewing) copy.save else copy.review) }
                }
            }
        }
    }
}

@Composable
private fun FieldGroup(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall); content()
    } }
}

@Composable
private fun ExpandableSection(label: String, tag: String, enabled: Boolean, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.testTag("$tag.toggle")) { Text((if (open) "− " else "+ ") + label) }
        if (open) content()
    }
}

@Composable
private fun NotesSection(notes: String, onChange: (String) -> Unit, copy: UiCopy, tag: String, enabled: Boolean) =
    ExpandableSection(copy.t("Note · optional", "Notiz · optional", "Nota · opcional"), tag, enabled, notes.isNotBlank()) {
        EntryField(notes, onChange, copy.notes, tag, multiline = true, enabled = enabled)
    }

@Composable
private fun ZoneSection(zone: String, onChange: (String) -> Unit, copy: UiCopy, tag: String, enabled: Boolean) =
    ExpandableSection(copy.t("Time zone · advanced", "Zeitzone · erweitert", "Zona horaria · avanzado"), tag, enabled) {
        copy.zoneDetail(zone)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        EntryField(zone, onChange, copy.t("Time zone", "Zeitzone", "Zona horaria"), tag, placeholder = "Europe/Zurich", enabled = enabled)
        Text(copy.t("Dates and times use this zone without changing the date you chose.", "Datum und Uhrzeit verwenden diese Zone, ohne dein gewähltes Datum zu ändern.",
            "Las fechas y horas usan esta zona sin cambiar la fecha que elegiste."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable
private fun ReviewHeading(title: String, copy: UiCopy) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(copy.t("Ready to save?", "Bereit zum Speichern?", "¿Listo para guardar?"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.headlineMedium)
} }

@Composable
private fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, tag: String, enabled: Boolean, description: String? = null) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) }, colors = FilterChipDefaults.filterChipColors(
        labelColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary, selectedContainerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { description?.let { contentDescription = it } })
}

@Composable
private fun ArchiveAction(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag(tag)) { Text(label) }
}

@Composable
private fun EntryField(value: String, onChange: (String) -> Unit, label: String, tag: String, multiline: Boolean = false,
    placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(placeholder) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 5 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun validateTitle(title: String, notes: String, copy: UiCopy) {
    require(title.trim().isNotEmpty() && title.length <= 200 && title.none { it.isISOControl() }) {
        copy.t("Add a title on one line, up to 200 characters.", "Gib einen einzeiligen Titel mit höchstens 200 Zeichen ein.", "Añade un título de una línea y hasta 200 caracteres.") }
    require(notes.length <= 4000 && notes.none { it.isISOControl() && it !in "\n\r\t" }) {
        copy.t("Keep notes within 4000 characters.", "Beschränke Notizen auf 4000 Zeichen.", "Limita las notas a 4000 caracteres.") }
}
private fun optionalDate(value: String, copy: UiCopy): String? {
    if (value.isBlank()) return null
    val valid = runCatching { LocalDate.parse(value) }.getOrNull()
    require(valid != null && valid.year in 1..9999 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) {
        copy.t("Choose a valid date.", "Wähle ein gültiges Datum.", "Elige una fecha válida.") }
    return value
}
private fun optionalTime(value: String, copy: UiCopy): String? {
    if (value.isBlank()) return null
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) { copy.t("Choose a valid time.", "Wähle eine gültige Uhrzeit.", "Elige una hora válida.") }
    return value
}
private fun validZone(value: String, copy: UiCopy): String {
    val zone = runCatching { ZoneId.of(value.trim()) }.getOrNull()
    require(zone != null) { copy.t("Choose a valid time zone, such as Europe/Zurich.", "Wähle eine gültige Zeitzone, etwa Europe/Zurich.", "Elige una zona horaria válida, como Europe/Zurich.") }
    return zone.id
}
private fun validateClock(date: String, time: String, zone: String, copy: UiCopy): LocalDateTime {
    val clock = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
    require(ZoneId.of(zone).rules.getValidOffsets(clock).size == 1) { copy.t("This clock time is skipped or repeated in the selected zone. Choose another time.",
        "Diese Uhrzeit wird in der gewählten Zone übersprungen oder wiederholt. Wähle eine andere Zeit.", "Esta hora se omite o se repite en la zona elegida. Elige otra hora.") }
    return clock
}
