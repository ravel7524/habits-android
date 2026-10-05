package ch.habits.android.desktop

import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
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

@Serializable data class DesktopDelivery(val proposalID: String, val proposalDigest: String, val localFingerprint: String, val reportedState: String? = null)
@Serializable data class DesktopCredential(val version: Int = 1, val baseURL: String, val certificateSHA256: String, val deviceID: String, val deviceSecret: String, val deliveries: List<DesktopDelivery> = emptyList())
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
data class DesktopState(val paired: Boolean = false, val baseURL: String? = null, val busy: Boolean = false, val error: String? = null, val message: String? = null, val proposals: List<DesktopProposal> = emptyList(), val refreshRevision: Long = 0, val deliveries: List<DesktopDelivery> = emptyList())

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
                }
            }
            mutableState.value = DesktopState(paired = credential != null, baseURL = credential?.baseURL, deliveries = credential?.deliveries ?: emptyList())
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
        val next = DesktopCredential(baseURL = invite.baseURL, certificateSHA256 = invite.certificateSHA256, deviceID = returnedID, deviceSecret = secret)
        try { credentials.save(next) }
        catch (error: Exception) { runCatching { transport(next.baseURL, next.certificateSHA256, "POST", "/v1/assistant/disconnect", "{}", next) }; throw error }
        credential = next
        mutableState.value = DesktopState(paired = true, baseURL = next.baseURL, message = "Desktop paired. Nothing has been shared.")
    }

    suspend fun refresh() = operation {
        val key = requireCredential()
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
        val ledger = key.deliveries.associateBy { it.proposalID.lowercase() }.toMutableMap()
        for (proposal in proposals) {
            val id = proposal.proposal.proposalID.lowercase()
            val next = DesktopDelivery(id, proposal.proposalDigest, DesktopSigning.planFingerprint(proposal.proposal))
            val previous = ledger[id]
            require(previous == null || (previous.proposalDigest == next.proposalDigest && previous.localFingerprint == next.localFingerprint)) { "A delivered proposal changed; local planning data is preserved." }
            ledger[id] = next.copy(reportedState = previous?.reportedState)
        }
        require(ledger.size <= 4000) { "Desktop receipt history is full. Existing receipts were preserved." }
        val updated = key.copy(deliveries = ledger.values.sortedBy { it.proposalID })
        credentials.save(updated) // Persist identity before a proposal can be reviewed/applied.
        credential = updated
        mutableState.value = mutableState.value.copy(proposals = proposals, deliveries = updated.deliveries, message = "${proposals.size} proposals awaiting review.", error = null, refreshRevision = mutableState.value.refreshRevision + 1)
    }

    suspend fun publishContext(context: String) = operation {
        require(context.toByteArray().size <= 2 * 1024 * 1024) { "Context is too large." }
        val value = Json.parseToJsonElement(context).jsonObject
        val key = requireCredential()
        val result = transport(key.baseURL, key.certificateSHA256, "POST", "/v1/assistant/context", context, key)
        require(result.string("status") == "context_shared" && UUID.fromString(result.string("deviceID")) == UUID.fromString(key.deviceID)
            && result.string("capturedAt") == value.string("capturedAt")) { "Invalid sharing receipt." }
        mutableState.value = mutableState.value.copy(message = "Selected academic context shared. Your assistant may send it to its AI provider.", error = null)
    }

    suspend fun acknowledge(envelope: DesktopProposal, receiptState: String) = operation {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val key = requireCredential()
        require(rememberedProposal(envelope.proposal)?.proposalDigest == envelope.proposalDigest) { "This receipt does not match a received proposal. Local data is unchanged." }
        if (!needsAcknowledgement(envelope.proposal, receiptState)) return@operation
        val body = jsonObject("proposalID" to envelope.proposal.proposalID, "proposalDigest" to envelope.proposalDigest, "state" to receiptState)
        val result = transport(key.baseURL, key.certificateSHA256, "POST", "/v1/assistant/receipt", body, key)
        require(UUID.fromString(result.string("proposalID")) == UUID.fromString(envelope.proposal.proposalID)
            && result.string("proposalDigest") == envelope.proposalDigest
            && UUID.fromString(result.string("targetDeviceID")) == UUID.fromString(key.deviceID)
            && (result.string("status") == receiptState || (receiptState == "applied" && result.string("status") == "undone"))) { "Invalid desktop receipt." }
        val updated = key.copy(deliveries = key.deliveries.map {
            if (it.proposalID.equals(envelope.proposal.proposalID, ignoreCase = true)) it.copy(reportedState = result.string("status")) else it
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

    fun rememberedProposal(plan: AcademicPlan): DesktopProposal? {
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(plan.proposalID, ignoreCase = true) } ?: return null
        if (saved.localFingerprint != DesktopSigning.planFingerprint(plan)) return null
        return DesktopProposal(plan, saved.proposalDigest, "pending")
    }

    /** A successful terminal receipt never regresses when an older workspace is restored. */
    fun needsAcknowledgement(plan: AcademicPlan, receiptState: String): Boolean {
        require(receiptState in setOf("applied", "undone", "rejected")) { "Invalid receipt state." }
        val saved = credential?.deliveries?.firstOrNull { it.proposalID.equals(plan.proposalID, ignoreCase = true) } ?: return false
        if (saved.reportedState == receiptState || saved.reportedState in setOf("undone", "rejected")) return false
        if (saved.reportedState == "applied" && receiptState != "undone") return false
        return saved.localFingerprint == DesktopSigning.planFingerprint(plan)
    }
}

object DesktopSigning {
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
        require(connection.responseCode in 200..299) { "Desktop rejected the request (HTTP ${connection.responseCode}). Review the connection and retry; local data stays saved." }
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
