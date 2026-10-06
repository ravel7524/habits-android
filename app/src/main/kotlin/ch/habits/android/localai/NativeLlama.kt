package ch.habits.android.localai

/** CPU-only JNI boundary. Opaque handles must be released; prompts and output are never logged. */
object NativeLlama {
    private val loaded = try {
        System.loadLibrary("habits_llama")
        true
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun supported(): Boolean = loaded && try {
        nativeSupported()
    } catch (_: LinkageError) {
        false
    }

    fun version(): String = if (supported()) nativeVersion() else "unavailable"

    /** Reserve a handle before blocking IO so its owner can cancel a pending load. */
    fun startLoad(): Long {
        check(supported()) { "Local inference is unavailable." }
        return nativeStartLoad()
    }

    /** Complete one reserved handle's load. Its owner must release the handle after any failure. */
    fun finishLoad(handle: Long, path: String) {
        check(supported()) { "Local inference is unavailable." }
        require(handle > 0) { "Invalid local model handle." }
        require(path.length in 1..4096 && '\u0000' !in path) { "Invalid local model path." }
        nativeFinishLoad(handle, path.encodeToByteArray())
    }

    /** Convenience for callers that do not need to publish a loading handle. */
    fun load(path: String): Long {
        val handle = startLoad()
        try {
            finishLoad(handle, path)
            return handle
        } catch (error: Throwable) {
            release(handle)
            throw error
        }
    }

    fun generate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        grammar: String,
        maxTokens: Int = 128,
        reasoning: Boolean = false,
    ): String {
        check(supported()) { "Local inference is unavailable." }
        require(handle > 0 && maxTokens in 1..(if (reasoning) 2048 else 256)) { "Invalid local inference request." }
        require(systemPrompt.length <= 16384 && userPrompt.length <= 16384 && grammar.length in 1..16384) {
            "Local inference input is too large."
        }
        // Byte arrays preserve standard UTF-8, including non-BMP characters; JNI strings use modified UTF-8.
        return nativeGenerate(
            handle, systemPrompt.encodeToByteArray(), userPrompt.encodeToByteArray(),
            grammar.encodeToByteArray(), maxTokens, reasoning,
        ).decodeToString(throwOnInvalidSequence = true)
    }

    /** Non-blocking, sticky cancellation for this handle. Release and reload to use it again. */
    fun cancel(handle: Long) {
        if (loaded && handle > 0) nativeCancel(handle)
    }

    /** Idempotent; safely cancels an in-flight generation without freeing its retained state early. */
    fun release(handle: Long) {
        if (loaded && handle > 0) nativeRelease(handle)
    }

    private external fun nativeSupported(): Boolean
    private external fun nativeVersion(): String
    private external fun nativeStartLoad(): Long
    private external fun nativeFinishLoad(handle: Long, path: ByteArray)
    private external fun nativeGenerate(
        handle: Long, systemPrompt: ByteArray, userPrompt: ByteArray, grammar: ByteArray, maxTokens: Int, reasoning: Boolean,
    ): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeRelease(handle: Long)
}
