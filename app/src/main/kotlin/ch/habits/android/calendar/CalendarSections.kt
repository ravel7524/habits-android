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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Read-only display. It does not create appointments, notifications or report availability claims. */
@Composable
fun CalendarAgendaSection(state: CalendarState, language: String, day: LocalDate? = null, onOpenSettings: (() -> Unit)? = null) {
    val copy = CalendarCopy(language)
    val zone = ZoneId.systemDefault()
    val covered = if (day != null) state.covers(day, zone) else state.coverage == CalendarCoverage.AVAILABLE &&
        state.enabled && state.permissionGranted && state.selectedCalendarIDs.isNotEmpty() && state.window?.timeZoneIdentifier == zone.id
    val events = if (!covered) emptyList() else if (day == null) state.events.filter { it.calendarID in state.selectedCalendarIDs }
        else CalendarProjection.forDay(state, day, zone)
    var displayLimit by remember(state.refreshedAtMillis, day) { mutableStateOf(25) }
    Column(Modifier.fillMaxWidth().testTag("calendar.agenda"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(copy.text("Calendar", "Kalender", "Calendario"),
            style = MaterialTheme.typography.titleMedium)
        Text(if (covered) copy.text("Selected calendars · read only. Availability and attendance remain unknown.",
            "Ausgewählte Kalender · nur lesen. Verfügbarkeit und Teilnahme bleiben unbekannt.",
            "Calendarios seleccionados · solo lectura. Disponibilidad y asistencia desconocidas.")
        else if (state.coverage == CalendarCoverage.AVAILABLE) copy.text("Calendar coverage is not loaded for this day or time zone. Refresh in Settings.",
            "Kalenderdaten für diesen Tag oder diese Zeitzone sind nicht geladen. In Einstellungen aktualisieren.",
            "No hay cobertura para este día o zona horaria. Actualiza en Ajustes.")
        else copy.status(state.coverage), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("calendar.agenda.coverage"))
        if (!covered && onOpenSettings != null) TextButton(onClick = onOpenSettings, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.testTag("calendar.agenda.recover")) {
            Text(if (state.coverage == CalendarCoverage.OFF) copy.text("Set up Calendar", "Kalender einrichten", "Configurar calendario") else copy.text("Choose calendars", "Kalender auswählen", "Elegir calendarios"))
        }
        if (covered) {
            state.window?.let { window -> Text("${copy.date(window.startDate)} – ${copy.date(window.endDateExclusive.minusDays(1))}",
                style = MaterialTheme.typography.bodySmall) }
            state.refreshedAtMillis?.let { refreshed -> Text(copy.text("Read at ", "Gelesen um ", "Leído a las ") +
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(copy.locale).format(Instant.ofEpochMilli(refreshed).atZone(zone)),
                style = MaterialTheme.typography.bodySmall) }
            if (events.isEmpty()) Text(copy.text("No events were returned for this view. This is not a claim of free time.",
                "Für diese Ansicht wurden keine Termine geliefert. Daraus folgt keine freie Zeit.",
                "No se devolvieron eventos para esta vista. Esto no significa que haya tiempo libre."))
            if (events.size > displayLimit) Text(copy.text("Showing ", "Angezeigt: ", "Mostrando ") +
                "$displayLimit / ${events.size}", style = MaterialTheme.typography.bodySmall)
            events.take(displayLimit).forEach { event ->
                Column(Modifier.fillMaxWidth().testTag("calendar.event.${event.id}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(event.title.ifBlank { copy.text("Untitled event", "Termin ohne Titel", "Evento sin título") }, style = MaterialTheme.typography.titleSmall)
                    Text(copy.eventDate(event, zone), style = MaterialTheme.typography.bodyMedium)
                    Text(event.calendarName.ifBlank { copy.text("Selected calendar", "Ausgewählter Kalender", "Calendario seleccionado") },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (events.size > displayLimit) TextButton(onClick = { displayLimit += 25 }, modifier = Modifier.testTag("calendar.agenda.showMore")) {
                Text(copy.text("Show more events", "Weitere Termine anzeigen", "Mostrar más eventos"))
            }
            Text(copy.text("All-day dates stay on their calendar day. Timed events use this phone's time zone: ",
                "Ganztägige Termine behalten ihren Kalendertag. Uhrzeiten nutzen die Zeitzone dieses Telefons: ",
                "Los eventos de todo el día conservan su fecha. Las horas usan la zona de este teléfono: ") + zone.id,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal class CalendarCopy(language: String) {
    private val language = if (language == "system") Locale.getDefault().language else language
    val locale: Locale = Locale.forLanguageTag(this.language)
    fun text(en: String, de: String, es: String): String = when (language) { "de" -> de; "es" -> es; else -> en }
    fun status(coverage: CalendarCoverage): String = when (coverage) {
        CalendarCoverage.OFF -> text("Calendar reading is off. No external events are loaded.", "Kalenderlesen ist ausgeschaltet. Keine externen Termine geladen.", "La lectura está desactivada. No hay eventos externos cargados.")
        CalendarCoverage.PERMISSION_REQUIRED -> text("Read permission is missing or was removed. Calendar coverage is unknown.", "Leseberechtigung fehlt oder wurde entfernt. Kalenderabdeckung ist unbekannt.", "Falta el permiso de lectura o se retiró. La cobertura es desconocida.")
        CalendarCoverage.NO_SELECTION -> text("Choose a calendar. Nothing is selected automatically.", "Wähle einen Kalender. Es wird nichts automatisch ausgewählt.", "Elige un calendario. No se selecciona ninguno automáticamente.")
        CalendarCoverage.LOADING -> text("Reading selected calendars…", "Ausgewählte Kalender werden gelesen…", "Leyendo calendarios seleccionados…")
        CalendarCoverage.AVAILABLE -> text("Selected calendar snapshot is available. Other calendars are not covered.", "Momentaufnahme ausgewählter Kalender verfügbar. Andere Kalender sind nicht abgedeckt.", "La instantánea seleccionada está disponible. No cubre otros calendarios.")
        CalendarCoverage.UNAVAILABLE -> text("Calendar events could not be read. Coverage is unknown; events are hidden.", "Kalendertermine konnten nicht gelesen werden. Abdeckung unbekannt; Termine ausgeblendet.", "No se pudieron leer los eventos. Cobertura desconocida; eventos ocultos.")
        CalendarCoverage.TRUNCATED -> text("The calendar read exceeded its limit. Coverage is incomplete; events are hidden.", "Die Kalenderabfrage überschreitet das Limit. Abdeckung unvollständig; Termine ausgeblendet.", "La lectura superó el límite. Cobertura incompleta; eventos ocultos.")
        CalendarCoverage.SELECTION_UNAVAILABLE -> text("Calendar selection needs updating. Events are hidden.", "Kalenderauswahl aktualisieren. Termine sind ausgeblendet.", "Actualiza la selección de calendarios. Los eventos están ocultos.")
        CalendarCoverage.SETTINGS_ERROR -> text("Calendar connection settings need recovery. The file was preserved; events are hidden.", "Kalender-Verbindungseinstellungen benötigen Wiederherstellung. Datei erhalten; Termine ausgeblendet.", "La conexión necesita recuperación. Se conservó el archivo; eventos ocultos.")
    }
    fun date(value: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value)
    fun eventDate(event: CalendarEvent, zone: ZoneId): String {
        if (event.allDay) {
            val start = Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            return text("All day · ", "Ganztägig · ", "Todo el día · ") + date(start) + if (last == start) "" else " – ${date(last)}"
        }
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
        val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
        return formatter.format(start) + if (event.endMillis == event.startMillis) "" else " – " + formatter.format(end)
    }
}
