package ch.habits.android.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.habits.android.core.AcademicPlan
import ch.habits.android.data.AssistantContextExporter
import ch.habits.android.data.WorkspaceRepository
import kotlinx.coroutines.launch

@Composable
fun DesktopConnectionSection(connection: DesktopConnection, repository: WorkspaceRepository, onPlan: (AcademicPlan) -> Unit, operations: kotlinx.coroutines.CoroutineScope? = null) {
    val state by connection.state.collectAsStateWithLifecycle()
    val workspace by repository.state.collectAsStateWithLifecycle()
    val localScope = rememberCoroutineScope()
    val scope = operations ?: localScope
    val lang = when (val value = workspace.snapshot.preferences.language) { "system" -> java.util.Locale.getDefault().language; else -> value }
    fun t(en: String, de: String, es: String): String = when (lang) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
    var invitation by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf<DesktopInvite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSemester by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
            } catch (_: Exception) { error = "Invitation could not be opened. Local data is unchanged." }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("desktop.section")) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(t("Connect Codex or Claude","Codex oder Claude verbinden","Conectar Codex o Claude")) }
            if (expanded) {
                Text(t("Run the GitHub MCP package on your computer. Use its invitation to pair over your private network.","Starte das MCP-Paket von GitHub auf deinem Computer. Verwende seine Einladung für die Verbindung im privaten Netzwerk.","Ejecuta el paquete MCP de GitHub en tu ordenador. Usa su invitación para conectar por tu red privada."))
                if (!state.paired) {
                    TextButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }) { Text(t("Open pairing invitation","Verbindungseinladung öffnen","Abrir invitación de conexión")) }
                    OutlinedTextField(invitation, { invitation = it; reviewed = null }, label = { Text(t("Paste pairing invitation","Verbindungseinladung einfügen","Pegar invitación de conexión")) }, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("desktop.invitation"))
                    TextButton(onClick = { try { reviewed = DesktopInvite.decode(invitation); error = null } catch (e: Exception) { error = e.message } }, enabled = !state.busy) { Text(t("Review connection","Verbindung prüfen","Revisar conexión")) }
                    reviewed?.let { invite ->
                        Text(invite.baseURL)
                        Text("Certificate: ${invite.certificateSHA256}", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { scope.launch { runCatching { connection.pair(invite) }; reviewed = null } }, enabled = !state.busy, modifier = Modifier.testTag("desktop.pair")) { Text(t("Confirm and connect","Prüfen und verbinden","Confirmar y conectar")) }
                    }
                } else {
                    Text("Paired desktop: ${state.baseURL}")
                    TextButton(onClick = { scope.launch { runCatching { connection.refresh() } } }, enabled = !state.busy, modifier = Modifier.testTag("desktop.refresh")) { Text(t("Refresh proposals","Vorschläge aktualisieren","Actualizar propuestas")) }
                    for (semester in workspace.snapshot.semesters.filter { !it.archived }) {
                        RowForSemester(semester.title, selectedSemester == semester.id) { selectedSemester = semester.id }
                    }
                    Text(t("Share only the selected semester's course names, assessment dates, notes and recorded states. Your assistant may send these facts to its AI provider. Tasks and habit history stay on this phone.","Teile nur Kursnamen, Prüfungsdaten, Notizen und erfasste Zustände des gewählten Semesters. Dein Assistent kann diese Angaben an seinen KI-Anbieter senden. Aufgaben und Gewohnheitshistorie bleiben auf diesem Telefon.","Comparte solo nombres de cursos, fechas, notas y estados registrados del semestre seleccionado. Tu asistente puede enviar estos datos a su proveedor de IA. Las tareas y el historial de hábitos permanecen en este teléfono."), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { try { connection.publishContext(AssistantContextExporter.sharedAcademicContext(workspace.snapshot, selectedSemester!!, includeNotes = true)); error = null } catch (e: Exception) { error = e.message } } }, enabled = selectedSemester != null && !state.busy && !workspace.recoveryRequired, modifier = Modifier.testTag("desktop.share")) { Text(t("Share selected academic context","Gewähltes Semester teilen","Compartir semestre seleccionado")) }
                    for (proposal in state.proposals) {
                        TextButton(onClick = { onPlan(proposal.proposal) }, enabled = !state.busy, modifier = Modifier.testTag("desktop.proposal.${proposal.proposal.proposalID}")) { Text("Review ${proposal.proposal.source}") }
                    }
                    TextButton(onClick = { scope.launch { runCatching { connection.disconnect() } } }, enabled = !state.busy) { Text(t("Disconnect","Verbindung trennen","Desconectar")) }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("desktop.error")) }
            }
        }
    }
}

@Composable private fun RowForSemester(title: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text((if (selected) "✓ " else "") + title) }
}
