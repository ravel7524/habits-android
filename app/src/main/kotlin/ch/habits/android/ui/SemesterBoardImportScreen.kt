package ch.habits.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.data.BoardImportChange
import ch.habits.android.data.BoardImportPreview
import ch.habits.android.data.BoardImportReceipt
import ch.habits.android.data.BoardImportRequest
import ch.habits.android.data.ReceiptState
import ch.habits.android.data.SourceClockResolution
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.desktop.DesktopConnection
import ch.habits.android.semester.BoardCourse
import ch.habits.android.semester.BoardProposal
import ch.habits.android.semester.BoardSemester
import ch.habits.android.semester.SemesterBoardCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Import only the user-selected input; review and receipt delivery never write board records. */
@Composable
internal fun SemesterBoardImportScreen(
    repository: WorkspaceRepository,
    copy: UiCopy,
    onBack: () -> Unit,
    desktop: DesktopConnection? = null,
    providedProposal: BoardProposal? = null,
    onImported: (String) -> Unit = {},
    desktopSection: @Composable (onBoard: (BoardProposal) -> Unit) -> Unit = {},
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val workspace by repository.state.collectAsStateWithLifecycle()
    val connection = desktop?.state?.collectAsStateWithLifecycle()?.value
    var input by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<BoardInput?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var zone by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BoardImportPreview?>(null) }
    var receipt by remember { mutableStateOf<BoardImportReceipt?>(null) }
    var busy by remember { mutableStateOf<BoardImportAction?>(null) }
    var failure by remember { mutableStateOf<BoardImportFailure?>(null) }
    var diagnosticVisible by remember { mutableStateOf(false) }
    var receiptDetails by remember { mutableStateOf(false) }
    var desktopExpanded by remember { mutableStateOf(false) }

    fun install(value: BoardInput) {
        draft = value
        selected = value.document.courses.map { it.id }.toSet()
        zone = value.document.timeZoneIdentifier.orEmpty()
        preview = null
        receipt = null
        failure = null
        receiptDetails = false
        diagnosticVisible = false
        focus.clearFocus()
    }
    fun operation(action: BoardImportAction, block: suspend () -> Unit) {
        if (busy != null) return
        // Claim the action before dispatch. A second click must not enqueue another
        // reader or writer while the first coroutine has not started yet.
        busy = action
        failure = null
        diagnosticVisible = false
        // rememberCoroutineScope may inherit an Unconfined test effect context.
        // Install, focus changes and callbacks must resume on Android's UI thread.
        scope.launch(Dispatchers.Main.immediate) {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                // A storage error may occur after the atomic write. Recover the local
                // receipt if it is observable; do not describe that outcome as "unsaved".
                if (action == BoardImportAction.SAVE || action == BoardImportAction.UNDO) {
                    preview?.request?.proposal?.proposalID?.let { id ->
                        repository.state.value.snapshot.boardReceipts.firstOrNull { it.proposalID.equals(id, true) }?.let { receipt = it }
                    }
                }
                failure = BoardImportFailure(action, error.message)
            } finally { busy = null }
        }
    }
    fun review(value: BoardInput) {
        val reviewedSelection = selected.toSet()
        val reviewedZone = zone.trim()
        operation(BoardImportAction.REVIEW) {
            val request = if (value.proposal != null) BoardImportRequest(value.proposal)
            else BoardImportRequest.rawFile(value.document, reviewedSelection, reviewedZone)
            preview = repository.previewBoard(request)
            receipt = preview?.receipt
            focus.clearFocus()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation(BoardImportAction.READ) {
            val source = withContext(Dispatchers.IO) { readBoardInput(context, uri, copy) }
            val decoded = withContext(Dispatchers.Default) { decodeBoardInput(source.first, source.second) }
            input = source.first
            install(decoded)
        }
    }
    LaunchedEffect(providedProposal) {
        providedProposal?.let { value ->
            // Validate the same closed handoff as file input before presenting it.
            operation(BoardImportAction.READ) {
                val encoded = withContext(Dispatchers.Default) { SemesterBoardCodec.encodeProposal(value) }
                install(withContext(Dispatchers.Default) { decodeBoardInput(encoded, copy.t("Assistant proposal", "Vorschlag des Assistenten", "Propuesta del asistente")) })
            }
        }
    }
    BackHandler { if (busy == null) onBack() }

    // Freeze all conditional rows before the deferred LazyColumn DSL executes.
    val renderedInput = input
    val loaded = draft
    val reviewed = preview
    val lastReceipt = receipt ?: reviewed?.receipt
    val localReceipt = lastReceipt?.let { saved -> workspace.snapshot.boardReceipts.firstOrNull { it.proposalID == saved.proposalID } }
    val receiptMissing = lastReceipt != null && localReceipt == null
    val renderedFailure = failure
    val renderedBusy = busy
    val stale = reviewed != null && localReceipt == null && workspace.snapshot.revision != reviewed.baselineRevision
    val validZone = runCatching { ZoneId.of(zone.trim()) }.isSuccess
    val sourceCourses = loaded?.document?.courses.orEmpty().filter { loaded?.proposal != null || it.id in selected }
    val oldStatuses = workspace.snapshot.semesters.firstOrNull { it.id.equals(loaded?.document?.id, true) }?.statuses.orEmpty()
        .associate { it.id.lowercase() to it.name }
    val newStatuses = oldStatuses + loaded?.document?.statuses.orEmpty().associate { it.id.lowercase() to it.name }
    val incomingUnresolved = sourceCourses.flatMap { it.assessments }.filter { it.state == "pending" && it.dueTime != null }
        .count { SourceClockResolution.instant(it.dueDate, checkNotNull(it.dueTime), zone.trim()) == null }
    val isRaw = loaded?.proposal == null
    val renderedDesktopExpanded = desktopExpanded

    LaunchedEffect(loaded, reviewed, localReceipt?.state) { listState.scrollToItem(0) }

    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("semester.import.screen").semantics {
        stateDescription = when {
            renderedFailure != null -> copy.t("Import error", "Importfehler", "Error de importación")
            localReceipt != null -> copy.t("Import receipt", "Importbeleg", "Recibo de importación")
            reviewed != null -> copy.t("Review changes", "Änderungen prüfen", "Revisar cambios")
            loaded != null -> copy.t("Choose courses", "Kurse auswählen", "Elegir cursos")
            else -> copy.t("Open Semester Board", "Semester Board öffnen", "Abrir Semester Board")
        }
        if (renderedFailure != null) liveRegion = LiveRegionMode.Polite
    }, state = listState, contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (localReceipt == null) item(key = "back") {
            TextButton(onClick = onBack, enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.back")) {
                Text(copy.t("Back to Semester Board", "Zurück zum Semester Board", "Volver a Semester Board"))
            }
        }
        if (localReceipt == null) item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(copy.t("Import Semester Board", "Semester Board importieren", "Importar Semester Board"), style = MaterialTheme.typography.headlineSmall)
                Text(copy.t("Choose your file or proposal, review its changes, then confirm on this phone.",
                    "Wähle deine Datei oder einen Vorschlag, prüfe die Änderungen und bestätige auf diesem Telefon.",
                    "Elige tu archivo o propuesta, revisa los cambios y confirma en este teléfono."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (desktop != null && localReceipt == null) item(key = "desktop") {
            BoardImportCard(copy.t("Assistant on your desktop", "Assistent auf deinem Desktop", "Asistente en tu ordenador")) {
                TextButton(onClick = { desktopExpanded = !desktopExpanded }, enabled = renderedBusy == null,
                    modifier = Modifier.testTag("semester.import.desktop.toggle")) {
                    Text(if (renderedDesktopExpanded) copy.t("Hide connection", "Verbindung ausblenden", "Ocultar conexión") else
                        copy.t("Open desktop connection", "Desktopverbindung öffnen", "Abrir conexión al ordenador"))
                }
                if (renderedDesktopExpanded) desktopSection { value ->
                    operation(BoardImportAction.READ) {
                        val encoded = withContext(Dispatchers.Default) { SemesterBoardCodec.encodeProposal(value) }
                        install(withContext(Dispatchers.Default) { decodeBoardInput(encoded, copy.t("Assistant proposal", "Vorschlag des Assistenten", "Propuesta del asistente")) })
                        desktopExpanded = false
                    }
                }
            }
        }
        if (workspace.recoveryRequired) item(key = "recovery") {
            BoardImportNotice(copy.t("Your workspace needs recovery", "Deine Daten müssen wiederhergestellt werden", "Tus datos necesitan recuperación"),
                copy.t("Import and Undo are paused. Return to Settings to review recovery; your existing files are preserved.",
                    "Import und Rückgängig sind gesperrt. Prüfe die Wiederherstellung in den Einstellungen; bestehende Dateien bleiben erhalten.",
                    "Importar y deshacer están pausados. Revisa la recuperación en Ajustes; se conservan los archivos existentes."), true)
        }
        if (loaded == null) {
            item(key = "input") {
                BoardImportCard(copy.t("1 · Open your board", "1 · Board öffnen", "1 · Abrir tu tablero")) {
                    Text(copy.t("Open a Semester Board file from your iPhone or a proposal from your assistant.",
                        "Öffne eine Semester-Board-Datei vom iPhone oder einen Vorschlag deines Assistenten.",
                        "Abre un archivo de Semester Board del iPhone o una propuesta de tu asistente."), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = renderedBusy == null, modifier = Modifier.fillMaxWidth().testTag("semester.import.open")) {
                        Text(copy.t("Choose file", "Datei auswählen", "Elegir archivo"))
                    }
                    OutlinedTextField(value = renderedInput, onValueChange = { value ->
                        if (value.toByteArray(Charsets.UTF_8).size <= SemesterBoardCodec.MAX_DATA_BYTES) { input = value; failure = null }
                        else failure = BoardImportFailure(BoardImportAction.READ, copy.t("The file exceeds 2 MiB.", "Die Datei überschreitet 2 MiB.", "El archivo supera 2 MiB."))
                    }, label = { Text(copy.t("Or paste file contents", "Oder Dateiinhalt einfügen", "O pega el contenido del archivo")) },
                        minLines = 3, maxLines = 7, enabled = renderedBusy == null, modifier = Modifier.fillMaxWidth().testTag("semester.import.input"))
                    TextButton(onClick = { operation(BoardImportAction.READ) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?: error(copy.t("The clipboard is empty.", "Die Zwischenablage ist leer.", "El portapapeles está vacío."))
                        require(pasted.toByteArray(Charsets.UTF_8).size <= SemesterBoardCodec.MAX_DATA_BYTES) { "The file exceeds 2 MiB." }
                        input = pasted
                    } }, enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.paste")) {
                        Text(copy.t("Paste from clipboard", "Aus Zwischenablage einfügen", "Pegar del portapapeles"))
                    }
                    Button(onClick = {
                        // Read Compose state on the UI thread. The worker receives the
                        // exact text selected by this click, never a live state delegate.
                        val selectedInput = input
                        operation(BoardImportAction.READ) {
                            val decoded = withContext(Dispatchers.Default) {
                                decodeBoardInput(selectedInput, copy.t("Pasted board", "Eingefügtes Board", "Tablero pegado"))
                            }
                            install(decoded)
                        }
                    }, enabled = renderedBusy == null && renderedInput.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("semester.import.continue")) {
                        Text(copy.t("Continue to review", "Weiter zur Prüfung", "Continuar a la revisión"))
                    }
                    Text(copy.t("Opening and reviewing do not save anything.", "Öffnen und Prüfen speichern noch nichts.", "Abrir y revisar no guarda nada."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            if (localReceipt == null) item(key = "source") {
                BoardImportCard(copy.t("1 · Source and dates", "1 · Quelle und Daten", "1 · Origen y fechas")) {
                    Text(loaded.document.title, style = MaterialTheme.typography.titleLarge)
                    Text(loaded.proposal?.source ?: loaded.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(if (loaded.document.startDate == null) copy.t("No source date range", "Kein Datumsbereich angegeben", "Sin periodo de fechas de origen") else
                        copy.displayDate(loaded.document.startDate) + " – " + copy.displayDate(loaded.document.endDate))
                    Text("${loaded.document.weekCount} " + copy.t("teaching weeks", "Unterrichtswochen", "semanas lectivas"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isRaw && reviewed == null) {
                        OutlinedTextField(value = zone, onValueChange = { zone = it; failure = null }, enabled = renderedBusy == null,
                            label = { Text(copy.t("Source time zone", "Zeitzone der Quelle", "Zona horaria de origen")) },
                            placeholder = { Text("Europe/Zurich") }, singleLine = true, isError = zone.isNotBlank() && !validZone,
                            supportingText = { Text(copy.t("Use the time zone of the original timetable.", "Verwende die Zeitzone des Originalplans.", "Usa la zona horaria del calendario original.")) },
                            modifier = Modifier.fillMaxWidth().testTag("semester.import.zone"))
                        if (loaded.document.timeZoneIdentifier == null) TextButton(onClick = { zone = ZoneId.systemDefault().id },
                            enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.zone.device")) {
                            Text(copy.t("Use this phone’s time zone", "Zeitzone dieses Telefons verwenden", "Usar la zona horaria de este teléfono"))
                        }
                    } else Text(copy.t("Source time zone: ", "Zeitzone der Quelle: ", "Zona horaria de origen: ") + zone,
                        modifier = Modifier.testTag("semester.import.zone.review"), style = MaterialTheme.typography.bodyMedium)
                    Text(copy.t("Check these dates and the source against your original timetable.", "Vergleiche Daten und Quelle mit deinem Originalplan.",
                        "Compara las fechas y el origen con tu calendario original."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (reviewed == null) {
                item(key = "selection") {
                    BoardImportCard(copy.t("2 · Included courses", "2 · Enthaltene Kurse", "2 · Cursos incluidos")) {
                        Text(copy.quantity(sourceCourses.size, "selected course", "selected courses", "gewählter Kurs", "gewählte Kurse", "curso elegido", "cursos elegidos"),
                            modifier = Modifier.testTag("semester.import.selection.count"))
                        if (isRaw) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { selected = loaded.document.courses.map { it.id }.toSet() }, enabled = renderedBusy == null,
                                    modifier = Modifier.testTag("semester.import.select.all")) { Text(copy.t("Select all", "Alle wählen", "Elegir todos")) }
                                TextButton(onClick = { selected = emptySet() }, enabled = renderedBusy == null,
                                    modifier = Modifier.testTag("semester.import.select.none")) { Text(copy.t("Clear selection", "Auswahl aufheben", "Quitar selección")) }
                            }
                            Text(copy.t("Unselected courses keep their saved records. Semester dates and status definitions are reviewed together.",
                                "Nicht gewählte Kurse behalten ihre gespeicherten Daten. Semesterdaten und Statusdefinitionen werden gemeinsam geprüft.",
                                "Los cursos no elegidos conservan sus registros. Se revisan también las fechas y los estados del semestre."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Text(copy.t("This proposal is reviewed as a whole. Request a new proposal if the included courses are wrong.",
                            "Dieser Vorschlag wird vollständig geprüft. Fordere einen neuen Vorschlag an, wenn die Kurse nicht stimmen.",
                            "Esta propuesta se revisa completa. Pide otra propuesta si los cursos incluidos no son correctos."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(loaded.document.courses, key = { index, row -> "course.$index.${row.id}" }) { _, course ->
                    BoardImportCourse(course, loaded.document, copy, course.id in selected, isRaw, renderedBusy == null,
                        onSelected = { checked -> selected = if (checked) selected + course.id else selected - course.id })
                }
                item(key = "review.action") {
                    Button(onClick = { review(loaded) }, enabled = renderedBusy == null && !workspace.recoveryRequired && validZone &&
                        (selected.isNotEmpty() || loaded.document.courses.isEmpty()), modifier = Modifier.fillMaxWidth().testTag("semester.import.review")) {
                        Text(copy.t("Review changes", "Änderungen prüfen", "Revisar cambios"))
                    }
                }
            } else if (localReceipt == null && !receiptMissing) {
                item(key = "summary") {
                    BoardImportCard(copy.t("2 · Changes to review", "2 · Änderungen prüfen", "2 · Cambios para revisar")) {
                        Text(copy.t("New", "Neu", "Nuevos") + ": " + boardCountLine(reviewed.counts.createdCourses, reviewed.counts.createdAssessments, copy))
                        Text(copy.t("Updated", "Aktualisiert", "Actualizados") + ": " + boardCountLine(reviewed.counts.updatedCourses, reviewed.counts.updatedAssessments, copy))
                        if (reviewed.counts.createdSemesters > 0) Text(copy.t("A new semester will be created.", "Ein neues Semester wird erstellt.", "Se creará un semestre nuevo."))
                        if (reviewed.counts.updatedSemesters > 0) Text(copy.t("The existing semester will be updated.", "Das bestehende Semester wird aktualisiert.", "Se actualizará el semestre existente."))
                        Text(boardSourceCountLine(sourceCourses, copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(copy.t("These are recorded source facts. Missing status or notes do not imply completed work.",
                            "Dies sind Angaben aus der Quelle. Fehlender Status oder fehlende Notizen bedeuten keine erledigte Arbeit.",
                            "Son datos registrados en el origen. La falta de estado o notas no significa trabajo completado."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (incomingUnresolved > 0) item(key = "clock.warning") {
                    BoardImportNotice(copy.t("Check $incomingUnresolved recorded time(s)", "$incomingUnresolved angegebene Uhrzeit(en) prüfen", "Revisa $incomingUnresolved hora(s) registrada(s)"),
                        copy.t("These times are ambiguous or unavailable in the source time zone. They stay recorded and are omitted from time-based lists until corrected.",
                            "Diese Zeiten sind in der Quellzeitzone mehrdeutig oder nicht verfügbar. Sie bleiben gespeichert und werden bis zur Korrektur aus zeitabhängigen Listen ausgelassen.",
                            "Estas horas son ambiguas o no existen en la zona de origen. Se conservan y se omiten de las listas por hora hasta corregirlas."))
                }
                itemsIndexed(reviewed.changes, key = { index, _ -> "change.$index" }) { index, change ->
                    BoardImportChangeCard(change, index, copy, oldStatuses, newStatuses)
                }
                if (reviewed.changes.isEmpty()) item(key = "unchanged") {
                    BoardImportNotice(copy.t("No record changes", "Keine Änderungen an Einträgen", "Sin cambios de registros"),
                        copy.t("The selected records already match. Confirmation will record this import’s receipt.",
                            "Die gewählten Einträge stimmen bereits überein. Die Bestätigung speichert den Importbeleg.",
                            "Los registros elegidos ya coinciden. Confirmar guardará el recibo de esta importación."))
                }
                item(key = "disclosures") {
                    BoardImportCard(copy.t("Before you confirm", "Vor dem Bestätigen", "Antes de confirmar")) {
                        reviewed.notes.forEach { note -> Text(boardImportNote(note, copy), style = MaterialTheme.typography.bodySmall) }
                        Text(copy.t("Notes are imported for reading.", "Notizen werden zum Lesen importiert.", "Las notas se importan para leer."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (stale) item(key = "stale") {
                    BoardImportNotice(copy.t("Your records changed", "Deine Einträge haben sich geändert", "Tus registros han cambiado"),
                        copy.t("Refresh the review and check the changes again before saving.", "Aktualisiere die Vorschau und prüfe vor dem Speichern erneut.",
                            "Actualiza la revisión y vuelve a comprobar los cambios antes de guardar."), true)
                }
                item(key = "confirm") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { operation(BoardImportAction.SAVE) {
                            val saved = repository.applyBoard(reviewed)
                            receipt = saved
                            onImported(saved.semesterID)
                        } }, enabled = renderedBusy == null && !workspace.recoveryRequired && !stale && !reviewed.alreadyHandled,
                            modifier = Modifier.fillMaxWidth().testTag("semester.import.confirm")) { Text(copy.save) }
                        OutlinedButton(onClick = { operation(BoardImportAction.REVIEW) { preview = repository.previewBoard(reviewed.request); receipt = preview?.receipt } },
                            enabled = renderedBusy == null && !workspace.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("semester.import.refresh")) {
                            Text(copy.t("Refresh review", "Vorschau aktualisieren", "Actualizar revisión"))
                        }
                        TextButton(onClick = { preview = null; failure = null }, enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.selection")) {
                            Text(copy.t("Back to course selection", "Zurück zur Kursauswahl", "Volver a elegir cursos"))
                        }
                        TextButton(onClick = onBack, enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.cancel")) { Text(copy.cancel) }
                    }
                }
            }
        }
        if (receiptMissing) item(key = "receipt.missing") {
            BoardImportCard(copy.t("Receipt is not in this workspace", "Beleg ist nicht in diesen Daten vorhanden", "El recibo no está en estos datos"),
                Modifier.testTag("semester.import.receipt.missing"), error = true) {
                Text(copy.t("Your workspace may have changed or been restored. Refresh the review before relying on the previous receipt or saving again.",
                    "Deine Daten wurden möglicherweise geändert oder wiederhergestellt. Aktualisiere die Vorschau, bevor du dich auf den alten Beleg verlässt oder erneut speicherst.",
                    "Los datos pueden haber cambiado o haberse restaurado. Actualiza la revisión antes de usar el recibo anterior o guardar otra vez."),
                    color = MaterialTheme.colorScheme.onErrorContainer)
                if (reviewed != null) OutlinedButton(onClick = { operation(BoardImportAction.REVIEW) {
                    preview = repository.previewBoard(reviewed.request); receipt = preview?.receipt
                } }, enabled = renderedBusy == null && !workspace.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("semester.import.receipt.refresh")) {
                    Text(copy.t("Refresh review and receipt", "Vorschau und Beleg aktualisieren", "Actualizar revisión y recibo"))
                }
            }
        }
        localReceipt?.let { saved ->
            item(key = "receipt") {
                BoardImportCard(if (saved.state == ReceiptState.UNDONE) copy.t("Import undone", "Import rückgängig gemacht", "Importación deshecha")
                    else if (reviewed?.alreadyHandled == true) copy.t("Already saved on this phone", "Bereits auf diesem Telefon gespeichert", "Ya guardado en este teléfono")
                    else copy.t("Saved on this phone", "Auf diesem Telefon gespeichert", "Guardado en este teléfono"),
                    Modifier.testTag("semester.import.receipt").semantics { liveRegion = LiveRegionMode.Polite }) {
                    loaded?.document?.title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                    Text(if (saved.state == ReceiptState.UNDONE) copy.t("Reopening this import will not apply it again.",
                        "Erneutes Öffnen wendet diesen Import nicht wieder an.", "Abrir esta importación de nuevo no la aplicará otra vez.") else
                        copy.t("Saved locally. Reopening adds no duplicates and keeps later changes.",
                            "Lokal gespeichert. Erneutes Öffnen erstellt keine Duplikate und erhält spätere Änderungen.",
                            "Guardado localmente. Abrirlo de nuevo no crea duplicados y conserva cambios posteriores."))
                    Text(boardReceiptDate(saved.undoneAt ?: saved.appliedAt, copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onBack, enabled = renderedBusy == null, modifier = Modifier.fillMaxWidth().testTag("semester.import.done")) {
                        Text(copy.t("Return to board", "Zurück zum Board", "Volver al tablero"))
                    }
                    if (saved.state == ReceiptState.APPLIED) {
                        OutlinedButton(onClick = { operation(BoardImportAction.UNDO) { receipt = repository.undoBoard(saved.proposalID) } },
                            enabled = renderedBusy == null && !workspace.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("semester.import.undo")) {
                            Text(copy.t("Undo this import", "Diesen Import rückgängig machen", "Deshacer esta importación"))
                        }
                        Text(copy.t("Undo is available while imported records and linked preparation remain unchanged.",
                            "Rückgängig ist bei unveränderten importierten Einträgen und verknüpfter Vorbereitung möglich.",
                            "Puedes deshacer si no cambiaron los registros importados ni la preparación vinculada."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!saved.rawFile && desktop?.rememberedProposal(saved) != null) item(key = "acknowledgement") {
                val receiptState = if (saved.state == ReceiptState.UNDONE) "undone" else "applied"
                val pending = desktop.needsAcknowledgement(saved, receiptState)
                val reportedState = connection?.deliveries?.firstOrNull { it.proposalID.equals(saved.proposalID, true) }?.reportedState
                BoardImportCard(copy.t("Desktop receipt", "Desktopbeleg", "Recibo del ordenador"), Modifier.testTag("semester.import.desktop.receipt")) {
                    Text(if (pending) copy.t("The local result is saved. Send its receipt to your paired desktop when you are ready.",
                        "Das lokale Ergebnis ist gespeichert. Sende den Beleg an deinen gekoppelten Desktop, wenn du bereit bist.",
                        "El resultado local está guardado. Envía el recibo al ordenador vinculado cuando quieras.") else if (reportedState != receiptState) copy.t(
                            "The desktop has a different final receipt. Check it on the desktop; the local result remains as shown here.",
                            "Der Desktop hat einen anderen endgültigen Beleg. Prüfe ihn dort; das lokale Ergebnis bleibt wie hier angezeigt.",
                            "El ordenador tiene un recibo final distinto. Revísalo allí; el resultado local sigue como se muestra aquí.") else
                        copy.t("Your desktop has confirmed this receipt.", "Dein Desktop hat diesen Beleg bestätigt.", "Tu ordenador ha confirmado este recibo."))
                    if (pending) OutlinedButton(onClick = { operation(BoardImportAction.ACKNOWLEDGE) { desktop.acknowledge(saved, receiptState) } },
                        enabled = renderedBusy == null && connection?.busy != true && !workspace.recoveryRequired,
                        modifier = Modifier.fillMaxWidth().testTag("semester.import.desktop.retry")) {
                        Text(copy.t("Send receipt / retry", "Beleg senden / erneut versuchen", "Enviar recibo / reintentar"))
                    }
                    Text(copy.t("A connection failure does not undo the local result. This sends a receipt, not your board contents.",
                        "Ein Verbindungsfehler macht das lokale Ergebnis nicht rückgängig. Dies sendet einen Beleg, keine Board-Inhalte.",
                        "Un fallo de conexión no deshace el resultado local. Se envía un recibo, no el contenido del tablero."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "receipt.details") {
                BoardImportCard(copy.t("Import details", "Importdetails", "Detalles de la importación")) {
                    TextButton(onClick = { receiptDetails = !receiptDetails }, modifier = Modifier.testTag("semester.import.receipt.details")) {
                        Text(if (receiptDetails) copy.t("Hide details", "Details ausblenden", "Ocultar detalles") else copy.t("Show source and receipt", "Quelle und Beleg anzeigen", "Mostrar origen y recibo"))
                    }
                    if (receiptDetails) {
                        Text(copy.t("Source: ", "Quelle: ", "Origen: ") + (loaded?.proposal?.source ?: loaded?.displayName ?: saved.source), style = MaterialTheme.typography.bodyMedium)
                        loaded?.document?.let { document ->
                            Text(if (document.startDate == null) copy.t("No source date range", "Kein Datumsbereich angegeben", "Sin periodo de fechas de origen") else
                                copy.displayDate(document.startDate) + " – " + copy.displayDate(document.endDate))
                            Text(copy.quantity(document.weekCount, "teaching week", "teaching weeks", "Unterrichtswoche", "Unterrichtswochen", "semana lectiva", "semanas lectivas"))
                            Text(copy.t("Source time zone: ", "Zeitzone der Quelle: ", "Zona horaria de origen: ") + zone,
                                modifier = Modifier.testTag("semester.import.zone.review"), style = MaterialTheme.typography.bodySmall)
                            Text(boardSourceCountLine(sourceCourses, copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(copy.t("Import: ", "Import: ", "Importación: ") + saved.proposalID, style = MaterialTheme.typography.bodySmall)
                        Text(copy.t("Imports are reviewed and confirmed on this phone.", "Importe werden auf diesem Telefon geprüft und bestätigt.",
                            "Las importaciones se revisan y confirman en este teléfono."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(boardNotificationDisclosure(copy),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (saved.state == ReceiptState.APPLIED) Text(copy.t(
                            "Undo restores the previous values only if imported records are unchanged. Later notes, progress or linked preparation can prevent it.",
                            "Rückgängig stellt vorherige Werte nur bei unveränderten importierten Einträgen wieder her. Spätere Notizen, Fortschritte oder Vorbereitung können dies verhindern.",
                            "Deshacer restaura valores anteriores solo si no cambiaron los registros importados. Notas, progreso o preparación posteriores pueden impedirlo."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "receipt.actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = null; preview = null; receipt = null; input = ""; failure = null },
                        enabled = renderedBusy == null, modifier = Modifier.testTag("semester.import.another")) {
                        Text(copy.t("Open another file", "Andere Datei öffnen", "Abrir otro archivo"))
                    }
                }
            }
        }
        if (renderedBusy != null) item(key = "busy") {
            Row(Modifier.fillMaxWidth().testTag("semester.import.busy").semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(if (renderedBusy == BoardImportAction.ACKNOWLEDGE) copy.t("Sending receipt…", "Beleg wird gesendet…", "Enviando recibo…") else
                    copy.t("Working…", "Wird verarbeitet…", "Procesando…"))
            }
        }
        renderedFailure?.let { error -> item(key = "error") {
            BoardImportCard(boardFailureTitle(error.action, copy), Modifier.testTag("semester.import.error").semantics { liveRegion = LiveRegionMode.Polite }, error = true) {
                Text(boardFailureBody(error.action, copy), color = MaterialTheme.colorScheme.onErrorContainer)
                if (error.action != BoardImportAction.READ && error.action != BoardImportAction.ACKNOWLEDGE && reviewed != null) {
                    OutlinedButton(onClick = { operation(BoardImportAction.REVIEW) { preview = repository.previewBoard(reviewed.request); receipt = preview?.receipt } },
                        enabled = renderedBusy == null && !workspace.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("semester.import.error.refresh")) {
                        Text(copy.t("Refresh review and receipt", "Vorschau und Beleg aktualisieren", "Actualizar revisión y recibo"))
                    }
                }
                if (!error.diagnostic.isNullOrBlank()) {
                    TextButton(onClick = { diagnosticVisible = !diagnosticVisible }, modifier = Modifier.testTag("semester.import.error.details")) {
                        Text(if (diagnosticVisible) copy.t("Hide technical details", "Technische Details ausblenden", "Ocultar detalles técnicos") else
                            copy.t("Technical details", "Technische Details", "Detalles técnicos"))
                    }
                    if (diagnosticVisible) Text(error.diagnostic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag("semester.import.error.diagnostic"))
                }
            }
        } }
    }
}

private data class BoardInput(val document: BoardSemester, val displayName: String, val proposal: BoardProposal? = null)
private enum class BoardImportAction { READ, REVIEW, SAVE, UNDO, ACKNOWLEDGE }
private data class BoardImportFailure(val action: BoardImportAction, val diagnostic: String?)

private fun decodeBoardInput(text: String, displayName: String): BoardInput {
    val value = AcademicPlanCodec.parseStrictJson(text, SemesterBoardCodec.MAX_DATA_BYTES).jsonObject
    return if ("format" in value) {
        val proposal = SemesterBoardCodec.decodeProposal(text)
        BoardInput(proposal.semester, displayName, proposal)
    } else BoardInput(SemesterBoardCodec.decode(text), displayName)
}

private fun readBoardInput(context: Context, uri: Uri, copy: UiCopy): Pair<String, String> {
    val resolver = context.contentResolver
    val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString) else null
    } }.getOrNull()?.filterNot { it.isISOControl() }?.take(160)?.takeIf { it.isNotBlank() }
        ?: copy.t("Selected file", "Gewählte Datei", "Archivo elegido")
    val text = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, SemesterBoardCodec.MAX_DATA_BYTES - output.size() + 1))
            if (count < 0) break
            require(output.size() + count <= SemesterBoardCodec.MAX_DATA_BYTES) { "The file exceeds 2 MiB." }
            output.write(buffer, 0, count)
        }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    } ?: error("The selected file could not be opened.")
    return text to name
}

@Composable
private fun BoardImportCard(title: String, modifier: Modifier = Modifier, error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor =
        if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun BoardImportNotice(title: String, body: String, error: Boolean = false) {
    BoardImportCard(title, error = error) { Text(body, style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun BoardImportCourse(course: BoardCourse, document: BoardSemester, copy: UiCopy, selected: Boolean, selectable: Boolean,
    enabled: Boolean, onSelected: (Boolean) -> Unit) {
    var expanded by remember(course.id) { mutableStateOf(false) }
    BoardImportCard(course.name, Modifier.testTag("semester.import.course.${course.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                course.moduleCode?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Text(boardSourceCountLine(listOf(course), copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selectable) Checkbox(checked = selected, onCheckedChange = onSelected, enabled = enabled,
                modifier = Modifier.testTag("semester.import.course.${course.id}.selected").semantics {
                    contentDescription = copy.t("Include ", "Einschliessen: ", "Incluir ") + course.name
                })
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("semester.import.course.${course.id}.details")) {
            Text(if (expanded) copy.t("Hide source details", "Quellangaben ausblenden", "Ocultar datos del origen") else
                copy.t("Review source details", "Quellangaben prüfen", "Revisar datos del origen"))
        }
        if (expanded) {
            if ("activeWeeks" in course.providedFields) Text(copy.t("Active weeks: ", "Aktive Wochen: ", "Semanas activas: ") +
                (course.activeWeeks.joinToString(", ").ifEmpty { copy.t("None recorded", "Keine angegeben", "Ninguna registrada") }), style = MaterialTheme.typography.bodySmall)
            course.scheduleNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            course.entries.toSortedMap().forEach { (week, entry) ->
                HorizontalDivider()
                Text(copy.t("Week $week", "Woche $week", "Semana $week"), style = MaterialTheme.typography.labelLarge)
                if ("statusID" in entry.providedFields) Text(entry.statusID?.let { id -> document.statuses.firstOrNull { it.id.equals(id, true) }?.name }
                    ?: copy.t("No recorded status", "Kein Status angegeben", "Sin estado registrado"), style = MaterialTheme.typography.bodySmall)
                if ("notes" in entry.providedFields && entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
            }
            course.assessments.forEach { assessment ->
                HorizontalDivider()
                Text(assessment.title, style = MaterialTheme.typography.labelLarge)
                Text(copy.displayDate(assessment.dueDate) + " · " + (assessment.dueTime?.let(copy::displayTime)
                    ?: copy.t("Date only", "Nur Datum", "Solo fecha")), style = MaterialTheme.typography.bodySmall)
                if ("state" in assessment.providedFields) Text(boardAssessmentState(assessment.state, copy), style = MaterialTheme.typography.bodySmall)
                if ("notes" in assessment.providedFields && assessment.notes.isNotBlank()) Text(assessment.notes, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BoardImportChangeCard(change: BoardImportChange, index: Int, copy: UiCopy, oldStatuses: Map<String, String>, newStatuses: Map<String, String>) {
    val isNew = change.action == "create"
    var details by remember(change) { mutableStateOf(false) }
    BoardImportCard(change.title, Modifier.testTag("semester.import.change.$index")) {
        Text((if (isNew) copy.t("New ", "Neu: ", "Nuevo: ") else copy.t("Update · ", "Änderung · ", "Cambio · ")) +
            when (change.recordType) { "semester" -> copy.semester; "course" -> copy.t("Course", "Kurs", "Curso"); else -> copy.t("Assessment", "Prüfung oder Frist", "Evaluación") },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (isNew) {
            boardCreateSummary(change, copy, newStatuses).forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("semester.import.change.$index.details")) {
                Text(if (details) copy.t("Hide added values", "Hinzugefügte Werte ausblenden", "Ocultar valores añadidos") else
                    copy.t("Details · all added values", "Details · alle hinzugefügten Werte", "Detalles · todos los valores añadidos"))
            }
        }
        if (!isNew || details) change.fields.forEachIndexed { fieldIndex, field ->
            if (fieldIndex > 0 || isNew) HorizontalDivider()
            Column(Modifier.testTag("semester.import.change.$index.field.${field.field}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(boardFieldLabel(field.field, copy), style = MaterialTheme.typography.labelLarge)
                if (!isNew) Text(copy.t("Before: ", "Vorher: ", "Antes: ") + boardFieldValue(field.field, field.before, copy, oldStatuses, true),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (isNew) "" else copy.t("After: ", "Nachher: ", "Después: ")) + boardFieldValue(field.field, field.after, copy, newStatuses, false),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** New records have no previous values. Summarize the supplied facts; every
 * persisted field remains reviewable in Details. Updates always show every diff. */
private fun boardCreateSummary(change: BoardImportChange, copy: UiCopy, statuses: Map<String, String>): List<Pair<String, String>> {
    val primaryFields = when (change.recordType) {
        "semester" -> setOf("startDate", "endDate", "weekCount", "timeZoneIdentifier", "statuses", "showExamWeeks")
        "course" -> setOf("moduleCode", "activeWeeks", "entries", "scheduleNote", "recurrence", "compatibilityFields")
        else -> setOf("kind", "dueDate", "dueTime", "notes", "completedAt", "recurrence", "compatibilityFields")
    }
    val summary = change.fields.filter { it.field in primaryFields }.mapNotNull { field ->
        val value = field.after?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        if (value == null || value == JsonNull || value is JsonPrimitive && value.content.isBlank() ||
            value is JsonObject && value.isEmpty() || value is JsonArray && value.isEmpty() && field.field != "activeWeeks") return@mapNotNull null
        val text = when {
            field.field == "statuses" && value is JsonArray -> {
                val names = value.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                copy.quantity(names.size, "status", "statuses", "Status", "Status", "estado", "estados") +
                    names.take(2).joinToString(" · ", prefix = " · ") + if (names.size > 2) " · +${names.size - 2}" else ""
            }
            field.field == "activeWeeks" && value is JsonArray -> boardWeekRanges(value.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() })
                .ifBlank { copy.t("None recorded", "Keine angegeben", "Ninguna registrada") }
            field.field == "entries" && value is JsonObject -> {
                val entries = value.values.mapNotNull { it as? JsonObject }
                val recordedStatuses = entries.count { it["statusID"] != null && it["statusID"] != JsonNull }
                val recordedNotes = entries.count { (it["notes"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
                copy.quantity(recordedStatuses, "recorded status", "recorded statuses", "angegebener Status", "angegebene Status", "estado registrado", "estados registrados") + " · " +
                    copy.quantity(recordedNotes, "note", "notes", "Notiz", "Notizen", "nota", "notas")
            }
            field.field in setOf("notes", "scheduleNote") -> copy.t("Included · read in Details", "Enthalten · unter Details lesen", "Incluida · leer en Detalles")
            field.field == "compatibilityFields" && value is JsonObject -> copy.quantity(value.size,
                "retained detail", "retained details", "beibehaltene Angabe", "beibehaltene Angaben", "dato conservado", "datos conservados")
            field.field == "recurrence" -> copy.t("Included · read in Details", "Enthalten · unter Details lesen", "Incluida · leer en Detalles")
            else -> boardFieldValue(field.field, field.after, copy, statuses, false)
        }
        boardFieldLabel(field.field, copy) to text
    }.toMutableList()
    if (change.recordType == "assessment") {
        val state = when {
            change.fields.any { it.field == "completed" && it.after == "true" } -> "completed"
            change.fields.any { it.field == "dismissed" && it.after == "true" } -> "dismissed"
            else -> "pending"
        }
        summary += copy.t("State after import", "Status nach dem Import", "Estado tras importar") to boardAssessmentState(state, copy)
        if (change.fields.none { it.field == "dueTime" && it.after != null && it.after != "null" })
            summary += copy.t("Time", "Uhrzeit", "Hora") to copy.t("Date only", "Nur Datum", "Solo fecha")
    }
    return summary
}

private fun boardWeekRanges(weeks: List<Int>): String {
    val sorted = weeks.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val ranges = mutableListOf<String>()
    var start = sorted.first()
    var last = start
    for (week in sorted.drop(1)) {
        if (week == last + 1) last = week else {
            ranges += if (start == last) "$start" else "$start–$last"
            start = week
            last = week
        }
    }
    ranges += if (start == last) "$start" else "$start–$last"
    return ranges.joinToString(", ")
}

private fun boardCountLine(courses: Int, assessments: Int, copy: UiCopy): String =
    copy.quantity(courses, "course", "courses", "Kurs", "Kurse", "curso", "cursos") + " · " +
        copy.quantity(assessments, "assessment", "assessments", "Prüfung oder Frist", "Prüfungen und Fristen", "evaluación", "evaluaciones")

private fun boardSourceCountLine(courses: List<BoardCourse>, copy: UiCopy): String {
    val entries = courses.flatMap { it.entries.values }
    val assessments = courses.flatMap { it.assessments }
    val statuses = entries.count { "statusID" in it.providedFields && it.statusID != null }
    val notes = entries.count { "notes" in it.providedFields && it.notes.isNotBlank() } + courses.count { !it.scheduleNote.isNullOrBlank() } +
        assessments.count { "notes" in it.providedFields && it.notes.isNotBlank() }
    val explicitStates = assessments.filter { "state" in it.providedFields }
    val parts = listOf(copy.quantity(statuses, "recorded week status", "recorded week statuses", "angegebener Wochenstatus", "angegebene Wochenstatus", "estado semanal registrado", "estados semanales registrados"),
        copy.quantity(notes, "note", "notes", "Notiz", "Notizen", "nota", "notas"),
        copy.quantity(assessments.size, "assessment", "assessments", "Prüfung oder Frist", "Prüfungen und Fristen", "evaluación", "evaluaciones"))
    val states = listOf("pending", "completed", "dismissed").map { state -> "${explicitStates.count { it.state == state }} ${boardAssessmentState(state, copy).lowercase(copy.locale)}" }
    val missing = assessments.size - explicitStates.size
    return parts.joinToString(" · ") + "\n" + states.joinToString(" · ") + if (missing > 0) " · $missing " + copy.t("state not supplied", "Status nicht angegeben", "estado no indicado") else ""
}

private fun boardAssessmentState(value: String, copy: UiCopy): String = when (value) {
    "completed" -> copy.t("Completed", "Erledigt", "Completada")
    "dismissed" -> copy.t("Dismissed", "Verworfen", "Descartada")
    else -> copy.t("Pending", "Offen", "Pendiente")
}

private fun boardFieldLabel(value: String, copy: UiCopy): String = when (value) {
    "title", "name" -> copy.title
    "startDate" -> copy.t("Start date", "Startdatum", "Fecha de inicio")
    "endDate" -> copy.t("End date", "Enddatum", "Fecha de fin")
    "weekCount" -> copy.t("Teaching weeks", "Unterrichtswochen", "Semanas lectivas")
    "timeZoneIdentifier" -> copy.t("Source time zone", "Zeitzone der Quelle", "Zona horaria de origen")
    "statuses" -> copy.t("Status names and colours", "Statusnamen und Farben", "Nombres y colores de estados")
    "showExamWeeks" -> copy.t("Extended assessment weeks", "Erweiterte Prüfungswochen", "Semanas de evaluación ampliadas")
    "moduleCode" -> copy.t("Module code", "Modulcode", "Código del módulo")
    "sourceURL" -> copy.t("Source link", "Quelllink", "Enlace de origen")
    "scheduleNote" -> copy.t("Schedule note", "Stundenplannotiz", "Nota del calendario")
    "activeWeeks" -> copy.t("Active weeks", "Aktive Wochen", "Semanas activas")
    "entries" -> copy.t("Weekly status and notes", "Wochenstatus und Notizen", "Estados semanales y notas")
    "notes" -> copy.notes
    "kind" -> copy.t("Assessment type", "Prüfungsart", "Tipo de evaluación")
    "dueDate" -> copy.t("Due date", "Fristdatum", "Fecha límite")
    "dueTime" -> copy.t("Recorded time", "Angegebene Uhrzeit", "Hora registrada")
    "completed" -> copy.t("Completed", "Erledigt", "Completada")
    "completedAt" -> copy.t("Completion recorded at", "Erledigung erfasst am", "Finalización registrada el")
    "dismissed" -> copy.t("Dismissed", "Verworfen", "Descartada")
    "archived" -> copy.t("Archived", "Archiviert", "Archivada")
    "boardOrder" -> copy.t("Order in the board", "Reihenfolge im Board", "Orden en el tablero")
    "recurrence" -> copy.t("Source repeat information", "Wiederholungsangaben der Quelle", "Repetición indicada en origen")
    "compatibilityFields" -> copy.t("Additional source details", "Zusätzliche Quellangaben", "Datos adicionales del origen")
    else -> value
}

private fun boardFieldValue(field: String, raw: String?, copy: UiCopy, statuses: Map<String, String>, before: Boolean): String {
    if (raw == null) return if (before) copy.t("Not previously saved", "Noch nicht gespeichert", "No guardado antes") else copy.t("Not set", "Nicht angegeben", "No indicado")
    val value = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    if (value == JsonNull) return copy.t("Not set", "Nicht angegeben", "No indicado")
    if (field == "statuses" && value is JsonArray) return value.joinToString("\n") { row ->
        val item = row as? JsonObject
        listOfNotNull(item?.get("name")?.jsonPrimitive?.contentOrNull, item?.get("colorHex")?.jsonPrimitive?.contentOrNull).joinToString(" · ")
    }.ifBlank { copy.t("None recorded", "Keine angegeben", "Ninguno registrado") }
    if (field == "entries" && value is JsonObject) return value.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.joinToString("\n") { (week, row) ->
        val item = row as? JsonObject
        val id = (item?.get("statusID") as? JsonPrimitive)?.contentOrNull
        val status = id?.let { statuses[it.lowercase()] } ?: copy.t("No recorded status", "Kein Status angegeben", "Sin estado registrado")
        val notes = (item?.get("notes") as? JsonPrimitive)?.contentOrNull.orEmpty()
        copy.t("Week $week", "Woche $week", "Semana $week") + ": " + status + if (notes.isNotBlank()) " · $notes" else ""
    }.ifBlank { copy.t("None recorded", "Keine angegeben", "Ninguno registrado") }
    if (value is JsonPrimitive) {
        if (!value.isString && value.content in setOf("true", "false")) return if (value.content == "true") copy.t("Yes", "Ja", "Sí") else copy.t("No", "Nein", "No")
        if (field in setOf("startDate", "endDate", "dueDate")) return copy.displayDate(value.content)
        if (field == "dueTime") return copy.displayTime(value.content)
        if (field == "completedAt") return boardReceiptDate(value.content, copy)
        if (field == "boardOrder") return value.content.toIntOrNull()?.plus(1)?.toString() ?: value.content
        if (field == "kind") return when (value.content) {
            "exam" -> copy.t("Exam", "Prüfung", "Examen")
            "presentation" -> copy.t("Presentation", "Präsentation", "Presentación")
            "essay" -> copy.t("Essay", "Essay", "Ensayo")
            else -> copy.t("Deadline", "Frist", "Plazo")
        }
        return value.content.ifEmpty { copy.t("Empty", "Leer", "Vacío") }
    }
    return boardReadableJson(value, copy)
}

private fun boardReadableJson(value: JsonElement, copy: UiCopy): String = when (value) {
    is JsonObject -> value.entries.joinToString("\n") { (key, item) -> "$key: ${boardReadableJson(item, copy)}" }
    is JsonArray -> value.joinToString(", ") { boardReadableJson(it, copy) }
    JsonNull -> copy.t("Not set", "Nicht angegeben", "No indicado")
    is JsonPrimitive -> value.content
}.ifBlank { copy.t("None recorded", "Keine angegeben", "Ninguno registrado") }

private fun boardReceiptDate(value: String, copy: UiCopy): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(copy.locale).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun boardNotificationDisclosure(copy: UiCopy): String = copy.t(
    "Your notification preferences stay unchanged; imported reminder settings do not activate alerts. Calendar events, sharing and sync are not created.",
    "Deine Benachrichtigungseinstellungen bleiben unverändert; importierte Erinnerungseinstellungen aktivieren keine Hinweise. Kalenderereignisse, Freigaben und Sync werden nicht erstellt.",
    "Tus preferencias de notificación no cambian; los ajustes de recordatorio importados no activan alertas. No se crean eventos de calendario, datos compartidos ni sincronización.")

private fun boardImportNote(value: String, copy: UiCopy): String = when (value) {
    "Omitted records and fields keep their saved values. No reminders or synchronization are enabled.",
    "Omitted records and fields keep their saved values. Notification preferences stay unchanged; device reminder settings are not imported. No synchronization is enabled." -> copy.t(
        "Omitted records and fields keep their saved values.", "Fehlende Einträge und Felder behalten ihre gespeicherten Werte.",
        "Los registros y campos omitidos conservan sus valores.") + " " + boardNotificationDisclosure(copy)
    "Compatibility and device reminder metadata will be retained locally; imported reminders are not scheduled." -> copy.t(
        "Additional source details are retained on this phone. Imported reminder settings do not activate alerts.",
        "Zusätzliche Quellangaben bleiben auf diesem Telefon erhalten. Importierte Erinnerungseinstellungen aktivieren keine Hinweise.",
        "Los datos adicionales del origen se conservan en este teléfono. Los ajustes de recordatorio importados no activan alertas.")
    "A legacy completion timestamp conflicts with a pending/dismissed source state. The timestamp stays in local compatibility data; the recorded state is kept and shared without that timestamp." -> copy.t(
        "A legacy completion time conflicts with an open or dismissed source state. The time is retained on this phone. If you share later, the recorded state is included without that time.",
        "Ein älterer Erledigungszeitpunkt widerspricht einem offenen oder verworfenen Quellstatus. Die Zeit bleibt auf diesem Telefon erhalten. Beim späteren Teilen wird der Status ohne diese Zeit aufgenommen.",
        "Una fecha antigua de finalización contradice un estado abierto o descartado. Se conserva en este teléfono. Si compartes más adelante, se incluye el estado registrado sin esa fecha.")
    "Moved recurring assessments remain separate materialized exceptions. Their original series and scheduled date stay recorded; sibling occurrences are unchanged." -> copy.t(
        "Moved recurring assessments remain separate occurrences. The original repeat information and scheduled date are kept; other occurrences stay unchanged.",
        "Verschobene wiederkehrende Prüfungen bleiben einzelne Termine. Ursprüngliche Wiederholungsangaben und geplantes Datum bleiben erhalten; andere Termine ändern sich nicht.",
        "Las evaluaciones repetidas que cambian de fecha siguen separadas. Se conserva la repetición y fecha originales; las otras ocurrencias no cambian.")
    "This semester has no date range. Source weeks are available without current-week dates." -> copy.t(
        "Without a date range, source week numbers remain available; a current week cannot be calculated.",
        "Ohne Datumsbereich bleiben die Quellwochennummern verfügbar; eine aktuelle Woche kann nicht berechnet werden.",
        "Sin un periodo de fechas se conservan los números de semana; no se puede calcular la semana actual.")
    "This import was already handled. Your later edits will be kept." -> copy.t("This import was already handled. Later edits are kept.",
        "Dieser Import wurde bereits verarbeitet. Spätere Änderungen bleiben erhalten.", "Esta importación ya se procesó. Se conservan los cambios posteriores.")
    else -> value
}

private fun boardFailureTitle(action: BoardImportAction, copy: UiCopy): String = when (action) {
    BoardImportAction.READ -> copy.t("File could not be read", "Datei konnte nicht gelesen werden", "No se pudo leer el archivo")
    BoardImportAction.REVIEW -> copy.t("Changes could not be reviewed", "Änderungen konnten nicht geprüft werden", "No se pudieron revisar los cambios")
    BoardImportAction.SAVE -> copy.t("Save could not be confirmed", "Speicherung konnte nicht bestätigt werden", "No se pudo confirmar el guardado")
    BoardImportAction.UNDO -> copy.t("Undo could not be confirmed", "Rückgängig konnte nicht bestätigt werden", "No se pudo confirmar deshacer")
    BoardImportAction.ACKNOWLEDGE -> copy.t("Desktop receipt could not be sent", "Desktopbeleg konnte nicht gesendet werden", "No se pudo enviar el recibo al ordenador")
}

private fun boardFailureBody(action: BoardImportAction, copy: UiCopy): String = when (action) {
    BoardImportAction.READ -> copy.t("This action saved nothing. Choose another file or replace the pasted contents and try again.",
        "Diese Aktion hat nichts gespeichert. Wähle eine andere Datei oder ersetze den eingefügten Inhalt und versuche es erneut.",
        "Esta acción no guardó nada. Elige otro archivo o sustituye el contenido pegado y reintenta.")
    BoardImportAction.REVIEW -> copy.t("This review changed no saved records. If the board changed since it was shared, request a new proposal from the updated context.",
        "Diese Prüfung hat keine gespeicherten Einträge geändert. Wurde das geteilte Board geändert, fordere mit aktuellen Angaben einen neuen Vorschlag an.",
        "Esta revisión no cambió registros guardados. Si el tablero cambió desde que se compartió, pide otra propuesta con el contexto actualizado.")
    BoardImportAction.SAVE -> copy.t("Check the local receipt before retrying. If records changed after review, refresh and review again.",
        "Prüfe vor einem weiteren Versuch den lokalen Beleg. Bei Änderungen seit der Vorschau aktualisiere und prüfe erneut.",
        "Comprueba el recibo local antes de reintentar. Si los registros cambiaron, actualiza y revisa otra vez.")
    BoardImportAction.UNDO -> copy.t("The local receipt shows the last confirmed state. Undo is blocked if imported records, progress, notes or linked preparation changed.",
        "Der lokale Beleg zeigt den zuletzt bestätigten Stand. Änderungen an importierten Einträgen, Fortschritt, Notizen oder Vorbereitung sperren Rückgängig.",
        "El recibo local muestra el último estado confirmado. Cambios en registros, progreso, notas o preparación bloquean deshacer.")
    BoardImportAction.ACKNOWLEDGE -> copy.t("Your local save or Undo remains valid. Retry sending the receipt when the paired desktop is available.",
        "Deine lokale Speicherung oder Rückgängig-Aktion bleibt gültig. Sende den Beleg erneut, sobald der gekoppelte Desktop verfügbar ist.",
        "El guardado o deshacer local sigue válido. Reintenta enviar el recibo cuando esté disponible el ordenador vinculado.")
}
