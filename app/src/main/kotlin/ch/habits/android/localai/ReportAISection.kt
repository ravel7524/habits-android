package ch.habits.android.localai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.habits.android.calendar.CalendarState
import ch.habits.android.data.WorkspaceSnapshot
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ReportAISection(
    controller: LocalAIController,
    facts: ReportFactSet,
    language: String,
    scope: CoroutineScope,
    currentFingerprint: () -> String,
    onModelSetup: () -> Unit = {},
    snapshot: WorkspaceSnapshot? = null,
    calendar: CalendarState? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val selected by controller.selected.collectAsStateWithLifecycle()
    fun t(en: String, de: String, es: String) = when (language) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
    val locale = Locale.forLanguageTag(when (language) { "de", "german" -> "de"; "es", "spanish" -> "es"; else -> "en" })
    var details by rememberSaveable(facts.kind) { mutableStateOf(false) }
    val highlights = if (state.selectionFingerprint == facts.fingerprint) state.selection.toList() else emptyList()
    val error = state.error
    val availableCount = facts.facts.size
    val selectionLimit = minOf(8, availableCount)
    val canGenerate = state.loaded && !state.busy && facts.facts.isNotEmpty()
    fun generate() {
        scope.launch {
            try { controller.generate(facts, currentFingerprint) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { /* The controller preserves all recorded facts and exposes its failure. */ }
        }
    }
    LaunchedEffect(facts.fingerprint) { controller.invalidateIfDifferent(facts.fingerprint) }

    Card(Modifier.fillMaxWidth().testTag("localai.report"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("AI highlights", "KI-Hervorhebungen", "Destacados con IA"), style = MaterialTheme.typography.titleMedium)
            Text(t("Optional · on this device", "Optional · auf diesem Gerät", "Opcional · en este dispositivo"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.status == LocalAIStatus.GENERATING -> {
                    // Static status keeps CPU inference from competing with animated UI rendering.
                    Text(t("Selecting recorded items… Keep the app open.", "Erfasste Einträge werden ausgewählt… Lass die App geöffnet.",
                        "Seleccionando elementos registrados… Mantén la app abierta."), modifier = Modifier.testTag("localai.report.progress"))
                    TextButton(onClick = controller::cancel, modifier = Modifier.testTag("localai.report.cancel"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                        Text(t("Cancel selection", "Auswahl abbrechen", "Cancelar selección"))
                    }
                }
                state.busy -> Text(t("Preparing the model. Your recorded facts remain available.", "Das Modell wird vorbereitet. Deine erfassten Fakten bleiben verfügbar.",
                    "Preparando el modelo. Los datos registrados siguen disponibles."))
                !state.loaded -> {
                    Text(t("Your report is ready without AI. Set up a local model to choose highlights.",
                        "Dein Bericht ist auch ohne KI verfügbar. Richte ein lokales Modell für Hervorhebungen ein.",
                        "Tu informe está disponible sin IA. Configura un modelo local para elegir destacados."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onModelSetup, modifier = Modifier.testTag("localai.report.setup"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                        Text(t("Set up on-device AI", "Lokale KI einrichten", "Configurar IA local"))
                    }
                }
                facts.facts.isEmpty() -> Text(t("No eligible recorded items to highlight for this period.", "Für diesen Zeitraum gibt es keine passenden erfassten Einträge zum Hervorheben.",
                    "No hay elementos registrados adecuados para destacar en este periodo."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                highlights.isEmpty() -> {
                    Text(t("Choose up to $selectionLimit ${if (selectionLimit == 1) "item" else "items"} from these recorded facts.",
                        "Bis zu $selectionLimit ${if (selectionLimit == 1) "Eintrag" else "Einträge"} aus diesen erfassten Fakten auswählen.",
                        "Elige hasta $selectionLimit ${if (selectionLimit == 1) "elemento" else "elementos"} de estos datos registrados."), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::generate, enabled = canGenerate, modifier = Modifier.testTag("localai.report.generate")) {
                        Text(t("Choose highlights", "Hervorhebungen auswählen", "Elegir destacados"))
                    }
                }
            }
            if (highlights.isNotEmpty()) {
                Text(t("Selected from your recorded facts", "Aus deinen erfassten Fakten ausgewählt", "Seleccionados de tus datos registrados"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                highlights.forEachIndexed { index, fact ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReportHighlightRow(fact, snapshot, calendar, language)
                }
                val elapsed = state.generationMillis?.let { String.format(locale, "%.1f s", it / 1000.0) }
                Text(t("Selected on device", "Auf dem Gerät ausgewählt", "Seleccionado en el dispositivo") + " · ${selected.label}" +
                    elapsed?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("localai.report.source"))
                if (!state.busy) TextButton(onClick = ::generate, enabled = canGenerate,
                    modifier = Modifier.testTag("localai.report.generate"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Text(t("Choose again", "Erneut auswählen", "Elegir de nuevo"))
                }
            }
            if (error != null) {
                Text(t("AI highlights are unavailable. The recorded report stays intact.", "KI-Hervorhebungen sind nicht verfügbar. Der Faktenbericht bleibt erhalten.",
                    "Los destacados de IA no están disponibles. El informe registrado se conserva."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("localai.report.error"))
            }
            Text(t("$availableCount recorded ${if (availableCount == 1) "item" else "items"} available. Saved records stay unchanged.",
                "$availableCount ${if (availableCount == 1) "erfasster Eintrag verfügbar" else "erfasste Einträge verfügbar"}. Gespeicherte Angaben bleiben unverändert.",
                "$availableCount ${if (availableCount == 1) "elemento registrado disponible" else "elementos registrados disponibles"}. Los registros guardados no cambian."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("localai.report.details"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(if (details) t("Hide selection details", "Auswahldetails ausblenden", "Ocultar detalles")
                    else t("How highlights work", "So funktionieren Hervorhebungen", "Cómo funcionan los destacados"))
            }
            if (details) {
                Text(t("The model only selects existing fact IDs. The text, dates and completion evidence remain the original records. Nothing is written to your plans.",
                    "Das Modell wählt nur vorhandene Fakten-IDs. Text, Daten und Abschlussnachweise bleiben die ursprünglichen Angaben. Deine Pläne werden nicht geändert.",
                    "El modelo solo elige datos existentes. El texto, las fechas y las finalizaciones conservan los registros originales. Tus planes no se modifican."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                facts.coverage.forEach { notice -> Text(notice, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("localai.report.error.details"))
            }
        }
    }
}
