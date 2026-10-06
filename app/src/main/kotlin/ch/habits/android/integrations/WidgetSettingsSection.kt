package ch.habits.android.integrations

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.habits.android.widgets.HabitsWidgets
import java.util.Locale

@Composable
fun WidgetSettingsSection(context: Context, language: String) {
    val lang = if (language == "system") Locale.getDefault().language else language
    fun t(en: String, de: String, es: String) = when (lang) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
    var unsupported by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val buttonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    Column(Modifier.fillMaxWidth().testTag("integrations.widgets"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("A quick look at your plans, without opening the app.", "Ein schneller Blick auf deine Pläne, ohne die App zu öffnen.", "Un vistazo a tus planes sin abrir la aplicación."), style = MaterialTheme.typography.bodyMedium)
        Text(t("Widget titles and recorded completions are visible to anyone who can see your home screen.",
            "Widget-Titel und erfasste Abschlüsse sind für alle sichtbar, die deinen Startbildschirm sehen.",
            "Los títulos y las finalizaciones registradas son visibles para quien pueda ver tu pantalla de inicio."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("Next up", "Als Nächstes", "Próximamente"), style = MaterialTheme.typography.titleSmall)
            Text(t("Upcoming deadlines and saved appointments. Tap to open Planner.", "Anstehende Fristen und gespeicherte Termine. Antippen öffnet den Planer.",
                "Próximos plazos y citas guardadas. Toca para abrir el planificador."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { unsupported = !runCatching { HabitsWidgets.requestPin(context, "next-up") }.getOrDefault(false) }, colors = buttonColors,
                modifier = Modifier.fillMaxWidth().testTag("widgets.pin.next-up")) { Text(t("Add Next up", "Als Nächstes hinzufügen", "Añadir Próximamente")) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("Today's habits", "Heutige Gewohnheiten", "Hábitos de hoy"), style = MaterialTheme.typography.titleSmall)
            Text(t("See today's routines and explicitly record a completion.", "Sieh heutige Routinen und erfasse einen Abschluss ausdrücklich.",
                "Consulta las rutinas de hoy y registra una finalización de forma explícita."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { unsupported = !runCatching { HabitsWidgets.requestPin(context, "habits-today") }.getOrDefault(false) }, colors = buttonColors,
                modifier = Modifier.fillMaxWidth().testTag("widgets.pin.habits-today")) { Text(t("Add Today's habits", "Heutige Gewohnheiten hinzufügen", "Añadir Hábitos de hoy")) }
        }
        if (unsupported) Text(t("Add it from your launcher's Widgets menu: touch and hold an empty home-screen area, then choose Habits.",
            "Füge es im Widget-Menü deines Startprogramms hinzu: Halte eine freie Stelle gedrückt und wähle Habits.",
            "Añádelo desde el menú de widgets: mantén pulsada una zona vacía de inicio y elige Habits."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("widgets.manualPin"))
        TextButton(onClick = { details = !details }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.testTag("widgets.details")) {
            Text(if (details) t("Hide widget details", "Widget-Details ausblenden", "Ocultar detalles") else t("Widget details", "Widget-Details", "Detalles de widgets"))
        }
        if (details) Text(t("Widgets use saved local records and show when they were updated. They do not read personal Calendar content or run AI. Old habit buttons are checked against the current day and record state; a stale button refreshes instead of overwriting a newer log. Launcher refresh and battery rules can delay updates.",
            "Widgets nutzen gespeicherte lokale Einträge und zeigen ihre Aktualisierung. Sie lesen keine persönlichen Kalenderinhalte und starten keine KI. Alte Gewohnheitsbuttons werden mit dem aktuellen Tag und Eintragsstand geprüft; veraltete Buttons aktualisieren, statt neuere Protokolle zu überschreiben. Startprogramm- und Energiesparregeln können Aktualisierungen verzögern.",
            "Los widgets usan registros locales y muestran cuándo se actualizaron. No leen calendarios personales ni ejecutan IA. Los botones antiguos se comprueban con el día y estado actuales; uno obsoleto actualiza en lugar de sobrescribir un registro más reciente. El lanzador y la batería pueden retrasar las actualizaciones."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
