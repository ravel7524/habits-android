package ch.habits.android.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CalendarSettingsSection(connection: CalendarConnection, language: String) {
    val state by connection.state.collectAsStateWithLifecycle()
    val copy = CalendarCopy(language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actionFailed by remember { mutableStateOf(false) }
    var selectionPending by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var shownCalendars by rememberSaveable { mutableStateOf(20) }
    fun act(block: suspend () -> Unit) { scope.launch {
        actionFailed = false
        try { block() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            actionFailed = true
        }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { act { connection.refresh() } }
    fun selectCalendars(ids: Set<Long>) {
        if (selectionPending) return
        selectionPending = true
        scope.launch {
            actionFailed = false
            try { connection.selectCalendars(ids) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; actionFailed = true }
            finally { selectionPending = false }
        }
    }
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val status = when (state.coverage) {
        CalendarCoverage.OFF -> copy.text("Reading is off", "Lesen ist ausgeschaltet", "Lectura desactivada")
        CalendarCoverage.PERMISSION_REQUIRED -> copy.text("Read permission needed", "Leseberechtigung erforderlich", "Se necesita permiso de lectura")
        CalendarCoverage.NO_SELECTION -> copy.text("Choose calendars below", "Kalender unten auswählen", "Elige calendarios abajo")
        CalendarCoverage.LOADING -> copy.text("Refreshing calendars…", "Kalender werden aktualisiert…", "Actualizando calendarios…")
        CalendarCoverage.AVAILABLE -> copy.text("${state.selectedCalendarIDs.size} selected · read-only snapshot", "${state.selectedCalendarIDs.size} ausgewählt · nur lesbare Momentaufnahme", "${state.selectedCalendarIDs.size} seleccionados · instantánea de solo lectura")
        CalendarCoverage.UNAVAILABLE -> copy.text("Could not read events. Refresh to retry.", "Termine nicht lesbar. Zum Wiederholen aktualisieren.", "No se pudieron leer eventos. Actualiza para reintentar.")
        CalendarCoverage.TRUNCATED -> copy.text("Too many events. Coverage is incomplete.", "Zu viele Termine. Abdeckung ist unvollständig.", "Demasiados eventos. Cobertura incompleta.")
        CalendarCoverage.SELECTION_UNAVAILABLE -> copy.text("A selected calendar is missing or hidden.", "Ein ausgewählter Kalender fehlt oder ist ausgeblendet.", "Falta un calendario seleccionado o está oculto.")
        CalendarCoverage.SETTINGS_ERROR -> copy.text("Connection settings need recovery.", "Verbindungseinstellungen müssen wiederhergestellt werden.", "Es necesario recuperar la conexión.")
    }
    Column(Modifier.fillMaxWidth().testTag("calendar.settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(copy.text("Show your chosen calendars beside saved appointments.", "Zeige ausgewählte Kalender neben gespeicherten Terminen.", "Muestra los calendarios elegidos junto a las citas guardadas."),
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().toggleable(value = state.enabled, role = Role.Switch,
            enabled = state.coverage != CalendarCoverage.SETTINGS_ERROR, onValueChange = { enabled -> act {
                connection.setEnabled(enabled)
                if (enabled && context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.READ_CALENDAR)
            } }).padding(vertical = 4.dp).testTag("calendar.enabled"), verticalAlignment = Alignment.CenterVertically) {
            Text(copy.text("Read calendars", "Kalender lesen", "Leer calendarios"), modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = null, enabled = state.coverage != CalendarCoverage.SETTINGS_ERROR)
        }
        Text(status, modifier = Modifier.testTag("calendar.coverage").semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = if (state.coverage in setOf(CalendarCoverage.UNAVAILABLE, CalendarCoverage.TRUNCATED, CalendarCoverage.SETTINGS_ERROR, CalendarCoverage.SELECTION_UNAVAILABLE))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.enabled && !state.permissionGranted) Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) },
            modifier = Modifier.fillMaxWidth().testTag("calendar.permission")) { Text(copy.text("Allow read access", "Lesezugriff erlauben", "Permitir lectura")) }
        if (state.enabled && state.permissionGranted && state.calendarChoices.isNotEmpty()) {
            Text(copy.text("Choose calendars", "Kalender auswählen", "Elegir calendarios"), style = MaterialTheme.typography.titleSmall)
            val duplicateNames = state.calendarChoices.groupingBy { it.displayName }.eachCount().filterValues { it > 1 }.keys
            state.calendarChoices.take(shownCalendars).forEach { calendar ->
                val selected = calendar.id in state.selectedCalendarIDs
                Row(Modifier.fillMaxWidth().testTag("calendar.choice.${calendar.id}")
                    .toggleable(value = selected, enabled = !state.busy && !selectionPending && calendar.visible, role = Role.Checkbox, onValueChange = { checked ->
                        val current = connection.state.value.selectedCalendarIDs
                        selectCalendars(if (checked) current + calendar.id else current - calendar.id)
                    }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(selected, onCheckedChange = null, enabled = !state.busy && !selectionPending && calendar.visible)
                    Column(Modifier.weight(1f)) {
                        Text(calendar.displayName.ifBlank { copy.text("Untitled calendar", "Kalender ohne Titel", "Calendario sin título") }, style = MaterialTheme.typography.bodyMedium)
                        if (calendar.displayName in duplicateNames) calendar.accountLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!calendar.visible) Text(copy.text("Hidden in Calendar", "Im Kalender ausgeblendet", "Oculto en Calendario"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.calendarChoices.size > shownCalendars) TextButton(onClick = { shownCalendars += 20 }, colors = textColors,
                modifier = Modifier.testTag("calendar.more")) { Text(copy.text("Show more calendars", "Weitere Kalender anzeigen", "Mostrar más calendarios")) }
        }
        if (state.enabled && state.permissionGranted) OutlinedButton(onClick = { act { connection.refresh() } }, enabled = !state.busy,
            colors = outlineColors, modifier = Modifier.fillMaxWidth().testTag("calendar.refresh")) { Text(copy.text("Refresh calendars", "Kalender aktualisieren", "Actualizar calendarios")) }
        if (state.coverage == CalendarCoverage.SETTINGS_ERROR) OutlinedButton(onClick = { act { connection.resetLocalSettings() } },
            colors = outlineColors, modifier = Modifier.fillMaxWidth().testTag("calendar.resetSettings")) {
            Text(copy.text("Reset connection settings", "Verbindungseinstellungen zurücksetzen", "Restablecer conexión"))
        }
        if (state.selectedCalendarIDs.isNotEmpty()) TextButton(onClick = { selectCalendars(emptySet()) }, enabled = !state.busy && !selectionPending,
            colors = textColors, modifier = Modifier.testTag("calendar.clearSelection")) { Text(copy.text("Clear selection", "Auswahl aufheben", "Quitar selección")) }
        Text(copy.text("Read-only and on this phone. Calendar content stays out of backups and assistant sharing.",
            "Nur Lesen, auf diesem Telefon. Kalenderinhalte bleiben außerhalb von Sicherungen und Assistentenfreigaben.",
            "Solo lectura, en este teléfono. El contenido no se incluye en copias ni se comparte con asistentes."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { actionFailed = true }
        }, colors = textColors, modifier = Modifier.testTag("calendar.systemSettings")) { Text(copy.text("Android permission settings", "Android-Berechtigungen", "Permisos de Android")) }
        TextButton(onClick = { details = !details }, colors = textColors, modifier = Modifier.testTag("calendar.details")) {
            Text(if (details) copy.text("Hide details", "Details ausblenden", "Ocultar detalles") else copy.text("Privacy and coverage details", "Datenschutz und Abdeckung", "Privacidad y cobertura"))
        }
        if (details) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(copy.text("Habits never changes Calendar events or books time. Nothing is selected automatically. Declining or revoking read permission hides events without changing your plans.",
                "Habits ändert keine Kalendertermine und bucht keine Zeit. Nichts wird automatisch ausgewählt. Ablehnen oder Entziehen der Leseberechtigung blendet Termine aus, ohne deine Pläne zu ändern.",
                "Habits no modifica eventos ni reserva tiempo. No selecciona calendarios automáticamente. Rechazar o retirar el permiso oculta los eventos sin cambiar tus planes."), style = MaterialTheme.typography.bodySmall)
            Text(copy.status(state.coverage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionFailed) Text(copy.text("Action paused. Refresh or check Android permissions; your records were preserved.",
            "Aktion angehalten. Aktualisiere oder prüfe Android-Berechtigungen; deine Einträge bleiben erhalten.",
            "Acción pausada. Actualiza o revisa los permisos; tus registros se conservaron."), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("calendar.error"))
    }
}
