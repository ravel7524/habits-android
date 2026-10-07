@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.Preferences
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.data.WorkspaceState
import ch.habits.android.integrations.IntegrationPanel
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@Composable
internal fun HabitsScreen(snapshot: WorkspaceSnapshot, copy: UiCopy, writesEnabled: Boolean,
    onNew: () -> Unit, onEdit: (Habit) -> Unit, onLog: (Habit, Boolean) -> Unit) {
    var allDays by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val facts = WorkspaceFacts.today(snapshot, today)
    val active = snapshot.habits.filterNot { it.archived }
    val visible = if (allDays) active else facts.habits
    val pending = visible.filter { today.dayOfWeek.value in it.weekdays && it.id !in facts.completedHabitIDs }
    val logged = visible.filter { it.id in facts.completedHabitIDs }
    val otherDays = visible.filter { today.dayOfWeek.value !in it.weekdays && it.id !in facts.completedHabitIDs }
    val scheduledLogged = facts.habits.count { it.id in facts.completedHabitIDs }
    val logsByHabit = remember(snapshot.habitLogs) { snapshot.habitLogs.groupBy { it.habitID } }
    LazyColumn(Modifier.testTag("habits.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "habits.introduction") {
            Text(copy.t("Your daily rhythm", "Dein täglicher Rhythmus", "Tu ritmo diario"), style = MaterialTheme.typography.headlineSmall)
            Text(copy.displayDate(today.toString()), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text(if (facts.habits.isEmpty()) copy.t("No routines scheduled for today", "Heute sind keine Routinen geplant", "No hay rutinas programadas para hoy") else copy.t("$scheduledLogged of ${facts.habits.size} logged for today",
                "$scheduledLogged von ${facts.habits.size} für heute erfasst",
                "$scheduledLogged de ${facts.habits.size} con registro hoy"),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onNew, enabled = writesEnabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("habits.add")) {
                Text(copy.t("Add habit", "Gewohnheit hinzufügen", "Añadir hábito"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !allDays, onClick = { allDays = false }, label = { Text(copy.today) }, modifier = Modifier.testTag("habits.filter.today"))
                FilterChip(selected = allDays, onClick = { allDays = true }, label = { Text(copy.t("All routines", "Alle Routinen", "Todas las rutinas")) }, modifier = Modifier.testTag("habits.filter.all"))
            }
        }
        if (visible.isEmpty()) item(key = "habits.empty") {
            EmptyMessage(if (active.isEmpty()) copy.t("Start with one small habit", "Beginne mit einer kleinen Gewohnheit", "Empieza con un hábito pequeño")
                else copy.t("Nothing scheduled today", "Heute ist nichts geplant", "Nada programado para hoy"),
                if (active.isEmpty()) copy.t("Choose a routine and the days you want to track.", "Wähle eine Routine und die Tage, die du verfolgen möchtest.", "Elige una rutina y los días que quieres registrar.")
                else copy.t("Your routines are in All routines. Unrecorded days are not counted as misses.", "Deine Routinen findest du unter Alle Routinen. Nicht erfasste Tage gelten nicht als verpasst.", "Tus rutinas están en Todas las rutinas. Los días sin registro no se cuentan como fallos."))
        }
        if (pending.isNotEmpty()) item(key = "habits.pending.heading") {
            HabitGroupHeading(copy.t("For today", "Für heute", "Para hoy"), pending.size)
        }
        items(pending, key = { it.id }) { habit -> HabitDailyRow(habit, false, true, logsByHabit[habit.id].orEmpty().associate { it.date to it.completed }, today, copy, writesEnabled, onEdit, onLog) }
        if (logged.isNotEmpty()) item(key = "habits.logged.heading") {
            HabitGroupHeading(copy.t("Logged today", "Heute erfasst", "Registrados hoy"), logged.size)
        }
        items(logged, key = { it.id }) { habit -> HabitDailyRow(habit, true, today.dayOfWeek.value in habit.weekdays, logsByHabit[habit.id].orEmpty().associate { it.date to it.completed }, today, copy, writesEnabled, onEdit, onLog) }
        if (otherDays.isNotEmpty()) item(key = "habits.other.heading") {
            HabitGroupHeading(copy.t("On other days", "An anderen Tagen", "En otros días"), otherDays.size)
        }
        items(otherDays, key = { it.id }) { habit -> HabitDailyRow(habit, false, false, logsByHabit[habit.id].orEmpty().associate { it.date to it.completed }, today, copy, writesEnabled, onEdit, onLog) }
        if (visible.isNotEmpty()) item(key = "habits.history.legend") {
            Text(copy.t("A check means a recorded completion. A dot means no completion recorded; it does not mean a missed habit.",
                "Ein Haken steht für einen erfassten Abschluss. Ein Punkt bedeutet keinen erfassten Abschluss, nicht eine verpasste Gewohnheit.",
                "Una marca indica una finalización registrada. Un punto significa que no hay finalización registrada, no un hábito omitido."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HabitGroupHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HabitDailyRow(habit: Habit, logged: Boolean, scheduled: Boolean, logs: Map<String, Boolean>, today: LocalDate,
    copy: UiCopy, writesEnabled: Boolean, onEdit: (Habit) -> Unit, onLog: (Habit, Boolean) -> Unit) {
    var notesExpanded by rememberSaveable(habit.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("habits.row.${habit.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = logged, onCheckedChange = { onLog(habit, it) }, enabled = writesEnabled,
                modifier = Modifier.testTag("habits.log.${habit.id}").semantics {
                    contentDescription = copy.t("Log today: ", "Heute protokollieren: ", "Registrar hoy: ") + habit.title
                })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(habit.title, style = MaterialTheme.typography.titleMedium)
                Text(if (logged) copy.t("Logged today", "Heute erfasst", "Registrado hoy") else if (scheduled)
                    copy.t("Ready to record", "Bereit zum Erfassen", "Listo para registrar") else
                    copy.t("Not scheduled today", "Heute nicht geplant", "No programado para hoy"),
                    color = if (logged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                val schedule = if (habit.weekdays.toSet() == (1..7).toSet()) copy.t("Every day", "Jeden Tag", "Cada día") else
                    habit.weekdays.distinct().sorted().mapNotNull { day -> runCatching { DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, copy.locale) }.getOrNull() }.joinToString(" · ")
                Text(schedule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(copy.t("Last seven days", "Letzte sieben Tage", "Últimos siete días"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (offset in 6 downTo 0) {
                val day = today.minusDays(offset.toLong())
                val completed = logs[day.toString()] == true
                val status = if (completed) copy.t("Completion recorded", "Abschluss erfasst", "Finalización registrada") else if (logs.containsKey(day.toString()))
                    copy.t("Not marked complete", "Nicht als erledigt markiert", "No marcado como completado") else
                    copy.t("No log", "Kein Protokoll", "Sin registro")
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = copy.displayDate(day.toString()) + ": " + status },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(day.format(DateTimeFormatter.ofPattern("EE", copy.locale)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Surface(shape = CircleShape, color = if (completed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            if (completed) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                            else Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        }
                    }
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onEdit(habit) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("habits.edit.${habit.id}")) { Text(copy.t("Edit routine", "Routine bearbeiten", "Editar rutina")) }
            if (habit.notes.isNotBlank()) TextButton(onClick = { notesExpanded = !notesExpanded },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(if (notesExpanded) copy.t("Hide notes", "Notizen ausblenden", "Ocultar notas") else copy.t("Notes", "Notizen", "Notas"))
            }
        }
        if (notesExpanded) Text(habit.notes, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun SettingsScreen(state: WorkspaceState, copy: UiCopy, busy: Boolean,
    onPreferences: (Preferences) -> Unit, onExport: () -> Unit, onRestoreBackup: () -> Unit,
    integrationsSection: @Composable () -> Unit = {},
    onRestoreTask: (WorkTask) -> Unit, onRestoreHabit: (Habit) -> Unit, onRestoreAppointment: (Appointment) -> Unit,
    focusSection: String? = null) {
    val snapshot = state.snapshot
    val preferences = snapshot.preferences
    var name by remember(preferences.userName) { mutableStateOf(preferences.userName) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    var archivePage by rememberSaveable { mutableStateOf(0) }
    val writable = !busy && !state.recoveryRequired
    val archivedTasks = snapshot.tasks.filter { it.archived }
    val archivedHabits = snapshot.habits.filter { it.archived }
    val archivedAppointments = snapshot.appointments.filter { it.archived }
    val archiveCount = archivedTasks.size + archivedHabits.size + archivedAppointments.size
    val archivedRecords: List<Any> = archivedTasks + archivedAppointments + archivedHabits
    val pageCount = ((archiveCount + 19) / 20).coerceAtLeast(1)
    val currentPage = archivePage.coerceIn(0, pageCount - 1)
    val language = when (preferences.language) { "en" -> "English"; "de" -> "Deutsch"; "es" -> "Español"; else -> copy.t("System language", "Systemsprache", "Idioma del sistema") }
    val theme = when (preferences.theme) { "light" -> copy.t("Light", "Hell", "Claro"); "dark" -> copy.t("Dark", "Dunkel", "Oscuro"); else -> copy.t("System appearance", "Systemdarstellung", "Apariencia del sistema") }
    val actionColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val listState = rememberLazyListState()
    LaunchedEffect(focusSection) {
        val index = when (focusSection) {
            "profile" -> 1
            "language" -> 2
            "appearance" -> 3
            "integrations", "calendar", "reminders", "widgets", "localai" -> 4
            "data" -> 5
            "archive" -> 6
            else -> null
        }
        if (index != null) listState.scrollToItem(index)
    }
    LazyColumn(Modifier.testTag("settings.screen"), state = listState, contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "settings.introduction") {
            Text(copy.settings, style = MaterialTheme.typography.headlineSmall)
            Text(copy.t("Your preferences and optional device tools.", "Deine Einstellungen und optionalen Gerätefunktionen.", "Tus preferencias y herramientas opcionales del dispositivo."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
        item(key = "settings.profile") {
            IntegrationPanel("profile", copy.t("Profile", "Profil", "Perfil"), focus = focusSection == "profile",
                language = copy.locale.language, summary = preferences.userName.ifBlank { copy.t("Name is optional", "Name ist optional", "Nombre opcional") }) {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 200) name = it },
                    label = { Text(copy.t("Name (optional)", "Name (optional)", "Nombre (opcional)")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings.name"))
                TextButton(onClick = { onPreferences(preferences.copy(userName = name.trim())) }, enabled = writable && name.trim() != preferences.userName,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.testTag("settings.name.save")) {
                    Text(copy.t("Save name", "Name speichern", "Guardar nombre"))
                }
            }
        }
        item(key = "settings.language") {
            IntegrationPanel("language", copy.t("Language", "Sprache", "Idioma"), focus = focusSection == "language", summary = language, language = copy.locale.language) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf("system" to copy.t("System", "System", "Sistema"), "en" to "English", "de" to "Deutsch", "es" to "Español")) {
                        FilterChip(selected = preferences.language == value, onClick = { onPreferences(preferences.copy(language = value)) },
                            enabled = writable, label = { Text(label) }, modifier = Modifier.testTag("settings.language.$value"))
                    }
                }
            }
        }
        item(key = "settings.appearance") {
            IntegrationPanel("appearance", copy.t("Appearance", "Darstellung", "Apariencia"), focus = focusSection == "appearance", summary = theme, language = copy.locale.language) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (value in listOf("system", "light", "dark")) FilterChip(selected = preferences.theme == value,
                        onClick = { onPreferences(preferences.copy(theme = value)) }, enabled = writable,
                        label = { Text(when (value) { "light" -> copy.t("Light", "Hell", "Claro"); "dark" -> copy.t("Dark", "Dunkel", "Oscuro"); else -> copy.t("System", "System", "Sistema") }) },
                        modifier = Modifier.testTag("settings.theme.$value"))
                }
            }
        }
        item(key = "settings.integrations") { integrationsSection() }
        item(key = "settings.data") {
            IntegrationPanel("data", copy.t("Backup and local data", "Sicherung und lokale Daten", "Copias y datos locales"), focus = focusSection == "data",
                language = copy.locale.language, summary = copy.t("Exports include private records and notes", "Exporte enthalten private Einträge und Notizen", "Las copias contienen registros y notas privados")) {
                Text(copy.localOnly, style = MaterialTheme.typography.titleSmall)
                Text(copy.t("Choose a safe place for your exported tasks, appointments, academic records, habits and private notes.",
                    "Wähle einen sicheren Ort für exportierte Aufgaben, Termine, akademische Daten, Gewohnheiten und private Notizen.",
                    "Elige un lugar seguro para exportar tareas, citas, registros académicos, hábitos y notas privadas."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onExport, enabled = !busy, colors = actionColors,
                    modifier = Modifier.fillMaxWidth().testTag("settings.export")) { Text(copy.t("Export local backup", "Lokale Sicherung exportieren", "Exportar copia local")) }
                if (state.backupAvailable) OutlinedButton(onClick = { restoreConfirmation = true }, enabled = !busy, colors = actionColors,
                    modifier = Modifier.fillMaxWidth().testTag("settings.restoreBackup")) {
                    Text(copy.t("Restore previous local backup", "Vorherige lokale Sicherung wiederherstellen", "Restaurar copia local anterior"))
                }
            }
        }
        item(key = "settings.archive") {
            IntegrationPanel("archive", copy.t("Archived history", "Archivierte Historie", "Historial archivado"), focus = focusSection == "archive",
                language = copy.locale.language, summary = copy.quantity(archiveCount, "archived record", "archived records", "archivierter Eintrag", "archivierte Einträge", "registro archivado", "registros archivados")) {
                Text(copy.t("Archiving keeps records and habit logs. Restore an item to use it again.", "Archivieren erhält Einträge und Gewohnheitsprotokolle. Stelle einen Eintrag wieder her, um ihn erneut zu nutzen.",
                    "Archivar conserva los registros y hábitos. Restaura un elemento para volver a usarlo."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (archiveCount == 0) Text(copy.t("No archived items", "Keine archivierten Einträge", "Sin elementos archivados"))
                for (record in archivedRecords.drop(currentPage * 20).take(20)) when (record) {
                    is WorkTask -> {
                        val item = record
                        val liveAssessment = (item.sourceAssessmentID == null || snapshot.assessments.any { it.id == item.sourceAssessmentID && !it.archived }) &&
                            (item.sourceCourseID == null || snapshot.courses.any { it.id == item.sourceCourseID && !it.archived })
                        ArchivedRow(item.title, copy, writable && liveAssessment) { onRestoreTask(item) }
                        if (!liveAssessment) Text(copy.t("Linked academic source archived. Keep this history or create a new proposal.", "Verknüpfte Semesterquelle archiviert. Behalte diese Historie oder erstelle einen neuen Vorschlag.",
                            "El origen académico vinculado está archivado. Conserva este historial o crea una propuesta nueva."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Appointment -> ArchivedRow(record.title, copy, writable) { onRestoreAppointment(record) }
                    is Habit -> ArchivedRow(record.title + " · " + copy.quantity(snapshot.habitLogs.count { it.habitID == record.id && it.completed },
                        "recorded log", "recorded logs", "erfasstes Protokoll", "erfasste Protokolle", "registro", "registros"), copy, writable) { onRestoreHabit(record) }
                }
                if (pageCount > 1) {
                    Text(copy.t("Page ${currentPage + 1} of $pageCount", "Seite ${currentPage + 1} von $pageCount", "Página ${currentPage + 1} de $pageCount"), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { archivePage = currentPage - 1 }, enabled = currentPage > 0,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.testTag("settings.archive.previous")) { Text(copy.t("Previous", "Zurück", "Anterior")) }
                        TextButton(onClick = { archivePage = currentPage + 1 }, enabled = currentPage < pageCount - 1,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.testTag("settings.archive.next")) { Text(copy.t("Next", "Weiter", "Siguiente")) }
                    }
                }
            }
        }
    }
    if (restoreConfirmation) AlertDialog(onDismissRequest = { restoreConfirmation = false },
        title = { Text(copy.t("Restore previous backup?", "Vorherige Sicherung wiederherstellen?", "¿Restaurar copia anterior?")) },
        text = { Text(copy.t("This replaces the active workspace with the last local backup. Changes made after that backup will not appear in the restored workspace.",
            "Der aktive Arbeitsbereich wird durch die letzte lokale Sicherung ersetzt. Spätere Änderungen sind dort nicht enthalten.",
            "Esto sustituye el espacio activo por la última copia local. Los cambios posteriores no aparecerán en el espacio restaurado.")) },
        confirmButton = { TextButton(onClick = { restoreConfirmation = false; onRestoreBackup() }) { Text(copy.t("Restore backup", "Sicherung wiederherstellen", "Restaurar copia")) } },
        dismissButton = { TextButton(onClick = { restoreConfirmation = false }) { Text(copy.cancel) } })
}

@Composable
private fun ArchivedRow(title: String, copy: UiCopy, enabled: Boolean, onRestore: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRestore, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))) { Text(copy.t("Restore", "Wiederherstellen", "Restaurar")) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
