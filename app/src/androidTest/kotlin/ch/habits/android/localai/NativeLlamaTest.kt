package ch.habits.android.localai

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Supply llamaModelPath for real Liquid inference and llamaReasoning=true for the balanced model. */
@RunWith(AndroidJUnit4::class)
class NativeLlamaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reasoning get() = InstrumentationRegistry.getArguments().getString("llamaReasoning").equals("true", ignoreCase = true)
    private val generationBudget get() = if (reasoning) 2048 else 64
    private val oneFactGrammar = """
        root ::= "{\"factIds\":[\"task:A\"]}"
    """.trimIndent()
    private val system = "Select recorded fact identifiers only. Return the requested JSON object."
    private val facts = """{"facts":[{"id":"task:A","title":"Statistik 📚 / 統計","kind":"exam"}]}"""

    private fun modelPath(): String {
        val path = InstrumentationRegistry.getArguments().getString("llamaModelPath").orEmpty()
        assumeTrue("Actual model test requires llamaModelPath argument", path.isNotBlank())
        assertTrue("Provided model must be readable by the test app", File(path).canRead())
        assertTrue("64-bit native inference must be available", NativeLlama.supported())
        return path
    }

    private fun failure(block: () -> Unit): IllegalStateException {
        try {
            block()
        } catch (error: IllegalStateException) {
            return error
        }
        throw AssertionError("Expected a bounded native failure")
    }

    @Test fun supportMatchesProcessAbiAndBadFilesDoNotLeakPrivateDetails() {
        assertEquals(Process.is64Bit(), NativeLlama.supported())
        if (!NativeLlama.supported()) return
        assertTrue(NativeLlama.version().contains("5f436dddb440a288ee5611d7d1eca564a6aca9f4"))
        val name = "private-model-${UUID.randomUUID()}.gguf"
        val missing = failure { NativeLlama.load(File(context.cacheDir, name).absolutePath) }
        assertFalse(missing.message.orEmpty().contains(name))
        val corrupt = File(context.cacheDir, name)
        try {
            corrupt.writeText("Private model contents must never appear in diagnostics")
            val error = failure { NativeLlama.load(corrupt.absolutePath) }
            assertFalse(error.message.orEmpty().contains(name))
            assertFalse(error.message.orEmpty().contains("Private model contents"))
            assertTrue(error.message.orEmpty().length <= 128)
        } finally {
            corrupt.delete()
        }
        NativeLlama.cancel(Long.MAX_VALUE)
        NativeLlama.release(Long.MAX_VALUE)
    }

    @Test fun actualGrammarInferenceRepeatsAndReloadsWithOpaqueHandles() {
        val path = modelPath()
        val handle = NativeLlama.load(path)
        try {
            repeat(2) {
                val output = NativeLlama.generate(handle, system, facts, oneFactGrammar, generationBudget, reasoning)
                assertFalse("Reasoning must never reach the caller", output.contains("<think>") || output.contains("</think>"))
                assertTrue(output.encodeToByteArray().size <= 4096)
                val json = JSONObject(output)
                assertEquals(setOf("factIds"), json.keys().asSequence().toSet())
                val ids = json.getJSONArray("factIds")
                assertEquals(1, ids.length())
                assertEquals("task:A", ids.getString(0))
            }
            failure { NativeLlama.load(path) }
            // CJK input is within byte/character guards but exceeds the complete templated token budget.
            val overflow = failure { NativeLlama.generate(handle, system, "猫".repeat(8000), oneFactGrammar, generationBudget, reasoning) }
            assertEquals("Local inference input is too large.", overflow.message)
            val badGrammar = failure { NativeLlama.generate(handle, system, facts, "root ::= secret-private-literal", generationBudget, reasoning) }
            assertEquals("Local inference grammar is invalid.", badGrammar.message)
        } finally {
            NativeLlama.release(handle)
        }
        NativeLlama.release(handle)
        failure { NativeLlama.generate(handle, system, facts, oneFactGrammar, generationBudget, reasoning) }
        val second = NativeLlama.load(path)
        try {
            assertNotEquals(handle, second)
            assertEquals("task:A", JSONObject(NativeLlama.generate(second, system, facts, oneFactGrammar, generationBudget, reasoning))
                .getJSONArray("factIds").getString(0))
        } finally {
            NativeLlama.release(second)
        }
    }

    @Test fun reservedLoadCanBeCancelledAndReleasedBeforeAnyFileIO() {
        assumeTrue("64-bit native library required", NativeLlama.supported())
        val handle = NativeLlama.startLoad()
        val missingPath = File(context.cacheDir, "private-cancelled-load-${UUID.randomUUID()}.gguf").absolutePath
        try {
            assertEquals("Local model is unavailable.", failure {
                NativeLlama.generate(handle, system, facts, oneFactGrammar, generationBudget, reasoning)
            }.message)
            NativeLlama.cancel(handle)
            // Cancellation must win over the deliberately invalid file path: no loading work should begin.
            assertEquals("Local inference was cancelled.", failure {
                NativeLlama.finishLoad(handle, missingPath)
            }.message)
        } finally {
            NativeLlama.release(handle)
        }
        assertEquals("Local model is unavailable.", failure {
            NativeLlama.finishLoad(handle, missingPath)
        }.message)
        val replacement = NativeLlama.startLoad()
        assertNotEquals(handle, replacement)
        NativeLlama.release(replacement)
    }

    @Test fun inProgressModelLoadRetainsStateAcrossConcurrentCancellationAndRelease() {
        val path = modelPath()
        val handle = NativeLlama.startLoad()
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<Throwable?> {
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < startDeadline) {
                try {
                    NativeLlama.finishLoad(handle, path)
                    return@submit null
                } catch (error: IllegalStateException) {
                    if (error.message != "Local inference is busy.") return@submit error
                    Thread.sleep(10)
                } catch (error: Throwable) {
                    return@submit error
                }
            }
            IllegalStateException("Native load worker could not start.")
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var running = false
            while (System.nanoTime() < deadline && !result.isDone) {
                val probe = failure { NativeLlama.startLoad() }
                if (probe.message == "Local inference is busy.") {
                    running = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue("Actual model-load work must hold the native guard", running)
            val start = System.nanoTime()
            NativeLlama.cancel(handle)
            assertTrue("Cancelling loading must not await model IO", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            NativeLlama.release(handle)
            val error = result.get(15, TimeUnit.SECONDS)
            assertTrue("Loading must terminate safely while its owner releases", error is IllegalStateException)
            assertEquals("Local inference was cancelled.", error?.message)
            val replacement = NativeLlama.startLoad()
            NativeLlama.release(replacement)
        } finally {
            NativeLlama.cancel(handle)
            NativeLlama.release(handle)
            worker.shutdownNow()
            worker.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    @Test fun cancellationIsStickyAndConcurrentReleaseDoesNotFreeInFlightModel() {
        val path = modelPath()
        val handle = NativeLlama.load(path)
        val worker = Executors.newSingleThreadExecutor()
        // A long constrained response keeps actual decode active until cancellation; no fake inference backend.
        val longGrammar = """
            root ::= "{\"factIds\":[" id ("," id){127} "]}"
            id ::= "\"task:A\""
        """.trimIndent()
        val result = worker.submit<Throwable?> {
            val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < startDeadline) {
                try {
                    NativeLlama.generate(handle, system, facts, longGrammar, if (reasoning) 2048 else 256, reasoning)
                    return@submit null
                } catch (error: IllegalStateException) {
                    // The passive load probe can briefly own the guard before the worker starts.
                    if (error.message != "Local inference is busy.") return@submit error
                    Thread.sleep(10)
                } catch (error: Throwable) {
                    return@submit error
                }
            }
            IllegalStateException("Native worker could not start.")
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var running = false
            while (System.nanoTime() < deadline && !result.isDone) {
                val probe = failure { NativeLlama.load(path) }
                if (probe.message == "Local inference is busy.") {
                    running = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue("Actual native generation must hold the serialization guard", running)
            assertEquals("Local inference is busy.", failure {
                NativeLlama.generate(handle, system, facts, oneFactGrammar, generationBudget, reasoning)
            }.message)
            val start = System.nanoTime()
            NativeLlama.cancel(handle)
            assertTrue("Cancellation must not wait for generation", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            NativeLlama.release(handle)
            val error = result.get(15, TimeUnit.SECONDS)
            assertTrue("In-flight generation must terminate safely after cancel/release", error is IllegalStateException)
            assertEquals("Local inference was cancelled.", error?.message)
            val replacement = NativeLlama.load(path)
            try {
                NativeLlama.cancel(replacement)
                assertEquals("Local inference was cancelled.", failure {
                    NativeLlama.generate(replacement, system, facts, oneFactGrammar, generationBudget, reasoning)
                }.message)
            } finally {
                NativeLlama.release(replacement)
            }
        } finally {
            NativeLlama.cancel(handle)
            NativeLlama.release(handle)
            worker.shutdownNow()
            worker.awaitTermination(15, TimeUnit.SECONDS)
        }
    }
}
