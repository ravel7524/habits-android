package ch.habits.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanValidator
import ch.habits.android.data.AssistantContextExporter
import ch.habits.android.data.PlanPreview
import ch.habits.android.data.PlanReceipt
import ch.habits.android.data.ReceiptState
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.data.WorkspaceState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantPlanScreen(
    repository: WorkspaceRepository,
    state: WorkspaceState,
    semesterID: String?,
    copy: UiCopy,
    desktopSection: @Composable (onPlan: (AcademicPlan) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var includeNotes by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PlanPreview?>(null) }
    var receipt by remember { mutableStateOf<PlanReceipt?>(null) }
    var error by remember { mutableStateOf<ProposalFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sharingDetails by remember { mutableStateOf(false) }
    var receiptDetails by remember { mutableStateOf(false) }
    var errorDetails by remember { mutableStateOf(false) }
    fun operation(action: ProposalAction, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            errorDetails = false
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = ProposalFailure(action, failure.message)
            } finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation(ProposalAction.READ) {
            val loaded = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer, 0, minOf(buffer.size, AcademicPlanValidator.MAX_BYTES - output.size() + 1))
                        if (read < 0) break
                        require(output.size() + read <= AcademicPlanValidator.MAX_BYTES) { "Proposal exceeds the 2 MiB limit." }
                        output.write(buffer, 0, read)
                    }
                    // The decoder validates both Unicode and the proposal contract.
                    val bytes = output.toByteArray()
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                } ?: throw IllegalStateException("The selected file could not be opened.")
            }
            val plan = withContext(Dispatchers.Default) { AcademicPlanCodec.decode(loaded) }
            val reviewed = repository.preview(plan)
            text = loaded
            preview = reviewed
            receipt = reviewed.receipt
        }
    }

    // The deferred lazy DSL must build one coherent interval list. Reading the
    // delegated state inside it can change its conditional row count during
    // measurement instead of rebuilding through this composition.
    val renderedPreview = preview
    val renderedReceipt = receipt
    val renderedError = error
    val renderedBusy = busy
    val renderedErrorDetails = errorDetails
    val selected = state.snapshot.semesters.firstOrNull { it.id == semesterID && !it.archived }
    LazyColumn(Modifier.fillMaxWidth().testTag("assistant.plan.screen").semantics {
        stateDescription = when {
            renderedError != null -> copy.t("Proposal error", "Vorschlagsfehler", "Error de propuesta")
            renderedPreview != null -> copy.t("Proposal review", "Vorschlagsprüfung", "Revisión de propuesta")
            else -> copy.t("Proposal input", "Vorschlagseingabe", "Entrada de propuesta")
        }
    }, contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "assistant.introduction") {
            Text(copy.t("Plan with your assistant", "Mit deinem Assistenten planen", "Planifica con tu asistente"),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(copy.t("Share a timetable. Review the proposal. Save when it is right.",
                "Teile einen Stundenplan. Prüfe den Vorschlag. Speichere, wenn er stimmt.",
                "Comparte un calendario. Revisa la propuesta. Guarda cuando sea correcta."),
                modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item(key = "assistant.share") {
            AssistantStepCard(copy.t("1 · Share instructions", "1 · Anleitung teilen", "1 · Compartir instrucciones")) {
            Text(selected?.title ?: copy.t("No existing semester selected", "Kein bestehendes Semester ausgewählt", "Sin semestre existente seleccionado"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(copy.t("Include academic notes", "Akademische Notizen einschliessen", "Incluir notas académicas"),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = includeNotes, onCheckedChange = { includeNotes = it }, modifier = Modifier.testTag("assistant.plan.includeNotes").semantics {
                    contentDescription = copy.t("Include academic notes", "Akademische Notizen einschliessen", "Incluir notas académicas")
                })
            }
            Text(copy.t("Only selected academic work is shared. Your assistant may send it to its AI provider.",
                "Nur gewählte akademische Einträge werden geteilt. Dein Assistent kann sie an seinen KI-Anbieter senden.",
                "Solo se comparte el trabajo académico elegido. Tu asistente puede enviarlo a su proveedor de IA."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val sharedSnapshot = if (semesterID == null) state.snapshot.copy(semesters = emptyList(), courses = emptyList(), assessments = emptyList(), tasks = emptyList()) else state.snapshot
                val instructions = AssistantContextExporter.build(sharedSnapshot, semesterID, includeNotes)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, instructions)
                context.startActivity(Intent.createChooser(intent, copy.t("Choose your assistant", "Assistenten wählen", "Elige tu asistente")))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.share"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(copy.t("Share assistant instructions", "Anleitung für den Assistenten teilen", "Compartir instrucciones para el asistente"))
            }
            TextButton(onClick = { sharingDetails = !sharingDetails }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(if (sharingDetails) copy.t("Hide sharing details", "Details ausblenden", "Ocultar detalles") else copy.t("What gets shared?", "Was wird geteilt?", "¿Qué se comparte?"))
            }
            if (sharingDetails) Text(copy.t("Only the selected semester, its academic records and linked preparation are included. Notes are included only if enabled. Unrelated tasks, habits and Calendar events are excluded. Your chosen assistant may send shared text to its AI provider.",
                "Enthalten sind nur das gewählte Semester, seine akademischen Einträge und verknüpfte Vorbereitung. Notizen werden nur auf Wunsch geteilt. Andere Aufgaben, Gewohnheiten und Kalenderereignisse bleiben ausgeschlossen. Dein Assistent kann den Text an seinen KI-Anbieter senden.",
                "Solo se incluyen el semestre elegido, sus registros académicos y la preparación vinculada. Las notas se añaden solo si las activas. Se excluyen otras tareas, hábitos y eventos del calendario. Tu asistente puede enviar el texto a su proveedor de IA."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "assistant.desktop") {
            desktopSection { plan -> operation(ProposalAction.READ) {
                text = AcademicPlanCodec.encode(plan)
                preview = repository.preview(plan)
                receipt = preview?.receipt
            } }
        }
        if (renderedPreview == null) {
            item(key = "assistant.input") {
                AssistantStepCard(copy.t("2 · Open a proposal", "2 · Vorschlag öffnen", "2 · Abrir una propuesta")) {
                Text(copy.t("Open the assistant’s file or paste its JSON below.", "Öffne die Datei des Assistenten oder füge unten sein JSON ein.", "Abre el archivo del asistente o pega su JSON abajo."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.open"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Text(copy.t("Open JSON file", "JSON-Datei öffnen", "Abrir archivo JSON"))
                }
                OutlinedTextField(value = text, onValueChange = { value ->
                    if (value.toByteArray(Charsets.UTF_8).size <= AcademicPlanValidator.MAX_BYTES) {
                        text = value
                        error = null
                    } else {
                        errorDetails = false
                        error = ProposalFailure(ProposalAction.READ, copy.t("Proposal exceeds the 2 MiB limit.", "Der Vorschlag überschreitet 2 MiB.", "La propuesta supera el límite de 2 MiB."))
                    }
                }, label = { Text(copy.t("Paste proposal JSON", "JSON-Vorschlag einfügen", "Pegar propuesta JSON")) },
                    minLines = 3, maxLines = 7, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistant.plan.input"))
                Button(onClick = { operation(ProposalAction.READ) {
                    val plan = withContext(Dispatchers.Default) { AcademicPlanCodec.decode(text) }
                    preview = repository.preview(plan)
                    receipt = preview?.receipt
                } }, enabled = text.isNotBlank() && !busy && !state.recoveryRequired,
                    modifier = Modifier.fillMaxWidth().testTag("assistant.plan.review")) {
                    Text(copy.t("Review proposal", "Vorschau prüfen", "Revisar propuesta"))
                }
                Text(copy.t("Reviewing does not save anything.", "Die Vorschau speichert noch nichts.", "Revisar no guarda nada."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        renderedPreview?.let { reviewed ->
            val plan = reviewed.plan
            item(key = "assistant.review") {
                AssistantStepCard(copy.t("2 · Review proposal", "2 · Vorschlag prüfen", "2 · Revisar propuesta")) {
                Text(plan.semester.title, style = MaterialTheme.typography.titleLarge)
                Text(copy.t("Source: ", "Quelle: ", "Fuente: ") + plan.source, style = MaterialTheme.typography.bodyMedium)
                Text(copy.displayDate(plan.semester.startDate) + " · " + plan.semester.weekCount + " " +
                    copy.t("teaching weeks", "Unterrichtswochen", "semanas lectivas"))
                Text(copy.t("Source time zone: ", "Zeitzone der Quelle: ", "Zona horaria de origen: ") + plan.semester.timeZoneIdentifier,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(copy.t("Check the assistant’s source and dates against your original timetable.",
                    "Vergleiche Quelle und Daten des Assistenten mit deinem Originalplan.",
                    "Compara la fuente y las fechas del asistente con tu calendario original."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "assistant.assessments.header") { AssistantSection(copy.t("Exams and deadlines", "Prüfungen und Fristen", "Exámenes y plazos")) }
            items(plan.assessments, key = { "assessment-" + it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("assistant.plan.assessment.${item.id}"), colors = assistantCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(assessmentKindLabel(item.kind, copy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(plan.courses.firstOrNull { it.id == item.courseID }?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(copy.displayDate(item.dueDate) + " · " + (item.dueTime?.let(copy::displayTime) ?: copy.unknownTime))
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                }
                }
            }
            if (plan.preparationTasks.isNotEmpty()) {
                item(key = "assistant.preparation.header") { AssistantSection(copy.t("Preparation tasks", "Vorbereitungsaufgaben", "Tareas de preparación")) }
                items(plan.preparationTasks, key = { "preparation-" + it.id }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth(), colors = assistantCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(copy.displayDate(item.dueDate) + (item.dueDate?.let { " · " + (item.dueTime?.let(copy::displayTime) ?: copy.unknownTime) } ?: ""))
                        plan.assessments.firstOrNull { it.id == item.assessmentID }?.let { assessment ->
                            Text(copy.t("For: ", "Für: ", "Para: ") + assessment.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${item.estimatedWorkMinutes} " + copy.t("estimated minutes", "geschätzte Minuten", "minutos estimados"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    }
                }
                item(key = "assistant.preparation.disclosure") {
                    Text(copy.t("Preparation dates are task deadlines, not reserved Calendar time. Reminders stay off. Completing preparation does not complete an exam.",
                        "Vorbereitungsdaten sind Aufgabenfristen, keine reservierten Kalenderzeiten. Erinnerungen bleiben aus. Erledigte Vorbereitung erledigt keine Prüfung.",
                        "Las fechas de preparación son plazos de tareas, no tiempo reservado en el calendario. Los recordatorios quedan desactivados. Completar la preparación no completa el examen."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "assistant.save") {
                val saved = renderedReceipt ?: reviewed.receipt
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved != null) {
                    Card(Modifier.fillMaxWidth().testTag("assistant.plan.receipt").semantics { liveRegion = LiveRegionMode.Polite }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (saved.state == ReceiptState.UNDONE)
                                copy.t("Import undone", "Import rückgängig gemacht", "Importación deshecha") else copy.t("Saved on this phone", "Auf diesem Telefon gespeichert", "Guardado en este teléfono"),
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (saved.state == ReceiptState.UNDONE)
                                copy.t("This proposal will not be applied again.", "Dieser Vorschlag wird nicht erneut angewendet.", "Esta propuesta no se aplicará de nuevo.") else
                                copy.t("Reopening this proposal creates no duplicates. This receipt confirms a local save.", "Erneutes Öffnen erstellt keine Duplikate. Dieser Beleg bestätigt die lokale Speicherung.", "Abrir esta propuesta de nuevo no crea duplicados. Este recibo confirma el guardado local."))
                            TextButton(onClick = { receiptDetails = !receiptDetails }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                                Text(if (receiptDetails) copy.t("Hide receipt details", "Belegdetails ausblenden", "Ocultar detalles del recibo") else
                                    copy.t("Receipt details", "Belegdetails", "Detalles del recibo"))
                            }
                            if (receiptDetails) Text(copy.t("Proposal: ", "Vorschlag: ", "Propuesta: ") + saved.proposalID, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (saved.state == ReceiptState.APPLIED) {
                        OutlinedButton(onClick = { operation(ProposalAction.UNDO) { receipt = repository.undo(saved.proposalID) } },
                            enabled = !busy && !state.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.undo"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                            Text(copy.t("Undo import", "Import rückgängig machen", "Deshacer importación"))
                        }
                        Text(copy.t("Undo is available while imported items remain unchanged. It preserves unrelated data.",
                            "Rückgängig ist möglich, solange importierte Einträge unverändert sind. Andere Daten bleiben erhalten.",
                            "Puedes deshacer mientras los elementos importados no cambien. Se conservan los demás datos."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    AssistantSection(copy.t("3 · Confirm additions", "3 · Ergänzungen bestätigen", "3 · Confirmar elementos"))
                    Text(copy.quantity(reviewed.counts.semesters, "semester", "semesters", "Semester", "Semester", "semestre", "semestres") + " · " +
                        copy.quantity(reviewed.counts.courses, "course", "courses", "Kurs", "Kurse", "curso", "cursos"))
                    Text(copy.quantity(reviewed.counts.assessments, "assessment", "assessments", "Prüfung oder Frist", "Prüfungen und Fristen", "evaluación", "evaluaciones") + " · " +
                        copy.quantity(reviewed.counts.preparationTasks, "preparation task", "preparation tasks", "Vorbereitungsaufgabe", "Vorbereitungsaufgaben", "tarea de preparación", "tareas de preparación"))
                    Button(onClick = { operation(ProposalAction.SAVE) { receipt = repository.apply(reviewed) } },
                        enabled = !busy && !state.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.apply")) { Text(copy.save) }
                    TextButton(onClick = { operation(ProposalAction.REFRESH) { preview = repository.preview(plan); receipt = preview?.receipt } },
                        enabled = !busy, modifier = Modifier.testTag("assistant.plan.refresh"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                        Text(copy.t("Refresh preview", "Vorschau aktualisieren", "Actualizar vista previa"))
                    }
                }
                Text(copy.t("Existing work is preserved. A paired desktop can receive the phone’s save receipt.",
                    "Bestehende Aufgaben bleiben erhalten. Ein gekoppelter Desktop kann den Speicherbeleg des Telefons erhalten.",
                    "Se conserva el trabajo existente. El ordenador vinculado puede recibir el recibo de guardado del teléfono."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { preview = null; receipt = null; error = null }, enabled = !busy,
                    modifier = Modifier.testTag("assistant.plan.edit"), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Text(copy.t("Open another proposal", "Anderen Vorschlag öffnen", "Abrir otra propuesta"))
                }
                }
            }
        }
        if (renderedBusy) item(key = "assistant.busy") { CircularProgressIndicator(Modifier.testTag("assistant.plan.busy")) }
        renderedError?.let { failure -> item(key = "assistant.error") {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (failure.action) {
                ProposalAction.READ -> copy.t("Proposal could not be read", "Vorschlag konnte nicht gelesen werden", "No se pudo leer la propuesta")
                ProposalAction.REFRESH -> copy.t("Review could not be refreshed", "Vorschau konnte nicht aktualisiert werden", "No se pudo actualizar la revisión")
                ProposalAction.SAVE -> copy.t("Save could not be confirmed", "Speicherung konnte nicht bestätigt werden", "No se pudo confirmar el guardado")
                ProposalAction.UNDO -> copy.t("Undo could not be confirmed", "Rückgängigmachen konnte nicht bestätigt werden", "No se pudo confirmar la acción de deshacer")
            }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("assistant.plan.error").semantics { liveRegion = LiveRegionMode.Polite })
            when (failure.action) {
                ProposalAction.READ -> {
                    Text(if (renderedPreview == null && renderedReceipt == null) copy.t("Nothing was saved.", "Es wurde nichts gespeichert.", "No se guardó nada.") else
                        copy.t("This action did not save anything.", "Diese Aktion hat nichts gespeichert.", "Esta acción no guardó nada."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(copy.t("Open the assistant’s proposal file, or replace the pasted JSON and review again.",
                        "Öffne die Vorschlagsdatei des Assistenten oder ersetze das eingefügte JSON und prüfe erneut.",
                        "Abre el archivo de propuesta del asistente o sustituye el JSON pegado y vuelve a revisar."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("assistant.plan.error.open"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Text(copy.t("Open proposal file", "Vorschlagsdatei öffnen", "Abrir archivo de propuesta"))
                    }
                    TextButton(onClick = { preview = null; receipt = null; error = null }, enabled = !busy,
                        modifier = Modifier.testTag("assistant.plan.error.edit"), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Text(copy.t("Edit pasted proposal", "Eingefügten Vorschlag bearbeiten", "Editar propuesta pegada"))
                    }
                }
                ProposalAction.REFRESH -> Text(copy.t("This review did not change your saved records. Try refreshing it again.",
                    "Diese Vorschau hat deine gespeicherten Daten nicht geändert. Versuche erneut zu aktualisieren.",
                    "Esta revisión no cambió tus registros guardados. Intenta actualizarla de nuevo."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                ProposalAction.SAVE -> Text(copy.t("Check the receipt on this phone before trying again. If your workspace changed, refresh the review first.",
                    "Prüfe den Beleg auf diesem Telefon, bevor du es erneut versuchst. Bei geänderten Daten aktualisiere zuerst die Vorschau.",
                    "Comprueba el recibo en este teléfono antes de reintentarlo. Si cambiaron tus registros, actualiza primero la revisión."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                ProposalAction.UNDO -> Text(copy.t("Check the receipt and saved items. Undo is available only while imported records remain unchanged.",
                    "Prüfe den Beleg und die gespeicherten Einträge. Rückgängig ist nur möglich, solange importierte Daten unverändert sind.",
                    "Comprueba el recibo y los elementos guardados. Solo puedes deshacer si los registros importados no han cambiado."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (failure.action != ProposalAction.READ) renderedPreview?.let { currentReview ->
                OutlinedButton(onClick = { operation(ProposalAction.REFRESH) { preview = repository.preview(currentReview.plan); receipt = preview?.receipt } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistant.plan.error.refresh"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Text(if (failure.action == ProposalAction.UNDO) copy.t("Refresh receipt", "Beleg aktualisieren", "Actualizar recibo") else
                        copy.t("Refresh review", "Vorschau aktualisieren", "Actualizar revisión"))
                }
            }
            failure.diagnostic?.takeIf { it.isNotBlank() }?.let { diagnostic ->
                TextButton(onClick = { errorDetails = !errorDetails }, modifier = Modifier.testTag("assistant.plan.error.details"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Text(if (renderedErrorDetails) copy.t("Hide technical details", "Technische Details ausblenden", "Ocultar detalles técnicos") else
                        copy.t("Technical details", "Technische Details", "Detalles técnicos"))
                }
                if (renderedErrorDetails) Text(diagnostic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag("assistant.plan.error.diagnostic"))
            }
            }
            }
        } }
    }
}

// READ and REFRESH never invoke a workspace writer; SAVE and UNDO may have
// reached storage before failing and must not assert that nothing was saved.
private enum class ProposalAction { READ, REFRESH, SAVE, UNDO }
private data class ProposalFailure(val action: ProposalAction, val diagnostic: String?)

@Composable
private fun AssistantStepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = assistantCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun AssistantSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun assistantCardColors() = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
