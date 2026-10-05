package ch.habits.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun operation(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = copy.t("Could not complete this action. ", "Diese Aktion konnte nicht abgeschlossen werden. ", "No se pudo completar esta acción. ") +
                    (failure.message ?: copy.t("Your saved data is preserved.", "Deine gespeicherten Daten bleiben erhalten.", "Se conservan tus datos guardados."))
            } finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
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

    LazyColumn(Modifier.fillMaxWidth().testTag("assistant.plan.screen").semantics {
        stateDescription = when {
            error != null -> copy.t("Proposal error", "Vorschlagsfehler", "Error de propuesta")
            preview != null -> copy.t("Proposal review", "Vorschlagsprüfung", "Revisión de propuesta")
            else -> copy.t("Proposal input", "Vorschlagseingabe", "Entrada de propuesta")
        }
    }, contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(copy.t("A proposal you can check", "Ein Vorschlag zum Prüfen", "Una propuesta que puedes revisar"),
                style = MaterialTheme.typography.headlineMedium)
            Text(copy.t("Ask ChatGPT or Claude to prepare your exams and assignments. Check the source, dates and preparation before saving.",
                "Bitte ChatGPT oder Claude, deine Prüfungen und Abgaben vorzubereiten. Prüfe Quelle, Daten und Vorbereitung vor dem Speichern.",
                "Pide a ChatGPT o Claude que prepare tus exámenes y entregas. Revisa la fuente, las fechas y la preparación antes de guardar."),
                modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeading(copy.t("1 · Share instructions", "1 · Anleitung teilen", "1 · Compartir instrucciones"))
            val selected = state.snapshot.semesters.firstOrNull { it.id == semesterID && !it.archived }
            Text(selected?.title ?: copy.t("No existing semester selected", "Kein bestehendes Semester ausgewählt", "Sin semestre existente seleccionado"),
                style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(copy.t("Include academic notes", "Akademische Notizen einschliessen", "Incluir notas académicas"),
                    modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(checked = includeNotes, onCheckedChange = { includeNotes = it }, modifier = Modifier.testTag("assistant.plan.includeNotes"))
            }
            Text(copy.t("Only this selected academic context and linked preparation are shared. Private notes are excluded unless you enable them. Unrelated tasks and habits are excluded.",
                "Nur dieser ausgewählte akademische Kontext und verknüpfte Vorbereitung werden geteilt. Private Notizen sind nur auf Wunsch enthalten. Andere Aufgaben und Gewohnheiten bleiben ausgeschlossen.",
                "Solo se comparte este contexto académico seleccionado y la preparación vinculada. Las notas privadas se excluyen salvo que las actives. Se excluyen las demás tareas y hábitos."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val sharedSnapshot = if (semesterID == null) state.snapshot.copy(semesters = emptyList(), courses = emptyList(), assessments = emptyList(), tasks = emptyList()) else state.snapshot
                val instructions = AssistantContextExporter.build(sharedSnapshot, semesterID, includeNotes)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, instructions)
                context.startActivity(Intent.createChooser(intent, copy.t("Choose your assistant", "Assistenten wählen", "Elige tu asistente")))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.share")) {
                Text(copy.t("Share assistant instructions", "Anleitung für den Assistenten teilen", "Compartir instrucciones para el asistente"))
            }
        }
        item {
            desktopSection { plan -> operation {
                text = AcademicPlanCodec.encode(plan)
                preview = repository.preview(plan)
                receipt = preview?.receipt
            } }
        }
        if (preview == null) {
            item {
                SectionHeading(copy.t("2 · Open a proposal", "2 · Vorschlag öffnen", "2 · Abrir una propuesta"))
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.open")) {
                    Text(copy.t("Open JSON file", "JSON-Datei öffnen", "Abrir archivo JSON"))
                }
                OutlinedTextField(value = text, onValueChange = { value ->
                    if (value.toByteArray(Charsets.UTF_8).size <= AcademicPlanValidator.MAX_BYTES) {
                        text = value
                        error = null
                    } else error = copy.t("Proposal exceeds the 2 MiB limit.", "Der Vorschlag überschreitet 2 MiB.", "La propuesta supera el límite de 2 MiB.")
                }, label = { Text(copy.t("Paste proposal JSON", "JSON-Vorschlag einfügen", "Pegar propuesta JSON")) },
                    minLines = 5, maxLines = 10, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistant.plan.input"))
                Button(onClick = { operation {
                    val plan = withContext(Dispatchers.Default) { AcademicPlanCodec.decode(text) }
                    preview = repository.preview(plan)
                    receipt = preview?.receipt
                } }, enabled = text.isNotBlank() && !busy && !state.recoveryRequired,
                    modifier = Modifier.fillMaxWidth().testTag("assistant.plan.review")) {
                    Text(copy.t("Review proposal", "Vorschau prüfen", "Revisar propuesta"))
                }
            }
        }
        preview?.let { reviewed ->
            val plan = reviewed.plan
            item {
                SectionHeading(copy.t("2 · Review import", "2 · Import prüfen", "2 · Revisar importación"))
                Text(plan.semester.title, style = MaterialTheme.typography.titleLarge)
                Text(copy.t("Provided source: ", "Angegebene Quelle: ", "Fuente indicada: ") + plan.source)
                Text(copy.t("Source time zone: ", "Zeitzone der Quelle: ", "Zona horaria de origen: ") + plan.semester.timeZoneIdentifier)
                Text(copy.t("Start: ", "Beginn: ", "Inicio: ") + plan.semester.startDate + " · " + plan.semester.weekCount + " " +
                    copy.t("teaching weeks", "Unterrichtswochen", "semanas lectivas"))
                Text(copy.t("Compare these details with your original timetable. The assistant supplied the source and dates.",
                    "Vergleiche diese Angaben mit deinem Originalplan. Quelle und Daten wurden vom Assistenten angegeben.",
                    "Compara estos datos con el calendario original. La fuente y las fechas las proporcionó el asistente."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SectionHeading(copy.t("Exams and deadlines", "Prüfungen und Fristen", "Exámenes y plazos")) }
            items(plan.assessments, key = { "assessment-" + it.id }) { item ->
                Column(Modifier.fillMaxWidth().testTag("assistant.plan.assessment.${item.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(plan.courses.firstOrNull { it.id == item.courseID }?.name.orEmpty())
                    Text("${item.kind} · ${item.dueDate} · ${item.dueTime ?: copy.unknownTime}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                }
            }
            if (plan.preparationTasks.isNotEmpty()) {
                item { SectionHeading(copy.t("Preparation tasks", "Vorbereitungsaufgaben", "Tareas de preparación")) }
                items(plan.preparationTasks, key = { "preparation-" + it.id }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.dueDate?.let { "$it · ${item.dueTime ?: copy.unknownTime}" } ?: copy.noDeadline)
                        Text("${item.estimatedWorkMinutes} " + copy.t("estimated minutes", "geschätzte Minuten", "minutos estimados"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text(copy.t("Preparation dates are task deadlines, not reserved Calendar time. Reminders stay off. Completing preparation does not complete an exam.",
                        "Vorbereitungsdaten sind Aufgabenfristen, keine reservierten Kalenderzeiten. Erinnerungen bleiben aus. Erledigte Vorbereitung erledigt keine Prüfung.",
                        "Las fechas de preparación son plazos de tareas, no tiempo reservado en el calendario. Los recordatorios quedan desactivados. Completar la preparación no completa el examen."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                val saved = receipt ?: reviewed.receipt
                if (saved != null) {
                    Card(Modifier.fillMaxWidth().testTag("assistant.plan.receipt").semantics { liveRegion = LiveRegionMode.Polite }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (saved.state == ReceiptState.UNDONE)
                                copy.t("Import undone", "Import rückgängig gemacht", "Importación deshecha") else copy.t("Saved on this phone", "Auf diesem Telefon gespeichert", "Guardado en este teléfono"),
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (saved.state == ReceiptState.UNDONE)
                                copy.t("This proposal will not be applied again.", "Dieser Vorschlag wird nicht erneut angewendet.", "Esta propuesta no se aplicará de nuevo.") else
                                copy.t("Reopening this proposal creates no duplicates. This receipt confirms a local save.", "Erneutes Öffnen erstellt keine Duplikate. Dieser Beleg bestätigt die lokale Speicherung.", "Abrir esta propuesta de nuevo no crea duplicados. Este recibo confirma el guardado local."))
                            Text(copy.t("Proposal: ", "Vorschlag: ", "Propuesta: ") + saved.proposalID,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (saved.state == ReceiptState.APPLIED) {
                        OutlinedButton(onClick = { operation { receipt = repository.undo(saved.proposalID) } },
                            enabled = !busy && !state.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.undo")) {
                            Text(copy.t("Undo import", "Import rückgängig machen", "Deshacer importación"))
                        }
                        Text(copy.t("Undo is available while imported items remain unchanged. It preserves unrelated data.",
                            "Rückgängig ist möglich, solange importierte Einträge unverändert sind. Andere Daten bleiben erhalten.",
                            "Puedes deshacer mientras los elementos importados no cambien. Se conservan los demás datos."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SectionHeading(copy.t("New items to save", "Neue Einträge zum Speichern", "Nuevos elementos a guardar"))
                    Text("${reviewed.counts.semesters} " + copy.semester + " · ${reviewed.counts.courses} " + copy.t("courses", "Kurse", "cursos"))
                    Text("${reviewed.counts.assessments} " + copy.t("assessments", "Prüfungen und Fristen", "evaluaciones") +
                        " · ${reviewed.counts.preparationTasks} " + copy.t("preparation tasks", "Vorbereitungsaufgaben", "tareas de preparación"))
                    Button(onClick = { operation { receipt = repository.apply(reviewed) } },
                        enabled = !busy && !state.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("assistant.plan.apply")) { Text(copy.save) }
                    TextButton(onClick = { operation { preview = repository.preview(plan); receipt = preview?.receipt } },
                        enabled = !busy, modifier = Modifier.testTag("assistant.plan.refresh")) {
                        Text(copy.t("Refresh preview", "Vorschau aktualisieren", "Actualizar vista previa"))
                    }
                }
                Text(copy.t("Existing items stay intact. A paired desktop may receive the save receipt; cloud sync is unchanged.",
                    "Bestehende Einträge bleiben erhalten. Ein gekoppelter Desktop kann den Speicherbeleg erhalten; die Cloud-Synchronisierung bleibt unverändert.",
                    "Se conservan los elementos existentes. Un ordenador vinculado puede recibir el recibo de guardado; la sincronización en la nube no cambia."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { preview = null; receipt = null; error = null }, enabled = !busy,
                    modifier = Modifier.testTag("assistant.plan.edit")) {
                    Text(copy.t("Open another proposal", "Anderen Vorschlag öffnen", "Abrir otra propuesta"))
                }
            }
        }
        if (busy) item { CircularProgressIndicator(Modifier.testTag("assistant.plan.busy")) }
        error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("assistant.plan.error").semantics { liveRegion = LiveRegionMode.Polite })
            Text(copy.t("No successful save is claimed. Check the proposal or refresh a changed preview and try again.",
                "Es wird keine erfolgreiche Speicherung behauptet. Prüfe den Vorschlag oder aktualisiere die geänderte Vorschau.",
                "No se confirma un guardado correcto. Revisa la propuesta o actualiza una vista previa cambiada e inténtalo de nuevo."),
                style = MaterialTheme.typography.bodySmall)
        } }
    }
}
