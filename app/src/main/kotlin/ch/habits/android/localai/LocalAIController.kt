package ch.habits.android.localai

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class LocalAIStatus { UNLOADED, LOADING, READY, GENERATING, ERROR, UNLOADING }

data class LocalAIState(
    val status: LocalAIStatus = LocalAIStatus.UNLOADED,
    val loaded: Boolean = false,
    val error: String? = null,
    val selectionFingerprint: String? = null,
    val selection: List<ReportFact> = emptyList(),
    val generationMillis: Long? = null,
) {
    val busy: Boolean get() = status in setOf(LocalAIStatus.LOADING, LocalAIStatus.GENERATING, LocalAIStatus.UNLOADING)
}

/** Model bytes, native memory and derived suggestions remain outside primary records. */
class LocalAIController(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("local-ai-device", Context.MODE_PRIVATE)
    private val stores by lazy { ModelCatalog.models.associate { it.id to ModelStore(app, it) } }
    private val mutableSelected = MutableStateFlow(ModelCatalog.models.firstOrNull { it.id == runCatching { preferences.getString("model", null) }.getOrNull() } ?: ModelCatalog.starter)
    val selected: StateFlow<ModelDescriptor> = mutableSelected.asStateFlow()
    val store: ModelStore get() = stores.getValue(selected.value.id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalAIState())
    val state: StateFlow<LocalAIState> = mutableState.asStateFlow()
    @Volatile private var handle = 0L
    @Volatile private var epoch = 0L
    @Volatile private var foreground = false
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeFingerprint: String? = null
    @Volatile private var storeCancellation: Job? = null
    @Volatile private var teardownJob: Job? = null
    private fun operationPending(): Boolean = state.value.busy || activeJob?.isCompleted == false || teardownJob?.isCompleted == false

    fun hardwareSupported(descriptor: ModelDescriptor = selected.value): Boolean {
        val manager = app.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return NativeLlama.supported() && !manager.isLowRamDevice && memory.totalMem >= descriptor.minRamBytes
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            cancelOperation()
            requestRelease()
        }
    }

    suspend fun selectModel(id: String) {
        check(!operationPending()) { "Wait for the current local AI operation to finish." }
        val descriptor = ModelCatalog.models.firstOrNull { it.id == id } ?: error("Unknown local model.")
        if (descriptor == selected.value) return
        check(hardwareSupported(descriptor)) { "This model needs more memory than this device provides." }
        unload()
        preferences.edit().putString("model", descriptor.id).apply()
        mutableSelected.value = descriptor
    }

    suspend fun refreshModel() { store.verifiedModelPath() }

    suspend fun download() {
        check(hardwareSupported()) { "This device does not support the local model. Recorded-facts reports remain available." }
        storeCancellation?.join()
        store.download()
    }

    suspend fun load() {
        check(!operationPending()) { "Wait for the current local AI operation to finish." }
        check(foreground) { "Open the app before loading the model." }
        check(hardwareSupported()) { "Local AI requires a supported device with sufficient memory." }
        val memory = ActivityManager.MemoryInfo().also(app.getSystemService(ActivityManager::class.java)::getMemoryInfo)
        check(!memory.lowMemory && memory.availMem >= selected.value.size + 384L * 1024 * 1024) {
            "Not enough memory is available to load the model. Close other apps and retry."
        }
        val job = currentCoroutineContext()[Job]
        val request = ++epoch
        activeJob = job
        mutableState.value = LocalAIState(LocalAIStatus.LOADING)
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (handle != 0L) { NativeLlama.release(handle); handle = 0L }
                    val path = store.verifiedModelPath() ?: error("Download and verify the model before loading it.")
                    currentCoroutineContext().ensureActive()
                    if (!foreground || epoch != request) throw CancellationException("Model load cancelled")
                    val loaded = NativeLlama.startLoad()
                    handle = loaded
                    try {
                        currentCoroutineContext().ensureActive()
                        if (!foreground || epoch != request) throw CancellationException("Model load cancelled")
                        NativeLlama.finishLoad(loaded, path)
                        currentCoroutineContext().ensureActive()
                        if (!foreground || epoch != request) throw CancellationException("Model load cancelled")
                        handle = loaded
                        mutableState.value = LocalAIState(LocalAIStatus.READY, loaded = true)
                    } catch (failure: Exception) {
                        NativeLlama.release(loaded)
                        if (handle == loaded) handle = 0L
                        throw failure
                    }
                }
            }
        } catch (failure: CancellationException) {
            ++epoch
            if (handle != 0L) NativeLlama.cancel(handle)
            mutableState.value = LocalAIState(LocalAIStatus.UNLOADING)
            requestRelease()
            throw failure
        } catch (_: Exception) {
            mutableState.value = LocalAIState(LocalAIStatus.ERROR, error = "The model could not be loaded. Recorded facts remain available; unload or retry.")
        } finally {
            if (activeJob == job) activeJob = null
            if (mutableState.value.status == LocalAIStatus.LOADING && epoch != request) {
                mutableState.value = LocalAIState(if (handle == 0L) LocalAIStatus.UNLOADED else LocalAIStatus.READY, handle != 0L)
            }
        }
    }

    suspend fun generate(facts: ReportFactSet, currentFingerprint: () -> String) {
        check(!operationPending()) { "Wait for the current local AI operation to finish." }
        check(foreground && handle != 0L) { "Load the local model while the app is open first." }
        if (facts.facts.isEmpty()) return
        check(currentFingerprint() == facts.fingerprint) { "Recorded facts changed. Review the current report and retry." }
        val job = currentCoroutineContext()[Job]
        val request = ++epoch
        activeJob = job
        activeFingerprint = facts.fingerprint
        mutableState.value = mutableState.value.copy(status = LocalAIStatus.GENERATING, error = null,
            selectionFingerprint = null, selection = emptyList(), generationMillis = null)
        val started = System.nanoTime()
        try {
            val raw = mutex.withLock {
                currentCoroutineContext().ensureActive()
                val loaded = handle
                check(loaded != 0L && foreground && epoch == request)
                coroutineScope {
                    val native = async(Dispatchers.IO) { NativeLlama.generate(loaded, facts.systemPrompt, facts.userPrompt, facts.grammar, if (selected.value.reasoning) 2048 else 128, selected.value.reasoning) }
                    try { withTimeout(95_000) { native.await() } }
                    finally { if (!native.isCompleted) NativeLlama.cancel(loaded) }
                }
            }
            currentCoroutineContext().ensureActive()
            if (epoch != request || !foreground) return
            if (currentFingerprint() != facts.fingerprint) {
                mutableState.value = LocalAIState(LocalAIStatus.READY, loaded = true, error = "Recorded facts changed. Review the current report and retry.")
                return
            }
            val selection = ReportSelectionCodec.parse(raw, facts)
            mutableState.value = LocalAIState(LocalAIStatus.READY, loaded = true,
                selectionFingerprint = facts.fingerprint, selection = selection,
                generationMillis = (System.nanoTime() - started) / 1_000_000)
        } catch (failure: CancellationException) {
            ++epoch
            if (handle != 0L) NativeLlama.cancel(handle)
            requestRelease()
            throw failure
        } catch (failure: Exception) {
            ++epoch
            if (handle != 0L) NativeLlama.cancel(handle)
            val message = when {
                failure is ReportSelectionException -> "The model returned an invalid selection. Try again or use the recorded-facts report."
                failure.message == "Local inference timed out." -> "Local AI reached its time limit. Try the 230M model or use the recorded-facts report."
                failure.message == "Local inference input is too large." -> "This report exceeds the model's input limit. Use the recorded-facts report or choose the larger model."
                failure.message == "Local inference output is incomplete." -> "The model did not finish its answer. Try the 230M model or use the recorded-facts report."
                else -> "Local AI could not produce a valid selection. Reload the model or use the recorded-facts report."
            }
            mutableState.value = LocalAIState(LocalAIStatus.UNLOADING, loaded = false, error = message)
            requestRelease(message)
        } finally {
            if (activeJob == job) { activeJob = null; activeFingerprint = null }
            if (mutableState.value.status == LocalAIStatus.GENERATING && epoch != request) {
                mutableState.value = LocalAIState(if (handle != 0L) LocalAIStatus.READY else LocalAIStatus.UNLOADED, handle != 0L)
            }
        }
    }

    fun invalidateIfDifferent(fingerprint: String) {
        if (activeFingerprint != null && activeFingerprint != fingerprint) cancel()
        val current = mutableState.value
        if (current.selectionFingerprint != null && current.selectionFingerprint != fingerprint) {
            mutableState.value = current.copy(selectionFingerprint = null, selection = emptyList(), generationMillis = null)
        }
    }

    private fun cancelOperation(): Job {
        ++epoch
        if (handle != 0L) NativeLlama.cancel(handle)
        activeJob?.cancel()
        val previousStore = store
        return scope.launch { previousStore.cancel() }.also { storeCancellation = it }
    }

    /** Native cancel is sticky; a cancelled handle is released before another generation. */
    fun cancel() { cancelOperation(); requestRelease() }

    fun cancelCurrentSelection() { if (activeFingerprint != null) cancel() }

    fun cancelSelection(fingerprint: String) { if (activeFingerprint == fingerprint) cancel() }

    suspend fun unload() = withContext(NonCancellable) { cancelOperation().join(); requestRelease().join() }

    /** Coalesce cleanup so an old cancelled caller cannot release a newer model. */
    private fun requestRelease(error: String? = null): Job {
        teardownJob?.takeIf { !it.isCompleted }?.let { return it }
        return scope.launch(start = CoroutineStart.LAZY) { releaseNative(error) }.also {
            teardownJob = it
            it.start()
        }
    }

    private suspend fun releaseNative(error: String? = null) = withContext(NonCancellable) {
        mutableState.value = LocalAIState(LocalAIStatus.UNLOADING, loaded = handle != 0L, error = error)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val loaded = handle
                handle = 0L
                if (loaded != 0L) NativeLlama.release(loaded)
                mutableState.value = LocalAIState(error = error)
            }
        }
    }

    suspend fun remove() { unload(); store.remove() }
}
