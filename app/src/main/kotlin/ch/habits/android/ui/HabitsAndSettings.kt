@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

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
import androidx.compose.material3.OutlinedTextField
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
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.Preferences
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.data.WorkspaceState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HabitsScreen(snapshot: WorkspaceSnapshot, copy: UiCopy, writesEnabled: Boolean,
    onNew: () -> Unit, onEdit: (Habit) -> Unit, onLog: (Habit, Boolean) -> Unit) {
    var allDays by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val facts = WorkspaceFacts.today(snapshot, today)
    val habits = if (allDays) snapshot.habits.filterNot { it.archived } else facts.habits
    LazyColumn(Modifier.testTag("habits.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(copy.t("Small things, repeated", "Kleine Schritte, wiederholt", "Pequeños pasos, repetidos"), style = MaterialTheme.typography.headlineMedium)
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", copy.locale)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onNew, enabled = writesEnabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("habits.add")) {
                Text(copy.t("Add habit", "Gewohnheit hinzufügen", "Añadir hábito"))
            }
            FilterChip(selected = allDays, onClick = { allDays = !allDays },
                label = { Text(copy.t("All scheduled days", "Alle geplanten Tage", "Todos los días programados")) })
        }
        if (habits.isEmpty()) item {
            EmptyMessage(copy.t("No habits scheduled", "Keine Gewohnheiten geplant", "Sin hábitos programados"),
                copy.t("Choose a routine and the days you want to track. Missing logs stay unrecorded.",
                    "Wähle eine Routine und die Tage, die du verfolgen möchtest. Fehlende Protokolle bleiben nicht erfasst.",
                    "Elige una rutina y los días que quieres registrar. Los registros ausentes siguen sin registrar."))
        }
        items(habits, key = { it.id }) { habit ->
            val logged = habit.id in facts.completedHabitIDs
            Column(Modifier.testTag("habits.row.${habit.id}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = logged, onCheckedChange = { onLog(habit, it) }, enabled = writesEnabled,
                        modifier = Modifier.testTag("habits.log.${habit.id}").semantics {
                            contentDescription = copy.t("Log today: ", "Heute protokollieren: ", "Registrar hoy: ") + habit.title
                        })
                    Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(habit.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (logged) copy.t("Logged today", "Heute protokolliert", "Registrado hoy") else copy.t("Not recorded today", "Heute nicht erfasst", "Sin registro hoy"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (habit.notes.isNotBlank()) Text(habit.notes, style = MaterialTheme.typography.bodySmall)
                        val recent = snapshot.habitLogs.filter { it.habitID == habit.id && it.completed &&
                            LocalDate.parse(it.date) >= today.minusDays(6) && LocalDate.parse(it.date) <= today }.sortedByDescending { it.date }
                        Text(copy.t("Last 7 days: ", "Letzte 7 Tage: ", "Últimos 7 días: ") +
                            (if (recent.isEmpty()) copy.t("No logs recorded", "Keine Protokolle erfasst", "Sin registros") else recent.joinToString(" · ") { it.date }),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onEdit(habit) }, modifier = Modifier.testTag("habits.edit.${habit.id}")) {
                            Text(copy.t("Edit routine", "Routine bearbeiten", "Editar rutina"))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
internal fun SettingsScreen(state: WorkspaceState, copy: UiCopy, busy: Boolean,
    onPreferences: (Preferences) -> Unit, onExport: () -> Unit, onRestoreBackup: () -> Unit,
    onRestoreTask: (WorkTask) -> Unit, onRestoreHabit: (Habit) -> Unit, onRestoreAppointment: (Appointment) -> Unit) {
    val snapshot = state.snapshot
    val preferences = snapshot.preferences
    var name by remember(preferences.userName) { mutableStateOf(preferences.userName) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    LazyColumn(Modifier.testTag("settings.screen"), contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(copy.t("Make this space yours", "Dein persönlicher Raum", "Haz tuyo este espacio"), style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionHeading(copy.t("Profile", "Profil", "Perfil"))
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 200) name = it },
                label = { Text(copy.t("Name (optional)", "Name (optional)", "Nombre (opcional)")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings.name"))
            TextButton(onClick = { onPreferences(preferences.copy(userName = name.trim())) }, enabled = !busy && !state.recoveryRequired,
                modifier = Modifier.testTag("settings.name.save")) { Text(copy.save) }
        }
        item {
            SectionHeading(copy.t("Language", "Sprache", "Idioma"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf("system" to copy.t("System", "System", "Sistema"), "en" to "English", "de" to "Deutsch", "es" to "Español")) {
                    FilterChip(selected = preferences.language == value, onClick = { onPreferences(preferences.copy(language = value)) },
                        enabled = !busy && !state.recoveryRequired, label = { Text(label) }, modifier = Modifier.testTag("settings.language.$value"))
                }
            }
        }
        item {
            SectionHeading(copy.t("Appearance", "Darstellung", "Apariencia"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in listOf("system", "light", "dark")) FilterChip(selected = preferences.theme == value,
                    onClick = { onPreferences(preferences.copy(theme = value)) }, enabled = !busy && !state.recoveryRequired,
                    label = { Text(when (value) { "light" -> copy.t("Light", "Hell", "Claro"); "dark" -> copy.t("Dark", "Dunkel", "Oscuro"); else -> copy.t("System", "System", "Sistema") }) },
                    modifier = Modifier.testTag("settings.theme.$value"))
            }
        }
        item {
            SectionHeading(copy.t("Your local data", "Deine lokalen Daten", "Tus datos locales"))
            Text(copy.localOnly)
            Text(copy.t("A backup export includes your tasks, appointments, academic records, habits, logs and private notes. Choose where to save it.",
                "Ein Sicherungsexport enthält Aufgaben, Termine, akademische Daten, Gewohnheiten, Protokolle und private Notizen. Wähle den Speicherort selbst.",
                "La copia incluye tareas, citas, registros académicos, hábitos y notas privadas. Elige dónde guardarla."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings.export")) {
                Text(copy.t("Export local backup", "Lokale Sicherung exportieren", "Exportar copia local"))
            }
            if (state.backupAvailable) OutlinedButton(onClick = { restoreConfirmation = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("settings.restoreBackup")) {
                Text(copy.t("Restore previous local backup", "Vorherige lokale Sicherung wiederherstellen", "Restaurar copia local anterior"))
            }
        }
        val archivedTasks = snapshot.tasks.filter { it.archived }
        val archivedHabits = snapshot.habits.filter { it.archived }
        val archivedAppointments = snapshot.appointments.filter { it.archived }
        item {
            SectionHeading(copy.t("Archived history", "Archivierte Historie", "Historial archivado"))
            Text(copy.t("Archiving preserves records and habit logs. Restore a record to make it active again.",
                "Archivieren erhält Einträge und Gewohnheitsprotokolle. Stelle einen Eintrag wieder her, um ihn zu aktivieren.",
                "Archivar conserva los registros y hábitos. Restaura un elemento para activarlo de nuevo."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (archivedTasks.isEmpty() && archivedHabits.isEmpty() && archivedAppointments.isEmpty())
                Text(copy.t("No archived tasks, appointments or habits", "Keine archivierten Aufgaben, Termine oder Gewohnheiten", "Sin tareas, citas ni hábitos archivados"))
        }
        items(archivedTasks, key = { "archived-task-" + it.id }) { item ->
            val liveAssessment = item.sourceAssessmentID == null || snapshot.assessments.any { it.id == item.sourceAssessmentID && !it.archived }
            ArchivedRow(item.title, copy, !busy && !state.recoveryRequired && liveAssessment) { onRestoreTask(item) }
            if (!liveAssessment) Text(copy.t("Its linked assessment was archived. Keep this history or create a new proposal.",
                "Die verknüpfte Prüfung wurde archiviert. Behalte diese Historie oder erstelle einen neuen Vorschlag.",
                "Su evaluación vinculada fue archivada. Conserva este historial o crea una propuesta nueva."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(archivedAppointments, key = { "archived-appointment-" + it.id }) { item -> ArchivedRow(item.title, copy, !busy && !state.recoveryRequired) { onRestoreAppointment(item) } }
        items(archivedHabits, key = { "archived-habit-" + it.id }) { item ->
            ArchivedRow(item.title + " · " + snapshot.habitLogs.count { it.habitID == item.id && it.completed } + " " + copy.t("recorded logs", "erfasste Protokolle", "registros"),
                copy, !busy && !state.recoveryRequired) { onRestoreHabit(item) }
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
        TextButton(onClick = onRestore, enabled = enabled) { Text(copy.t("Restore", "Wiederherstellen", "Restaurar")) }
        HorizontalDivider()
    }
}
