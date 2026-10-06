package ch.habits.android.desktop

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import ch.habits.android.core.AcademicPlan
import ch.habits.android.data.AssistantContextExporter
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.ui.UiCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DesktopConnectionSection(connection: DesktopConnection, repository: WorkspaceRepository, onPlan: (AcademicPlan) -> Unit, operations: kotlinx.coroutines.CoroutineScope? = null) {
    val state by connection.state.collectAsStateWithLifecycle()
    val workspace by repository.state.collectAsStateWithLifecycle()
    val localScope = rememberCoroutineScope()
    val scope = operations ?: localScope
    val lang = when (val value = workspace.snapshot.preferences.language) { "system" -> java.util.Locale.getDefault().language; else -> value }
    val copy = UiCopy(lang)
    fun t(en: String, de: String, es: String): String = copy.t(en, de, es)
    var invitation by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf<DesktopInvite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSemester by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var securityDetails by remember { mutableStateOf(false) }
    var shareReview by remember { mutableStateOf<DesktopShareReview?>(null) }
    val semesters = workspace.snapshot.semesters.filterNot { it.archived }
    val selected = semesters.firstOrNull { it.id == selectedSemester }
    val context = LocalContext.current
    val secondaryColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    fun submit(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: t("Action failed. Saved plans are preserved.", "Aktion fehlgeschlagen. Gespeicherte Pläne bleiben erhalten.", "La acción falló. Se conservan los planes guardados.") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 64 * 1024); output.write(buffer, 0, count) }
                    output.toByteArray()
                }
                invitation = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                reviewed = null; error = null
            } catch (_: Exception) { error = t("Invitation could not be opened. Saved plans are unchanged.", "Die Einladung konnte nicht geöffnet werden. Gespeicherte Pläne bleiben unverändert.", "No se pudo abrir la invitación. Los planes guardados no cambian.") }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("desktop.section"), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), colors = textColors) {
                Text(t("Connect Codex or Claude", "Codex oder Claude verbinden", "Conectar Codex o Claude"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            Text(if (state.paired) t("Connected · sharing stays manual", "Verbunden · Teilen bleibt manuell", "Conectado · compartir es manual") else
                t("Optional · use your own computer", "Optional · mit deinem Computer", "Opcional · usa tu ordenador"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                if (!state.paired) {
                    Text(t("1 · Start Habits MCP on your computer", "1 · Habits MCP auf dem Computer starten", "1 · Inicia Habits MCP en tu ordenador"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(t("Create a pairing invitation there. Keep both devices on the same private network.",
                        "Erstelle dort eine Verbindungseinladung. Beide Geräte müssen im selben privaten Netzwerk sein.",
                        "Crea allí una invitación. Mantén ambos dispositivos en la misma red privada."), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ravel7524/habits-mcp"))) }, colors = textColors) {
                        Text(t("Get the desktop MCP", "Desktop-MCP herunterladen", "Obtener MCP para ordenador"))
                    }
                    Text(t("2 · Open its invitation", "2 · Einladung öffnen", "2 · Abre su invitación"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = secondaryColors) {
                        Text(t("Open pairing invitation", "Verbindungseinladung öffnen", "Abrir invitación de conexión"))
                    }
                    OutlinedTextField(invitation, { value ->
                        if (value.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { invitation = value; reviewed = null; error = null }
                        else error = t("Invitation is too large.", "Die Einladung ist zu gross.", "La invitación es demasiado grande.")
                    }, label = { Text(t("Or paste the invitation", "Oder Einladung einfügen", "O pega la invitación")) }, minLines = 2, maxLines = 5,
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("desktop.invitation"))
                    OutlinedButton(onClick = { try { reviewed = DesktopInvite.decode(invitation); error = null } catch (failure: Exception) { error = failure.message } },
                        enabled = invitation.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth(), colors = secondaryColors) {
                        Text(t("Review connection", "Verbindung prüfen", "Revisar conexión"))
                    }
                    reviewed?.let { invite ->
                        HorizontalDivider()
                        Text(t("3 · Confirm this computer", "3 · Diesen Computer bestätigen", "3 · Confirma este ordenador"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(invite.baseURL, style = MaterialTheme.typography.bodyMedium)
                        Text(t("Check this address and the certificate against the invitation on your computer.",
                            "Vergleiche diese Adresse und das Zertifikat mit der Einladung auf deinem Computer.",
                            "Compara esta dirección y el certificado con la invitación de tu ordenador."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { securityDetails = !securityDetails }, colors = textColors) {
                            Text(if (securityDetails) t("Hide certificate", "Zertifikat ausblenden", "Ocultar certificado") else t("Certificate details", "Zertifikatdetails", "Detalles del certificado"))
                        }
                        if (securityDetails) Text(t("Certificate SHA-256", "Zertifikat SHA-256", "Certificado SHA-256") + "\n" + invite.certificateSHA256.chunked(16).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { submit { connection.pair(invite); invitation = ""; reviewed = null } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("desktop.pair")) {
                            Text(t("Confirm and connect", "Prüfen und verbinden", "Confirmar y conectar"))
                        }
                        Text(t("Connecting shares no planning records.", "Die Verbindung teilt noch keine Planungsdaten.", "Conectar no comparte registros de planificación."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(state.baseURL.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { submit { connection.refresh() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("desktop.refresh"), colors = secondaryColors) {
                        Text(t("Refresh proposals & receipts", "Vorschläge und Belege aktualisieren", "Actualizar propuestas y recibos"))
                    }
                    Text(t("Share one semester", "Ein Semester teilen", "Compartir un semestre"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    if (semesters.isEmpty()) Text(t("Save a semester first, then select it here.", "Speichere zuerst ein Semester und wähle es dann hier.", "Guarda primero un semestre y selecciónalo aquí."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.selectableGroup()) {
                        semesters.forEach { semester ->
                            Row(Modifier.fillMaxWidth().selectable(selected = selected?.id == semester.id, onClick = { selectedSemester = semester.id }, role = Role.RadioButton, enabled = !state.busy)
                                .padding(vertical = 4.dp).testTag("desktop.semester.${semester.id}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected?.id == semester.id, onClick = null, enabled = !state.busy)
                                Text(semester.title, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text(t("Includes courses, assessment dates, notes and recorded states. Your assistant may send these to its AI provider.",
                        "Enthält Kurse, Prüfungsdaten, Notizen und erfasste Zustände. Dein Assistent kann sie an seinen KI-Anbieter senden.",
                        "Incluye cursos, fechas, notas y estados registrados. Tu asistente puede enviarlos a su proveedor de IA."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        val semester = selected ?: return@OutlinedButton
                        try {
                            val captured = workspace.snapshot
                            val courseIDs = captured.courses.filter { !it.archived && it.semesterID == semester.id }.map { it.id }.toSet()
                            shareReview = DesktopShareReview(semester.title, AssistantContextExporter.sharedAcademicContext(captured, semester.id, includeNotes = true), captured.revision,
                                courseIDs.size, captured.assessments.count { !it.archived && it.semesterID == semester.id && it.courseID in courseIDs })
                            error = null
                        } catch (failure: Exception) { error = failure.message }
                    }, enabled = selected != null && !state.busy && !workspace.recoveryRequired, modifier = Modifier.fillMaxWidth().testTag("desktop.share"), colors = secondaryColors) {
                        Text(t("Review selected context", "Gewählten Kontext prüfen", "Revisar contexto seleccionado"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(t("Awaiting your review", "Warten auf deine Prüfung", "Pendientes de tu revisión"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (state.proposals.isEmpty()) Text(if (state.refreshRevision == 0L)
                        t("Refresh to check for proposals from your computer.", "Aktualisiere, um Vorschläge vom Computer abzurufen.", "Actualiza para buscar propuestas de tu ordenador.") else
                        t("No pending proposals from the last refresh.", "Keine offenen Vorschläge bei der letzten Aktualisierung.", "No hay propuestas pendientes de la última actualización."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.proposals.forEach { envelope ->
                        val plan = envelope.proposal
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(plan.semester.title, style = MaterialTheme.typography.titleSmall)
                                Text(t("Source: ", "Quelle: ", "Fuente: ") + plan.source, style = MaterialTheme.typography.bodySmall)
                                Text(copy.quantity(plan.assessments.size, "assessment", "assessments", "Prüfung oder Frist", "Prüfungen und Fristen", "evaluación", "evaluaciones") + " · " +
                                    copy.quantity(plan.preparationTasks.size, "preparation task", "preparation tasks", "Vorbereitungsaufgabe", "Vorbereitungsaufgaben", "tarea de preparación", "tareas de preparación"), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onPlan(plan) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("desktop.proposal.${plan.proposalID}"), colors = textColors) {
                                    Text(t("Review proposal", "Vorschlag prüfen", "Revisar propuesta"))
                                }
                            }
                        }
                    }
                    Text(t("Pending proposals require your confirmation on this phone. Refresh retries delivery of save and Undo receipts.",
                        "Offene Vorschläge brauchen deine Bestätigung auf diesem Telefon. Aktualisieren sendet Speicher- und Rückgängig-Belege erneut.",
                        "Las propuestas pendientes requieren tu confirmación en este teléfono. Actualizar reintenta enviar los recibos de guardado y deshacer."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { securityDetails = !securityDetails }, colors = textColors) {
                        Text(if (securityDetails) t("Hide connection details", "Verbindungsdetails ausblenden", "Ocultar detalles") else t("Connection & privacy details", "Verbindung und Datenschutz", "Conexión y privacidad"))
                    }
                    if (securityDetails) Text(t("The invitation pins the private desktop’s TLS certificate. Requests use per-device HMAC authentication. Calendar, tasks, habit history and credentials are excluded from shared context. Receipts retry when you refresh. Connecting does not enable cloud sync. If the computer is offline when you disconnect, revoke this phone from the desktop too.",
                        "Die Einladung bindet das TLS-Zertifikat des privaten Desktops. Anfragen werden pro Gerät mit HMAC authentifiziert. Kalender, Aufgaben, Gewohnheitshistorie und Zugangsdaten werden nicht geteilt. Belege werden beim Aktualisieren erneut gesendet. Die Verbindung aktiviert keine Cloud-Synchronisierung. Ist der Computer beim Trennen offline, widerrufe dieses Telefon auch am Desktop.",
                        "La invitación fija el certificado TLS del ordenador privado. Las solicitudes usan autenticación HMAC por dispositivo. Se excluyen calendario, tareas, historial de hábitos y credenciales. Los recibos se reintentan al actualizar. Conectar no activa sincronización en la nube. Si el ordenador está sin conexión al desconectar, revoca también este teléfono desde él."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { submit { connection.disconnect(); selectedSemester = null; shareReview = null } }, enabled = !state.busy, colors = textColors) {
                        Text(t("Disconnect desktop", "Desktop trennen", "Desconectar ordenador"))
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                (error ?: state.error)?.let { message ->
                    Text(t("Connection needs attention", "Verbindung braucht deine Prüfung", "La conexión requiere atención"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("desktop.error").semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
    shareReview?.let { review ->
        val unchanged = workspace.snapshot.revision == review.revision
        AlertDialog(onDismissRequest = { shareReview = null }, title = { Text(t("Share this semester?", "Dieses Semester teilen?", "¿Compartir este semestre?")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(review.title, style = MaterialTheme.typography.titleMedium)
                Text(copy.quantity(review.courses, "course", "courses", "Kurs", "Kurse", "curso", "cursos") + " · " +
                    copy.quantity(review.assessments, "assessment", "assessments", "Prüfung oder Frist", "Prüfungen und Fristen", "evaluación", "evaluaciones"))
                Text(t("Includes assessment notes and recorded states. Your assistant may send this context to its AI provider.", "Enthält Prüfungsnotizen und erfasste Zustände. Dein Assistent kann diesen Kontext an seinen KI-Anbieter senden.", "Incluye notas y estados registrados. Tu asistente puede enviar este contexto a su proveedor de IA."))
                Text(t("Other semesters, tasks, habits and Calendar events stay on this phone.", "Andere Semester, Aufgaben, Gewohnheiten und Kalenderereignisse bleiben auf diesem Telefon.", "Otros semestres, tareas, hábitos y eventos del calendario permanecen en este teléfono."), style = MaterialTheme.typography.bodySmall)
                if (!unchanged) Text(t("Your records changed. Close this review and open it again before sharing.", "Deine Daten wurden geändert. Schliesse diese Prüfung und öffne sie vor dem Teilen erneut.", "Tus registros cambiaron. Cierra y vuelve a abrir esta revisión antes de compartir."), color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(onClick = { submit { connection.publishContext(review.payload); shareReview = null } },
                enabled = unchanged && state.paired && !state.busy && !workspace.recoveryRequired, modifier = Modifier.testTag("desktop.share.confirm"), colors = textColors) {
                Text(t("Share selected semester", "Gewähltes Semester teilen", "Compartir semestre seleccionado"))
            } }, dismissButton = { TextButton(onClick = { shareReview = null }, colors = textColors) { Text(t("Cancel", "Abbrechen", "Cancelar")) } }, modifier = Modifier.testTag("desktop.share.review"))
    }
}

private data class DesktopShareReview(val title: String, val payload: String, val revision: Long, val courses: Int, val assessments: Int)
