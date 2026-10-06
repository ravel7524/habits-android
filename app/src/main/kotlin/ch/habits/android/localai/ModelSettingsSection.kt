package ch.habits.android.localai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** File delivery and native memory are distinct phases. All model actions remain explicit. */
@Composable
fun ModelSettingsSection(controller: LocalAIController, language: String) {
    val runtime by controller.state.collectAsStateWithLifecycle()
    val selected by controller.selected.collectAsStateWithLifecycle()
    val store = remember(controller, selected.id) { controller.store }
    val delivery by store.state.collectAsStateWithLifecycle()
    val descriptor = store.descriptor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = if (language == "system") Locale.getDefault().language else language
    fun t(en: String, de: String, es: String) = when (lang) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
    val locale = Locale.forLanguageTag(when (lang) { "german" -> "de"; "spanish" -> "es"; "english" -> "en"; else -> lang })
    fun size(bytes: Long): String {
        val giga = bytes >= 1_000_000_000
        return NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (giga) 2 else 1 }
            .format(bytes.coerceAtLeast(0) / if (giga) 1_000_000_000.0 else 1_000_000.0) + if (giga) " GB" else " MB"
    }
    fun ram(model: ModelDescriptor) = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(model.minRamBytes / (1024.0 * 1024 * 1024))
    var commandPending by remember(controller) { mutableStateOf(false) }
    var localError by remember(controller) { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val deliveryBusy = delivery.status in setOf(ModelDeliveryStatus.DOWNLOADING, ModelDeliveryStatus.VERIFYING)
    val busy = commandPending || runtime.busy || deliveryBusy
    val supported = controller.hardwareSupported(descriptor)
    val fastEligible = controller.hardwareSupported(ModelCatalog.fast)
    fun runAction(block: suspend () -> Unit) {
        val phase = controller.store.state.value.status
        if (commandPending || controller.state.value.busy || phase in setOf(ModelDeliveryStatus.DOWNLOADING, ModelDeliveryStatus.VERIFYING)) return
        commandPending = true; localError = null
        scope.launch {
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                localError = failure.message ?: "model action failed"
            } finally { commandPending = false }
        }
    }
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    fun errorCopy(raw: String): String {
        val message = raw.lowercase(Locale.ROOT)
        return when {
            "browser" in message -> t("No browser is available. Open the Liquid AI model license in your browser.", "Kein Browser verfügbar. Öffne die Liquid-AI-Modelllizenz in deinem Browser.", "No hay navegador disponible. Abre la licencia de Liquid AI en tu navegador.")
            "space" in message || "storage" in message -> t("Free space, then retry the download. Your planning records are safe.", "Gib Speicher frei und wiederhole den Download. Deine Planungsdaten bleiben erhalten.", "Libera espacio y reintenta la descarga. Tus planes se conservan.")
            "memory" in message || "could not be loaded" in message -> t("Not enough memory or the model could not load. Close other apps and retry, or choose 230M.", "Zu wenig Arbeitsspeicher oder Laden fehlgeschlagen. Schließe andere Apps und versuche es erneut, oder wähle 230M.", "Falta memoria o no se pudo cargar el modelo. Cierra otras apps y reintenta, o elige 230M.")
            "wait" in message || "finish" in message -> t("Wait for the current action to finish, then retry.", "Warte auf das Ende der aktuellen Aktion und versuche es erneut.", "Espera a que termine la acción actual y reintenta.")
            "recorded facts changed" in message -> t("The source records changed. Open the current report and try again.", "Die Einträge haben sich geändert. Öffne den aktuellen Bericht und versuche es erneut.", "Los registros cambiaron. Abre el informe actual y reintenta.")
            "verification" in message || "checksum" in message || "pinned" in message -> t("The model file did not verify. Retry or remove its files; your planning data stays intact.", "Die Modelldatei wurde nicht bestätigt. Wiederhole oder entferne ihre Dateien; deine Planungsdaten bleiben erhalten.", "No se pudo verificar el archivo. Reintenta o elimina sus archivos; tus planes se conservan.")
            "download" in message || "http" in message || "connectivity" in message -> t("Download interrupted. Check your connection and retry; a compatible partial is retained.", "Download unterbrochen. Prüfe die Verbindung und versuche es erneut; ein passender Teildownload bleibt erhalten.", "Descarga interrumpida. Revisa la conexión y reintenta; se conserva una descarga parcial compatible.")
            else -> t("The action paused. Retry or use the recorded-facts report; your planning records are unchanged.", "Die Aktion wurde angehalten. Versuche es erneut oder nutze den Faktenbericht; deine Planungsdaten sind unverändert.", "La acción se pausó. Reintenta o usa el informe de hechos; tus planes no cambiaron.")
        }
    }
    Column(Modifier.fillMaxWidth().testTag("localai.settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("Highlight recorded facts on this phone. Your tasks and habit logs stay unchanged.", "Hebe erfasste Fakten auf diesem Telefon hervor. Aufgaben und Gewohnheitsprotokolle bleiben unverändert.",
            "Destaca hechos registrados en este teléfono. Tus tareas y hábitos no cambian."), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (model in ModelCatalog.models) {
                val isSelected = model.id == selected.id
                val eligible = controller.hardwareSupported(model)
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                    .selectable(selected = isSelected, enabled = !busy && eligible, role = Role.RadioButton, onClick = { runAction { controller.selectModel(model.id) } })
                    .testTag("localai.model.${model.id}").padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioButton(selected = isSelected, onClick = null, enabled = !busy && eligible)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (model.reasoning) "LFM2.5 2.6B Q4" else "LFM2.5 230M Q4", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        Text(size(model.size) + " · " + t("${ram(model)} GiB RAM minimum", "mindestens ${ram(model)} GiB RAM", "mínimo ${ram(model)} GiB RAM"),
                            style = MaterialTheme.typography.bodySmall, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (!eligible) t("Unavailable on this device", "Auf diesem Gerät nicht verfügbar", "No disponible en este dispositivo")
                            else if (isSelected && model.reasoning) t("Selected · reasoning", "Ausgewählt · Denkmodell", "Seleccionado · razonamiento")
                            else if (isSelected) t("Selected", "Ausgewählt", "Seleccionado")
                            else if (model.reasoning) t("Reasoning · available", "Denkmodell · verfügbar", "Razonamiento · disponible")
                            else t("Fast · available", "Schnell · verfügbar", "Rápido · disponible"),
                            style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!supported) {
            Text(if (fastEligible) t("The selected 2.6B model needs at least 5 GiB RAM. This device can use 230M.", "Das ausgewählte 2.6B-Modell benötigt mindestens 5 GiB RAM. Dieses Gerät kann 230M nutzen.",
                "El modelo 2.6B seleccionado necesita al menos 5 GiB RAM. Este dispositivo puede usar 230M.")
                else t("Local AI is unavailable on this device. Recorded-facts reports still work.", "Lokale KI ist auf diesem Gerät nicht verfügbar. Faktenberichte funktionieren weiterhin.",
                    "La IA local no está disponible en este dispositivo. Los informes de hechos siguen funcionando."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("localai.unsupported"))
            if (fastEligible && selected.id != ModelCatalog.fast.id) OutlinedButton(onClick = { runAction { controller.selectModel(ModelCatalog.fast.id) } }, enabled = !busy,
                colors = outlineColors, modifier = Modifier.fillMaxWidth().testTag("localai.chooseFast")) { Text(t("Use 230M on this device", "230M auf diesem Gerät nutzen", "Usar 230M en este dispositivo")) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val fileStatus = when (delivery.status) {
            ModelDeliveryStatus.MISSING -> if (delivery.received > 0) t("Paused · partial retained", "Pausiert · Teildownload erhalten", "Pausado · parcial conservado") else t("Not downloaded", "Nicht heruntergeladen", "Sin descargar")
            ModelDeliveryStatus.DOWNLOADING -> t("Downloading", "Wird heruntergeladen", "Descargando")
            ModelDeliveryStatus.VERIFYING -> if (runtime.status == LocalAIStatus.LOADING) t("Verifying before load", "Prüfen vor dem Laden", "Verificando antes de cargar")
                else t("Checking stored file", "Gespeicherte Datei prüfen", "Verificando archivo guardado")
            ModelDeliveryStatus.READY -> t("Stored and verified", "Gespeichert und geprüft", "Guardado y verificado")
            ModelDeliveryStatus.ERROR -> t("Not verified · action needed", "Nicht geprüft · Aktion erforderlich", "Sin verificar · requiere atención")
        }
        ModelPhaseLine(t("Model file", "Modelldatei", "Archivo"), fileStatus, "localai.file.status")
        val memoryStatus = when {
            runtime.status == LocalAIStatus.LOADING -> if (delivery.status == ModelDeliveryStatus.VERIFYING) t("Load requested · checking file", "Laden angefordert · Datei prüfen", "Carga solicitada · verificando archivo")
                else t("Loading into memory", "Wird in den Speicher geladen", "Cargando en memoria")
            runtime.status == LocalAIStatus.UNLOADING -> t("Releasing memory", "Speicher wird freigegeben", "Liberando memoria")
            runtime.loaded && runtime.status == LocalAIStatus.GENERATING -> t("Loaded · selecting facts", "Geladen · Faktenauswahl läuft", "Cargado · seleccionando hechos")
            runtime.loaded -> t("Loaded · ready for reports", "Geladen · bereit für Berichte", "Cargado · listo para informes")
            !supported -> t("Not loaded · unavailable on this device", "Nicht geladen · auf diesem Gerät nicht verfügbar", "Sin cargar · no disponible en este dispositivo")
            delivery.status == ModelDeliveryStatus.READY -> t("Not loaded · ready to load", "Nicht geladen · bereit zum Laden", "Sin cargar · listo para cargar")
            delivery.status == ModelDeliveryStatus.VERIFYING -> t("Waiting for file verification", "Wartet auf Dateiprüfung", "Esperando verificación del archivo")
            else -> t("Not loaded · file required first", "Nicht geladen · zuerst Datei benötigt", "Sin cargar · primero se necesita el archivo")
        }
        ModelPhaseLine(t("In memory", "Im Speicher", "En memoria"), memoryStatus, "localai.memory.status")
        if (delivery.status == ModelDeliveryStatus.DOWNLOADING) {
            val total = delivery.total.takeIf { it > 0 } ?: descriptor.size
            LinearProgressIndicator(progress = { (delivery.received.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("localai.download.progress"))
            Text("${size(delivery.received)} / ${size(total)}", style = MaterialTheme.typography.bodySmall)
        } else if (delivery.received > 0 && delivery.status in setOf(ModelDeliveryStatus.MISSING, ModelDeliveryStatus.ERROR)) Text(
            (if (delivery.status == ModelDeliveryStatus.MISSING) t("Retained: ", "Erhalten: ", "Conservado: ")
            else t("Last attempt: ", "Letzter Versuch: ", "Último intento: ")) + size(delivery.received), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (delivery.status == ModelDeliveryStatus.VERIFYING) Text(t("Full-file integrity check before use.", "Vollständige Integritätsprüfung vor der Nutzung.", "Verificación completa antes de usarlo."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("localai.verify.progress"))
        if (runtime.status in setOf(LocalAIStatus.LOADING, LocalAIStatus.UNLOADING)) Text(t("Keep the app open while this action finishes.", "Lass die App geöffnet, bis die Aktion endet.", "Mantén la aplicación abierta hasta que termine."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("localai.memory.progress"))
        Text(t("Download from Hugging Face; no prompts or planning records are sent. Model files are not included in backups.",
            "Download von Hugging Face; keine Prompts oder Planungsdaten werden gesendet. Modelldateien sind nicht in Sicherungen enthalten.",
            "Descarga desde Hugging Face; no se envían instrucciones ni planes. Los archivos no se incluyen en copias."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (delivery.status in setOf(ModelDeliveryStatus.MISSING, ModelDeliveryStatus.ERROR)) Button(onClick = { runAction { controller.download() } }, enabled = supported && !busy && !runtime.loaded,
            modifier = Modifier.fillMaxWidth().testTag("localai.download")) { Text(if (delivery.status == ModelDeliveryStatus.ERROR) t("Retry download", "Download wiederholen", "Reintentar descarga")
            else if (delivery.received > 0) t("Resume download", "Download fortsetzen", "Reanudar descarga") else t("Download ${size(descriptor.size)}", "${size(descriptor.size)} herunterladen", "Descargar ${size(descriptor.size)}")) }
        if (delivery.status == ModelDeliveryStatus.READY && !runtime.loaded) Button(onClick = { runAction { controller.load() } }, enabled = supported && !busy,
            modifier = Modifier.fillMaxWidth().testTag("localai.load")) { Text(t("Load on this phone", "Auf diesem Telefon laden", "Cargar en este teléfono")) }
        if (runtime.loaded) OutlinedButton(onClick = { runAction { controller.unload() } }, enabled = !busy, colors = outlineColors,
            modifier = Modifier.fillMaxWidth().testTag("localai.unload")) { Text(t("Unload from memory", "Aus Speicher entladen", "Descargar de memoria")) }
        if (delivery.status == ModelDeliveryStatus.DOWNLOADING || runtime.status in setOf(LocalAIStatus.LOADING, LocalAIStatus.GENERATING)) OutlinedButton(onClick = { controller.cancel(); localError = null },
            colors = outlineColors, modifier = Modifier.fillMaxWidth().testTag("localai.cancel")) { Text(if (delivery.status == ModelDeliveryStatus.DOWNLOADING)
            t("Cancel download", "Download abbrechen", "Cancelar descarga") else t("Cancel local AI", "Lokale KI abbrechen", "Cancelar IA local")) }
        val error = localError ?: runtime.error ?: delivery.error
        error?.let { Text(errorCopy(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("localai.error").semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(descriptor.licenseURL))); localError = null }
            catch (_: RuntimeException) { localError = "browser unavailable" }
        }, colors = textColors, modifier = Modifier.testTag("localai.license")) { Text(t("Liquid AI · read model license", "Liquid AI · Modelllizenz lesen", "Liquid AI · leer licencia")) }
        TextButton(onClick = { details = !details }, colors = textColors, modifier = Modifier.testTag("localai.details")) {
            Text(if (details) t("Hide model details", "Modelldetails ausblenden", "Ocultar detalles") else t("Storage and model details", "Speicher- und Modelldetails", "Detalles de modelo y almacenamiento"))
        }
        if (details) {
            Text(t("Changing presets unloads the current model. Downloading and loading always need your action. Free storage is needed for the file and verification reserve; loading also needs available RAM. Close other apps if loading fails.",
                "Ein Presetwechsel entlädt das aktuelle Modell. Download und Laden erfordern immer deine Aktion. Freier Speicher wird für Datei und Prüfreserve benötigt; Laden benötigt zusätzlich verfügbaren RAM. Schließe andere Apps, wenn es fehlschlägt.",
                "Cambiar de modelo descarga el actual de memoria. Descargar y cargar siempre requieren tu acción. Se necesita espacio para el archivo y reserva; cargar también necesita RAM disponible. Cierra otras apps si falla."), style = MaterialTheme.typography.bodySmall)
            Text(t("Weights stay in no-backup storage and out of assistant context. Inference runs locally; leaving the foreground cancels work and releases memory. Recorded-facts reports, widgets and reminders remain available without a model.",
                "Modelldateien bleiben im Speicher ohne Sicherung und außerhalb des Assistentenkontexts. Die Verarbeitung läuft lokal; Verlassen des Vordergrunds bricht Arbeit ab und gibt RAM frei. Faktenberichte, Widgets und Erinnerungen bleiben ohne Modell verfügbar.",
                "Los modelos quedan fuera de copias y del contexto del asistente. La inferencia es local; al salir del primer plano se cancela y libera memoria. Los informes, widgets y avisos funcionan sin modelo."), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { runAction { controller.refreshModel() } }, enabled = !busy, colors = textColors,
            modifier = Modifier.testTag("localai.refresh")) { Text(t("Check stored model", "Gespeichertes Modell prüfen", "Comprobar modelo guardado")) }
        if (delivery.status in setOf(ModelDeliveryStatus.READY, ModelDeliveryStatus.ERROR) || delivery.received > 0) {
            TextButton(onClick = { confirmRemoval = true }, enabled = !busy && !runtime.loaded, colors = textColors,
                modifier = Modifier.testTag("localai.remove")) { Text(t("Remove selected model files", "Ausgewählte Modelldateien entfernen", "Eliminar archivos del modelo elegido")) }
            if (runtime.loaded) Text(t("Unload first to remove its files.", "Zum Entfernen zuerst entladen.", "Descarga de memoria antes de eliminar archivos."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text(t("Remove selected model?", "Ausgewähltes Modell entfernen?", "¿Eliminar el modelo elegido?")) },
        text = { Text(t("This removes ${descriptor.label} files and frees storage. Your planning records remain intact. You can download the model again.",
            "Dies entfernt Dateien von ${descriptor.label} und gibt Speicher frei. Deine Planungsdaten bleiben erhalten. Du kannst das Modell erneut herunterladen.",
            "Elimina los archivos de ${descriptor.label} y libera espacio. Tus planes se conservan. Puedes descargarlo de nuevo.")) },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; runAction { controller.remove() } }, enabled = !busy && !runtime.loaded,
            colors = textColors, modifier = Modifier.testTag("localai.remove.confirm")) { Text(t("Remove model", "Modell entfernen", "Eliminar modelo")) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }, colors = textColors, modifier = Modifier.testTag("localai.remove.cancel")) { Text(t("Cancel", "Abbrechen", "Cancelar")) } },
        modifier = Modifier.testTag("localai.remove.dialog"))
}

@Composable
private fun ModelPhaseLine(label: String, status: String, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
