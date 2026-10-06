package ch.habits.android.localai

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import ch.habits.android.core.AcademicPlanCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

enum class ModelDeliveryStatus { MISSING, DOWNLOADING, VERIFYING, READY, ERROR }
data class ModelDeliveryState(
    val status: ModelDeliveryStatus = ModelDeliveryStatus.MISSING,
    val received: Long = 0,
    val total: Long = ModelCatalog.starter.size,
    val error: String? = null,
)

/** Fixed author catalog only. Weights and partials never enter workspace/MCP exports or backups. */
class ModelStore internal constructor(
    private val directory: File,
    val descriptor: ModelDescriptor,
    private val transport: ModelDeliveryTransport,
    private val freeBytes: () -> Long = { directory.usableSpace },
    private val reserveBytes: Long = 512L * 1024 * 1024,
) {
    constructor(context: Context, descriptor: ModelDescriptor = ModelCatalog.starter) : this(
        File(context.applicationContext.noBackupFilesDir, "models"), catalogModel(descriptor), ModelDeliveryHttps(catalogModel(descriptor)),
    )
    private val guard = ModelDeliveryGuards.forPath(directory.absolutePath)
    private val mutableState = ModelDeliveryGuards.state(directory.absolutePath, descriptor)
    val state: StateFlow<ModelDeliveryState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startup = scope.async {
        guard.mutex.withLock {
            try { refreshReady() }
            catch (_: Exception) { publish(ModelDeliveryStatus.ERROR, error = "Stored model verification failed. Its file was preserved.") }
        }
    }

    suspend fun download() {
        val requestedGeneration = guard.generation.get()
        withContext(Dispatchers.IO) {
        startup.await()
        guard.mutex.withLock {
            val generation = requestedGeneration
            val coroutine = currentCoroutineContext()
            val check = { coroutine.ensureActive(); if (guard.generation.get() != generation) throw ModelDeliveryCancelled() }
            var response: ModelDeliveryResponse? = null
            try {
                check()
                secureDirectory(create = true)
                if (readyIsVerified(check)) { publish(ModelDeliveryStatus.READY, descriptor.size); return@withLock }
                val partial = owned(descriptor.fileName + ".part")
                val metadataFile = owned(descriptor.fileName + ".download.json")
                var metadata = readMetadata(metadataFile)
                var offset = partial.takeIf { it.isFile }?.length() ?: 0
                if (offset == descriptor.size && metadata?.matches(descriptor) == true) {
                    verifyAndInstall(partial, metadataFile, check)
                    return@withLock
                }
                if (offset > descriptor.size || metadata?.matches(descriptor) != true || metadata?.etag == null) {
                    clearPartial(partial, metadataFile)
                    offset = 0; metadata = null
                }
                require(freeBytes() >= descriptor.size - offset + reserveBytes) { "More free space is needed for the model and safety reserve." }
                publish(ModelDeliveryStatus.DOWNLOADING, offset)
                check()
                val call = transport.newCall(offset, metadata?.etag)
                guard.activeCall = call
                check()
                val incoming = call.execute()
                response = incoming
                check()
                if (incoming.code !in setOf(200, 206)) throw ModelDeliveryHttpError(incoming.code)
                val tag = strongETag(incoming.etag)
                if (incoming.code == 206) {
                    require(offset > 0 && metadata?.etag != null && tag == metadata?.etag) { "The server did not confirm the retained partial model." }
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(incoming.contentRange ?: "")
                        ?: throw IOException("The model range response is invalid.")
                    require(range.groupValues[1].toLongOrNull() == offset && range.groupValues[2].toLongOrNull() == descriptor.size - 1 &&
                        range.groupValues[3].toLongOrNull() == descriptor.size) { "The model range does not match the pinned file." }
                } else if (offset > 0) {
                    // If-Range ignored or changed: restart this owned partial, preserving the installed file.
                    offset = 0
                }
                require(incoming.length < 0 || incoming.length == descriptor.size - offset) { "The model response length does not match the pinned file." }
                val next = ModelDeliveryMetadata(1, descriptor.id, descriptor.sha256, descriptor.size, tag)
                saveMetadata(metadataFile, next)
                var received = offset
                FileOutputStream(partial, offset > 0).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        check()
                        val count = incoming.stream.read(buffer)
                        if (count < 0) break
                        if (received + count > descriptor.size) throw ModelValidationException("The download exceeds the pinned file size.")
                        output.write(buffer, 0, count)
                        received += count
                        publish(ModelDeliveryStatus.DOWNLOADING, received)
                    }
                    output.flush(); output.fd.sync()
                }
                incoming.close(); response = null
                check()
                if (received != descriptor.size) throw IOException("The model download is incomplete; its verified partial can be resumed.")
                verifyAndInstall(partial, metadataFile, check)
            } catch (error: Exception) {
                val cancelled = error is ModelDeliveryCancelled || guard.generation.get() != generation || !coroutine.isActive
                val partialSize = runCatching { owned(descriptor.fileName + ".part").length().coerceIn(0, descriptor.size) }.getOrDefault(0)
                if (error is ModelValidationException) runCatching { clearPartial(owned(descriptor.fileName + ".part"), owned(descriptor.fileName + ".download.json")) }
                publish(if (cancelled) ModelDeliveryStatus.MISSING else ModelDeliveryStatus.ERROR, partialSize,
                    if (cancelled) null else when (error) {
                        is ModelValidationException -> error.message
                        is ModelDeliveryHttpError -> "The official model server returned HTTP ${error.code}. Try again later."
                        is IllegalArgumentException -> error.message
                        else -> "Model download failed. Check connectivity and storage, then retry."
                    })
                if (!coroutine.isActive) throw error
            } finally {
                runCatching { response?.close() }
                runCatching { guard.activeCall?.cancel() }
                guard.activeCall = null
            }
        }
        }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        guard.generation.incrementAndGet()
        guard.activeCall?.cancel()
        guard.mutex.withLock { refreshReady(); Unit }
    }
    suspend fun remove() = withContext(Dispatchers.IO) {
        guard.generation.incrementAndGet()
        guard.activeCall?.cancel()
        startup.await()
        guard.mutex.withLock {
            secureDirectory(create = false)
            val files = listOf(descriptor.fileName, descriptor.fileName + ".part", descriptor.fileName + ".download.json",
                descriptor.fileName + ".download.json.bak", descriptor.fileName + ".download.json.new").map(::owned)
            try {
                for (file in files) if (file.exists() && !file.delete()) throw IOException("A model file could not be removed. Retry removal.")
                publish(ModelDeliveryStatus.MISSING)
            } finally {
                if (files.any { it.exists() }) refreshReady()
            }
        }
    }
    suspend fun verifiedModelPath(): String? = withContext(Dispatchers.IO) {
        startup.await()
        guard.mutex.withLock {
            if (refreshReady(preserveFailure = true)) owned(descriptor.fileName).absolutePath else null
        }
    }

    private fun refreshReady(preserveFailure: Boolean = false): Boolean {
        secureDirectory(create = false)
        if (readyIsVerified()) { publish(ModelDeliveryStatus.READY, descriptor.size); return true }
        val ready = owned(descriptor.fileName)
        if (ready.exists()) publish(ModelDeliveryStatus.ERROR, error = "Stored model verification failed. Its file was preserved.")
        else if (!preserveFailure || mutableState.value.status != ModelDeliveryStatus.ERROR) {
            publish(ModelDeliveryStatus.MISSING, owned(descriptor.fileName + ".part").takeIf { it.isFile }?.length()?.coerceIn(0, descriptor.size) ?: 0)
        }
        return false
    }
    private fun readyIsVerified(check: () -> Unit = {}): Boolean {
        val file = owned(descriptor.fileName)
        if (!file.exists()) return false
        publish(ModelDeliveryStatus.VERIFYING, file.length().coerceAtMost(descriptor.size))
        return try { ModelFileValidation.verify(file, descriptor, check); true }
        catch (error: ModelValidationException) { false }
    }
    private fun verifyAndInstall(partial: File, metadata: File, check: () -> Unit) {
        publish(ModelDeliveryStatus.VERIFYING, descriptor.size)
        ModelFileValidation.verify(partial, descriptor, check)
        FileOutputStream(partial, true).use { it.fd.sync() }
        check()
        Os.rename(partial.absolutePath, owned(descriptor.fileName).absolutePath)
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
        AtomicFile(metadata).delete()
        publish(ModelDeliveryStatus.READY, descriptor.size)
    }
    private fun secureDirectory(create: Boolean) {
        descriptor.validateFileMetadata()
        require(!Files.isSymbolicLink(directory.toPath())) { "The private model directory is not valid." }
        if (create) require(directory.isDirectory || directory.mkdirs()) { "The private model directory could not be created." }
        require(!directory.exists() || directory.isDirectory) { "The private model directory is not valid." }
    }
    private fun owned(name: String): File {
        val file = File(directory, name)
        require(file.parentFile == directory && !Files.isSymbolicLink(file.toPath())) { "The private model path is not valid." }
        return file
    }
    private fun clearPartial(partial: File, metadata: File) {
        if (partial.exists() && !partial.delete()) throw IOException("The retained partial could not be restarted.")
        AtomicFile(metadata).delete()
    }
    private fun readMetadata(file: File): ModelDeliveryMetadata? {
        owned(file.name + ".bak"); owned(file.name + ".new")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            val bytes = AtomicFile(file).openRead().use { input ->
                val data = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(data.size() + n <= 16 * 1024); data.write(buffer, 0, n) }
                data.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            Json.decodeFromJsonElement<ModelDeliveryMetadata>(AcademicPlanCodec.parseStrictJson(text, 16 * 1024)).also { require(it.version == 1 && (it.etag == null || strongETag(it.etag) != null)) }
        } catch (_: Exception) { null }
    }
    private fun saveMetadata(file: File, metadata: ModelDeliveryMetadata) {
        owned(file.name + ".bak"); owned(file.name + ".new")
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(Json.encodeToString(metadata).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun strongETag(value: String?): String? = value?.takeIf { it.length in 2..256 && it.first() == '"' && it.last() == '"' && it.all { char -> char.code in 33..126 } }
    private fun publish(status: ModelDeliveryStatus, received: Long = 0, error: String? = null) {
        mutableState.value = ModelDeliveryState(status, received, descriptor.size, error)
    }

    companion object {
        private fun catalogModel(descriptor: ModelDescriptor): ModelDescriptor {
            require(descriptor in ModelCatalog.models) { "Choose a supported author model from the catalog." }
            return descriptor
        }
    }
}

@Serializable
private data class ModelDeliveryMetadata(val version: Int, val id: String, val sha256: String, val size: Long, val etag: String? = null) {
    fun matches(descriptor: ModelDescriptor) = id == descriptor.id && sha256 == descriptor.sha256 && size == descriptor.size
}
private class ModelDeliveryHttpError(val code: Int) : IOException()
private class ModelDeliveryGuard {
    val mutex = Mutex()
    val generation = AtomicLong()
    @Volatile var activeCall: ModelDeliveryCall? = null
}
private object ModelDeliveryGuards {
    private val guards = ConcurrentHashMap<String, ModelDeliveryGuard>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<ModelDeliveryState>>()
    fun forPath(path: String): ModelDeliveryGuard = guards.computeIfAbsent(path) { ModelDeliveryGuard() }
    fun state(path: String, model: ModelDescriptor): MutableStateFlow<ModelDeliveryState> =
        states.computeIfAbsent("$path:${model.id}:${model.sha256}") { MutableStateFlow(ModelDeliveryState(total = model.size)) }
}
