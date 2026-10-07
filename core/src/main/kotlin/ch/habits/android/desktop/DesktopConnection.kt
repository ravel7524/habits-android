package ch.habits.android.desktop

import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.data.BoardImportReceipt
import ch.habits.android.semester.BoardProposal
import ch.habits.android.semester.SemesterBoardCodec
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val ACADEMIC_PLAN_FORMAT = "habits.academic-plan"
private const val BOARD_PROPOSAL_FORMAT = "habits.semester-board-proposal"
@Serializable data class DesktopDelivery(val proposalID: String, val proposalDigest: String, val localFingerprint: String, val reportedState: String? = null, val proposalFormat: String = ACADEMIC_PLAN_FORMAT)
@Serializable data class DesktopCredential(val version: Int = 1, val baseURL: String, val certificateSHA256: String, val deviceID: String, val deviceSecret: String, val deliveries: List<DesktopDelivery> = emptyList(), val boardProposalsSupported: Boolean = false)
interface DesktopCredentialStore { fun read(): DesktopCredential?; fun save(value: DesktopCredential); fun clear() }
data class DesktopInvite(val baseURL: String, val certificateSHA256: String, val pairingCode: String) {
    companion object {
        fun decode(text: String): DesktopInvite {
            require(text.toByteArray().size <= 64 * 1024) { "Invitation is too large." }
            val value = AcademicPlanCodec.parseStrictJson(text, 64 * 1024).jsonObject
            require(value.keys == setOf("format", "version", "baseURL", "certificateSHA256", "pairingCode")) { "Unsupported invitation fields." }
            require(value.string("format") == "habits.desktop-pairing" && value["version"]?.jsonPrimitive?.isString == false && value["version"]?.jsonPrimitive?.int == 1) { "Unsupported invitation." }
            val url = value.string("baseURL"); validateBaseURL(url)
            val pin = value.string("certificateSHA256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(pin)) { "Invalid certificate fingerprint." }
            val code = value.string("pairingCode")
            require(Regex("[A-Za-z0-9_-]{32,128}").matches(code)) { "Invalid pairing code." }
            return DesktopInvite(url.trimEnd('/'), pin, code)
        }
    }
}
data class DesktopProposal(val proposal: AcademicPlan, val proposalDigest: String, val status: String)
data class DesktopBoardProposal(val proposal: BoardProposal, val proposalDigest: String, val status: String)
data class DesktopState(val paired: Boolean = false, val baseURL: String? = null, val busy: Boolean = false, val error: String? = null, val message: String? = null, val proposals: List<DesktopProposal> = emptyList(), val refreshRevision: Long = 0, val deliveries: List<DesktopDelivery> = emptyList(), val boardProposals: List<DesktopBoardProposal> = emptyList(), val boardProposalsSupported: Boolean = false)

/** A status returned after pinned TLS and (where supplied) per-device authentication. */
class DesktopHTTPException(val statusCode: Int) : java.io.IOException("Desktop rejected the request (HTTP $statusCode). Review the connection and retry; local data stays saved.")

/** Manual local connection. The phone never uploads its workspace implicitly. */
class DesktopConnection internal constructor(
    private val credentials: DesktopCredentialStore,
    private val transport: (String, String, String, String, String, DesktopCredential?) -> JsonObject,
) {
    constructor(credentials: DesktopCredentialStore) : this(credentials, ::request)
    @Volatile private var credential: DesktopCredential? = null
    private val mutableState = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = mutableState
    private val mutex = Mutex()
    init {
        try {
            credential = credentials.read()?.also { saved ->
                require(saved.version == 1); validateBaseURL(saved.baseURL)
                require(Regex("[0-9a-fA-F]{64}").matches(saved.deviceSecret))
                require(Regex("[0-9a-fA-F]{64}").matches(saved.certificateSHA256)); UUID.fromString(saved.deviceID)
                require(saved.deliveries.size <= 4000 && saved.deliveries.map { it.proposalID.lowercase() }.distinct().size == saved.deliveries.size)
                saved.deliveries.forEach {
                    UUID.fromString(it.proposalID)
                    require(Regex("[0-9a-f]{64}").matches(it.proposalDigest) && Regex("[0-9a-f]{64}").matches(it.localFingerprint))
                    require(it.reportedState == null || it.reportedState in setOf("applied", "undone", "rejected"))
                    require(it.proposalFormat in setOf(ACADEMIC_PLAN_FORMAT, BOARD_PROPOSAL_FORMAT))
                }
            }
            mutableState.value = DesktopState(paired = credential != null, baseURL = credential?.baseURL, deliveries = credential?.deliveries ?: emptyList(), boardProposalsSupported = credential?.boardProposalsSupported == true)
        } catch (_: Exception) { mutableState.value = DesktopState(error = "Desktop settings could not be read. They were preserved; reconnect before sharing.") }
    }

    suspend fun pair(invite: DesktopInvite) = operation {
        val device = credential?.deviceID ?: UUID.randomUUID().toString()
        val body = jsonObject("pairingCode" to invite.pairingCode, "deviceID" to device, "deviceName" to "Habits on Android")
        val response = transport(invite.baseURL, invite.certificateSHA256, "POST", "/v1/assistant/pair", body, null)
        val returnedID = response.string("deviceID")
        require(UUID.fromString(returnedID) == UUID.fromString(device)) { "Unexpected device identity." }
        val secret = response.string("deviceSecret")
        require(Regex("[0-9a-fA-F]{64}").matches(secret)) { "Invalid device credential." }
        val priorDeliveries = credential?.takeIf { it.baseURL == invite.baseURL && it.certificateSHA256.equals(invite.certificateSHA256, ignoreCase = true) }?.deliveries ?: emptyList()
        val next = DesktopCredential(baseURL = invite.baseURL, certificateSHA256 = invite.certificateSHA256, deviceID = returnedID, deviceSecret = secret, deliveries = priorDeliveries)
        try { credentials.save(next) }
        catch (error: Exception) { runCatching { transport(next.baseURL, next.certificateSHA256, "POST", "/v1/assistant/disconnect", "{}", next) }; throw error }
        credential = next
        mutableState.value = DesktopState(paired = true, baseURL = next.baseURL, deliveries = next.deliveries, message = "Desktop paired. Nothing has been shared.")
        // Pairing remains usable when an old desktop has no capability route, or upgrade discovery is offline.
        runCatching { registerProposalCapabilities(next) }.onFailure {
            mutableState.value = mutableState.value.copy(error = "Desktop paired, but Semester Board support could not be checked. Refresh to retry. Nothing has been shared.")
        }
    }

    suspend fun refresh(registerCapabilities: Boolean = false) = operation {
        var key = requireCredential()
        var boardError: String? = null
        if (registerCapabilities) {
            try { key = registerProposalCapabilities(key) }
            catch (_: Exception) { boardError = "Academic proposals refreshed; Semester Board support could not be checked. Refresh to retry." }
        }
        val result = transport(key.baseURL, key.certificateSHA256, "GET", "/v1/assistant/proposals", "", key)
        require(result.keys == setOf("proposals")) { "Invalid desktop response." }
        val rows = result["proposals"]?.jsonArray ?: error("Invalid proposals.")
        require(rows.size <= 200) { "Too many proposals." }
        val proposals = rows.map { element ->
            val row = element.jsonObject
            require(row.keys == setOf("proposal", "proposalDigest", "status")) { "Invalid proposal envelope." }
            val plan = AcademicPlanCodec.decode(row.getValue("proposal").toString())
            val digest = row.string("proposalDigest")
            require(Regex("[0-9a-f]{64}").matches(digest) && row.string("status") == "pending") { "Invalid proposal status." }
            DesktopProposal(plan, digest, "pending")
        }
        require(proposals.map { it.proposal.proposalID.lowercase() }.distinct().size == proposals.size) { "Duplicate proposal identities." }
        var boardProposals = mutableState.value.boardProposals
        if (key.boardProposalsSupported) {
            try {
                boardProposals = decodeBoardProposals(transport(key.baseURL, key.certificateSHA256, "GET", "/v1/assistant/board-proposals", "", key))
            } catch (failure: DesktopHTTPException) {
                if (failure.statusCode == 404) {
                    key = key.copy(boardProposalsSupported = false)
                    boardProposals = emptyList()
                } else boardError = "Academic proposals refreshed; Semester Board proposals could not be refreshed. Previous Board proposals and local records were preserved."
            } catch (_: Exception) {
                boardError = "Academic proposals refreshed; Semester Board proposals could not be refreshed. Previous Board proposals and local records were preserved."
            }
        } else boardProposals = emptyList()
        val ledger = key.deliveries.associateBy { it.proposalID.lowercase() }.toMutableMap()
        for (proposal in proposals) {
            val id = proposal.proposal.proposalID.lowercase()
            val next = DesktopDelivery(id, proposal.proposalDigest, DesktopSigning.planFingerprint(proposal.proposal))
            val previous = ledger[id]
            require(previous == null || (previous.proposalFormat == next.proposalFormat && previous.proposalDigest == next.proposalDigest && previous.localFingerprint == next.localFingerprint)) { "A delivered proposal changed; local planning data is preserved." }
            ledger[id] = next.copy(reportedState = previous?.reportedState)
        }
        for (proposal in boardProposals) {
            val id = proposal.proposal.proposalID.lowercase()
            val next = DesktopDelivery(id, proposal.proposalDigest, DesktopSigning.boardFingerprint(proposal.proposal), proposalFormat = BOARD_PROPOSAL_FORMAT)
            val previous = ledger[id]
            require(previous == null || (previous.proposalFormat == next.proposalFormat && previous.proposalDigest == next.proposalDigest && previous.localFingerprint == next.localFingerprint)) { "A delivered proposal changed; local planning data is preserved." }
            ledger[id] = next.copy(reportedState = previous?.reportedState)
        }
        require(ledger.size <= 4000) { "Desktop receipt history is full. Existing receipts were preserved." }
        val updated = key.copy(deliveries = ledger.values.sortedBy { it.proposalID })
        credentials.save(updated) // Persist identity before a proposal can be reviewed/applied.
        credential = updated
        mutableState.value = mutableState.value.copy(proposals = proposals, boardProposals = boardProposals, boardProposalsSupported = updated.boardProposalsSupported, deliveries = updated.deliveries, message = "${proposals.size + boardProposals.size} proposals awaiting review." + if (updated.boardProposalsSupported) "" else " Semester Board support has not been confirmed; update Habits MCP and refresh to check.", error = boardError, refreshRevision = mutableState.value.refreshRevision + 1)
    }

    suspend fun publishContext(context: String) = operation {
        sendContext(context, "/v1/assistant/context", requireCredential())
        mutableState.value = mutableState.value.copy(message = "Selected academic context shared. Your assistant may send it to its AI provider.", error = null)
    }

    /** Both frozen payloads are covered by the same explicit selected-semester confirmation. */
    suspend fun publishSelectedContext(context: String, boardContext: String?) = operation {
        val key = requireCredential()
        // Reject an invalid Board payload before the first network write; local planning data is never touched.
        if (boardContext != null) {
            DesktopBoardContext.validate(boardContext)
            val selected = AcademicPlanCodec.parseStrictJson(context).jsonObject.getValue("semesters").jsonArray
            val board = AcademicPlanCodec.parseStrictJson(boardContext).jsonObject.getValue("semester").jsonObject
            require(selected.size == 1 && UUID.fromString(selected.single().jsonObject.string("id")) == UUID.fromString(board.string("id"))) { "Share only the same explicitly selected semester in both context formats." }
        }
        if (!legacyClocksRepresentable(context)) {
            require(boardContext != null && key.boardProposalsSupported) { "These recorded source clocks cannot be represented by the legacy exam context. Update Habits MCP and refresh Board support, or use an offline Semester Board export. Nothing was shared; original records stay saved." }
            try { sendContext(boardContext, "/v1/assistant/board-context", key) }
            catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(message = "Semester Board sharing was not confirmed; it may have reached the desktop. Legacy exam context was not sent. Original records stay saved.")
                throw java.io.IOException("Semester Board sharing was not confirmed. Legacy exam context was not sent because it cannot represent the recorded source clocks. Refresh support and review sharing again to retry.", failure)
            }
            mutableState.value = mutableState.value.copy(message = "Selected Semester Board context shared. Legacy exam context was not shared because it cannot represent these recorded source clocks. Use the Semester Board tools. Your assistant may send the Board context to its AI provider.", error = null)
            return@operation
        }
        sendContext(context, "/v1/assistant/context", key)
        if (boardContext == null || !key.boardProposalsSupported) {
            mutableState.value = mutableState.value.copy(message = "Selected academic context shared. Semester Board context was not shared with this desktop. Your assistant may send the academic context to its AI provider.", error = null)
            return@operation
        }
        try { sendContext(boardContext, "/v1/assistant/board-context", key) }
        catch (failure: Exception) {
            if (failure is DesktopHTTPException && failure.statusCode == 404) {
                val updated = key.copy(boardProposalsSupported = false)
                // A cache write failure must not hide the already confirmed first upload.
                runCatching { credentials.save(updated) }.onSuccess {
                    credential = updated
                    mutableState.value = mutableState.value.copy(boardProposalsSupported = false, boardProposals = emptyList())
                }
            }
            mutableState.value = mutableState.value.copy(message = "Selected academic context shared. Semester Board context was not confirmed; it may have reached the desktop. Local records stay saved. Refresh support and review sharing again to retry.")
            throw java.io.IOException("Academic context sharing was confirmed, but Semester Board sharing was not confirmed. Local planning data is unchanged.", failure)
        }
        mutableState.value = mutableState.value.copy(message = "Selected academic and Semester Board context shared. Your assistant may send it to its AI provider.", error = null)
    }

    suspend fun publishBoardContext(context: String) = operation {
        DesktopBoardContext.validate(context)
        val key = requireCredential()
        require(key.boardProposalsSupported) { "Update Habits MCP and refresh support before sharing Semester Board context." }
        sendContext(context, "/v1/assistant/board-context", key)
        mutableState.value = mutableState.value.copy(message = "Selected Semester Board context shared. Your assistant may send it to its AI provider.", error = null)
    }

    suspend fun acknowledge(envelope: DesktopProposal, receiptState: String) = operation {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val key = requireCredential()
        require(rememberedProposal(envelope.proposal)?.proposalDigest == envelope.proposalDigest) { "This receipt does not match a received proposal. Local data is unchanged." }
        if (!needsAcknowledgement(envelope.proposal, receiptState)) return@operation
        reportReceipt(envelope.proposal.proposalID, envelope.proposalDigest, receiptState, key)
    }

    suspend fun acknowledge(envelope: DesktopBoardProposal, receiptState: String) = operation {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val key = requireCredential()
        require(rememberedProposal(envelope.proposal)?.proposalDigest == envelope.proposalDigest) { "This receipt does not match a received Board proposal. Local data is unchanged." }
        if (!needsAcknowledgement(envelope.proposal, receiptState)) return@operation
        reportReceipt(envelope.proposal.proposalID, envelope.proposalDigest, receiptState, key)
    }

    suspend fun acknowledge(receipt: BoardImportReceipt, receiptState: String) = operation {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val key = requireCredential()
        val envelope = rememberedProposal(receipt) ?: error("This receipt does not match a received Board proposal. Local data is unchanged.")
        if (!needsAcknowledgement(receipt.proposalID, BOARD_PROPOSAL_FORMAT, receipt.digest, receiptState)) return@operation
        reportReceipt(envelope.proposal.proposalID, envelope.proposalDigest, receiptState, key)
    }

    private fun reportReceipt(proposalID: String, proposalDigest: String, receiptState: String, key: DesktopCredential) {
        val body = jsonObject("proposalID" to proposalID, "proposalDigest" to proposalDigest, "state" to receiptState)
        val result = transport(key.baseURL, key.certificateSHA256, "POST", "/v1/assistant/receipt", body, key)
        require(UUID.fromString(result.string("proposalID")) == UUID.fromString(proposalID)
            && result.string("proposalDigest") == proposalDigest
            && UUID.fromString(result.string("targetDeviceID")) == UUID.fromString(key.deviceID)
            && (result.string("status") == receiptState || (receiptState == "applied" && result.string("status") == "undone"))) { "Invalid desktop receipt." }
        val updated = key.copy(deliveries = key.deliveries.map {
            if (it.proposalID.equals(proposalID, ignoreCase = true)) it.copy(reportedState = result.string("status")) else it
        })
        credentials.save(updated) // Failed persistence remains retryable with the same idempotent receipt.
        credential = updated
        mutableState.value = mutableState.value.copy(deliveries = updated.deliveries,
            message = "Desktop receipt confirmed: ${result.string("status")}", error = null)
    }

    suspend fun disconnect() = operation {
        val key = credential
        var revoked = false
        if (key != null) revoked = runCatching {
            val result = transport(key.baseURL, key.certificateSHA256, "POST", "/v1/assistant/disconnect", "{}", key)
            require(result.string("status") == "disconnected" && UUID.fromString(result.string("deviceID")) == UUID.fromString(key.deviceID))
            true
        }.getOrDefault(false)
        credentials.clear()
        credential = null
        mutableState.value = DesktopState(message = if (revoked) "Desktop disconnected. Planning data was preserved." else "Disconnected on this phone. Remote revocation is unconfirmed; revoke this device from the desktop if needed.")
    }

    private suspend fun operation(block: () -> Unit) = mutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try { withContext(Dispatchers.IO) { block() } }
        catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "Desktop request failed. Local planning data is unchanged."); throw error }
        finally { mutableState.value = mutableState.value.copy(busy = false) }
    }
    private fun requireCredential(): DesktopCredential = credential ?: error("Connect your desktop first.")

    private fun registerProposalCapabilities(key: DesktopCredential): DesktopCredential {
        val formats = JsonArray(listOf(ACADEMIC_PLAN_FORMAT, BOARD_PROPOSAL_FORMAT).map { JsonObject(mapOf("format" to JsonPrimitive(it), "version" to JsonPrimitive(1))) })
        val body = JsonObject(mapOf("format" to JsonPrimitive("habits.desktop-capabilities"), "version" to JsonPrimitive(1), "platform" to JsonPrimitive("android"), "proposalFormats" to formats)).toString()
        val supported = try {
            val result = transport(key.baseURL, key.certificateSHA256, "POST", "/v1/assistant/capabilities", body, key)
            require(result.keys == setOf("status", "deviceID", "platform", "proposalFormats")
                && result.string("status") == "capabilities_registered"
                && UUID.fromString(result.string("deviceID")) == UUID.fromString(key.deviceID)
                && result.string("platform") == "android"
                && result["proposalFormats"] == formats) { "Invalid desktop capability receipt." }
            true
        } catch (failure: DesktopHTTPException) {
            if (failure.statusCode != 404) throw failure
            false // Only an authenticated 404 at this known route marks a legacy desktop.
        }
        val updated = key.copy(boardProposalsSupported = supported)
        credentials.save(updated); credential = updated
        mutableState.value = mutableState.value.copy(boardProposalsSupported = supported,
            boardProposals = if (supported) mutableState.value.boardProposals else emptyList())
        return updated
    }

    private fun decodeBoardProposals(result: JsonObject): List<DesktopBoardProposal> {
        require(result.keys == setOf("proposals")) { "Invalid desktop Board response." }
        val rows = result["proposals"]?.jsonArray ?: error("Invalid Board proposals.")
        require(rows.size <= 200) { "Too many Board proposals." }
        val proposals = rows.map { element ->
            val row = element.jsonObject
            require(row.keys == setOf("proposal", "proposalDigest", "status")) { "Invalid Board proposal envelope." }
            val proposal = SemesterBoardCodec.decodeProposal(row.getValue("proposal").toString())
            val digest = row.string("proposalDigest")
            require(Regex("[0-9a-f]{64}").matches(digest) && row.string("status") == "pending") { "Invalid Board proposal status." }
            DesktopBoardProposal(proposal, digest, "pending")
        }
        require(proposals.map { it.proposal.proposalID.lowercase() }.distinct().size == proposals.size) { "Duplicate Board proposal identities." }
        return proposals
    }

    private fun sendContext(context: String, path: String, key: DesktopCredential) {
        require(context.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Context is too large." }
        val value = AcademicPlanCodec.parseStrictJson(context).jsonObject
        val result = transport(key.baseURL, key.certificateSHA256, "POST", path, context, key)
        require(result.string("status") == "context_shared" && UUID.fromString(result.string("deviceID")) == UUID.fromString(key.deviceID)
            && result.string("capturedAt") == value.string("capturedAt")) { "Invalid sharing receipt." }
    }

    /** Match the v1 desktop validator: start at noon, timed deadlines literally, date-only deadlines at 23:59:59. */
    private fun legacyClocksRepresentable(context: String): Boolean {
        val semesters = AcademicPlanCodec.parseStrictJson(context).jsonObject.getValue("semesters").jsonArray
        return semesters.all semesterLoop@ { element ->
            val semester = element.jsonObject
            val zoneName = semester["timeZoneIdentifier"]?.jsonPrimitive?.also { require(it.isString) }?.content ?: return@semesterLoop true
            if ((zoneName != "UTC" && '/' !in zoneName) || zoneName !in ZoneId.getAvailableZoneIds()) return@semesterLoop false
            val zone = ZoneId.of(zoneName)
            fun resolves(date: String, clock: LocalTime): Boolean = runCatching {
                val day = LocalDate.parse(date)
                require(day.year in 1..9999)
                val local = LocalDateTime.of(day, clock)
                val offsets = zone.rules.getValidOffsets(local)
                offsets.size == 1 && LocalDateTime.ofInstant(local.toInstant(offsets.single()), ZoneId.of("UTC")).year in 1..9999
            }.getOrDefault(false)
            val start = semester["startDate"]?.jsonPrimitive?.also { require(it.isString) }?.content
            (start == null || resolves(start, LocalTime.NOON)) && semester.getValue("existingAssessments").jsonArray.all { row ->
                val assessment = row.jsonObject
                val dueTime = assessment["dueTime"]?.jsonPrimitive?.also { require(it.isString) }?.content
                val clock = if (dueTime == null) LocalTime.of(23, 59, 59) else runCatching { LocalTime.parse(dueTime) }.getOrNull()
                clock != null && resolves(assessment.string("dueDate"), clock)
            }
        }
    }

    fun rememberedProposal(plan: AcademicPlan): DesktopProposal? {
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(plan.proposalID, ignoreCase = true) } ?: return null
        if (saved.proposalFormat != ACADEMIC_PLAN_FORMAT || saved.localFingerprint != DesktopSigning.planFingerprint(plan)) return null
        return DesktopProposal(plan, saved.proposalDigest, "pending")
    }

    /** A successful terminal receipt never regresses when an older workspace is restored. */
    fun needsAcknowledgement(plan: AcademicPlan, receiptState: String): Boolean {
        return needsAcknowledgement(plan.proposalID, ACADEMIC_PLAN_FORMAT, DesktopSigning.planFingerprint(plan), receiptState)
    }

    fun rememberedProposal(proposal: BoardProposal): DesktopBoardProposal? {
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(proposal.proposalID, ignoreCase = true) } ?: return null
        if (saved.proposalFormat != BOARD_PROPOSAL_FORMAT || saved.localFingerprint != DesktopSigning.boardFingerprint(proposal)) return null
        return DesktopBoardProposal(proposal, saved.proposalDigest, "pending")
    }

    fun rememberedProposal(receipt: BoardImportReceipt): DesktopBoardProposal? {
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(receipt.proposalID, ignoreCase = true) } ?: return null
        if (saved.proposalFormat != BOARD_PROPOSAL_FORMAT || saved.localFingerprint != receipt.digest) return null
        val proposal = receiptProposal(receipt) ?: return null
        return DesktopBoardProposal(proposal, saved.proposalDigest, "pending")
    }

    fun needsAcknowledgement(proposal: BoardProposal, receiptState: String): Boolean =
        needsAcknowledgement(proposal.proposalID, BOARD_PROPOSAL_FORMAT, DesktopSigning.boardFingerprint(proposal), receiptState)

    fun needsAcknowledgement(receipt: BoardImportReceipt, receiptState: String): Boolean {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        if (receipt.rawFile) return false
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(receipt.proposalID, ignoreCase = true) } ?: return false
        if (saved.proposalFormat != BOARD_PROPOSAL_FORMAT || saved.localFingerprint != receipt.digest || !transitionNeeded(saved, receiptState)) return false
        // Terminal history is checked first so manual refresh does not decode every consumed Board payload.
        return receiptProposal(receipt) != null
    }

    private fun receiptProposal(receipt: BoardImportReceipt): BoardProposal? = runCatching {
        require(!receipt.rawFile) { "Local file receipts are not desktop proposal receipts." }
        val proposal = SemesterBoardCodec.decodeProposal(receipt.payload)
        require(proposal.proposalID.equals(receipt.proposalID, ignoreCase = true)
            && proposal.semester.id.equals(receipt.semesterID, ignoreCase = true)
            && proposal.source == receipt.source && DesktopSigning.boardFingerprint(proposal) == receipt.digest)
        proposal
    }.getOrNull()

    private fun needsAcknowledgement(proposalID: String, format: String, fingerprint: String, receiptState: String): Boolean {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(proposalID, ignoreCase = true) } ?: return false
        if (saved.proposalFormat != format) return false
        return transitionNeeded(saved, receiptState) && saved.localFingerprint == fingerprint
    }

    private fun transitionNeeded(saved: DesktopDelivery, receiptState: String): Boolean {
        if (saved.reportedState == receiptState || saved.reportedState in setOf("undone", "rejected")) return false
        if (saved.reportedState == "applied" && receiptState != "undone") return false
        return true
    }
}

object DesktopSigning {
    fun boardFingerprint(proposal: BoardProposal): String = sha256(SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(proposal)).toByteArray(Charsets.UTF_8))
    fun planFingerprint(plan: AcademicPlan): String {
        val normalized = plan.copy(proposalID = plan.proposalID.lowercase(), semester = plan.semester.copy(id = plan.semester.id.lowercase()),
            courses = plan.courses.map { it.copy(id = it.id.lowercase()) },
            assessments = plan.assessments.map { it.copy(id = it.id.lowercase(), courseID = it.courseID.lowercase()) },
            preparationTasks = plan.preparationTasks.map { it.copy(id = it.id.lowercase(), assessmentID = it.assessmentID.lowercase()) })
        return sha256(AcademicPlanCodec.encode(normalized).toByteArray(Charsets.UTF_8))
    }
    fun signature(secret: String, method: String, path: String, timestamp: String, nonce: String, body: ByteArray): String {
        require(Regex("[0-9a-fA-F]{64}").matches(secret))
        val bytes = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val message = "$method\n$path\n$timestamp\n$nonce\n${sha256(body)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).hex()
    }
}

private fun request(base: String, pin: String, method: String, path: String, body: String, credential: DesktopCredential?): JsonObject {
    val manager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("Client certificate is unsupported.")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No desktop certificate.")
            certificate.checkValidity()
            require(MessageDigest.isEqual(sha256(certificate.encoded).toByteArray(), pin.lowercase().toByteArray())) { "Desktop certificate fingerprint does not match the invitation." }
            certificate.verify(certificate.publicKey) // The private bridge intentionally uses its pinned self-signed certificate.
        }
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
    val connection = (URI(base.trimEnd('/') + path).toURL().openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = ssl.socketFactory
        connectTimeout = 15_000; readTimeout = 20_000; requestMethod = method
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
    }
    val bytes = body.toByteArray(Charsets.UTF_8)
    if (credential != null) {
        val timestamp = Instant.now().epochSecond.toString(); val nonce = UUID.randomUUID().toString()
        connection.setRequestProperty("X-Habits-Device", credential.deviceID)
        connection.setRequestProperty("X-Habits-Timestamp", timestamp)
        connection.setRequestProperty("X-Habits-Nonce", nonce)
        connection.setRequestProperty("X-Habits-Signature", DesktopSigning.signature(credential.deviceSecret, method, path, timestamp, nonce, bytes))
    }
    try {
        if (method == "POST") {
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(bytes) }
        }
        if (connection.responseCode !in 200..299) throw DesktopHTTPException(connection.responseCode)
        val limit = 2 * 1024 * 1024 + 64 * 1024
        require(connection.contentLengthLong <= limit) { "Desktop response is too large." }
        val data = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(data.size() + count <= limit); data.write(buffer, 0, count) }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return AcademicPlanCodec.parseStrictJson(decoder.decode(java.nio.ByteBuffer.wrap(data.toByteArray())).toString(), limit).jsonObject
    } finally { connection.disconnect() }
}

private fun validateBaseURL(value: String) {
    val uri = URI(value)
    require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/") && uri.port in 1..65535) { "Use the private HTTPS address from the desktop invitation." }
    val host = (uri.host ?: error("Invalid desktop address.")).lowercase().removePrefix("[").removeSuffix("]")
    val privateV6 = if (host.contains(':')) runCatching {
        val address = java.net.InetAddress.getByName(host)
        address is java.net.Inet6Address && ((address.address[0].toInt() and 0xfe) == 0xfc)
    }.getOrDefault(false) else false
    val private = host == "localhost" || host.endsWith(".local") || host == "::1" || privateV6 || run {
        val labels = host.split('.')
        val parts = labels.mapNotNull { label -> label.toIntOrNull()?.takeIf { n -> n in 0..255 && label.all(Char::isDigit) && (label.length == 1 || label.first() != '0') } }
        labels.size == 4 && parts.size == 4 && (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
    }
    require(private) { "This connection supports your private desktop network only." }
}
private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun jsonObject(vararg pairs: Pair<String, String>): String = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }).toString()
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
