package ch.habits.android.localai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real catalog weights and JNI inference. No download, fake engine or planning writes. */
@RunWith(AndroidJUnit4::class)
class LocalAIControllerTest {
    @Test fun realSelectionStaleRefusalCancellationAndReloadPreserveRecordedData() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val suppliedPath = args.getString("llamaModelPath").orEmpty()
        assumeTrue("Real controller test requires llamaModelPath", suppliedPath.isNotBlank())
        val requestedID = args.getString("selectedModelID") ?: ModelCatalog.starter.id
        val descriptor = ModelCatalog.models.firstOrNull { it.id == requestedID }
            ?: throw AssertionError("selectedModelID must be an approved catalog model")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = installOnlyIfMissing(context, File(suppliedPath), descriptor)
        val before = workspaceDigests(context)
        val controller = LocalAIController(context)
        val originalModelID = controller.selected.value.id
        assertTrue("The selected real model must be supported by this test device", controller.hardwareSupported(descriptor))
        val day = LocalDate.now(ZoneOffset.UTC)
        val source = WorkspaceSnapshot(revision = 17, tasks = listOf(WorkTask(
            id = "ac000000-0000-0000-0000-000000000001", title = "Review statistics",
            dueDate = day.toString(), dueTime = "18:00", sourceZone = "UTC", minutes = 25)))
        val facts = ReportFacts.build("morning", source, null, day, ZoneOffset.UTC, "en")
        val changed = ReportFacts.build("morning", source.copy(revision = 18,
            tasks = source.tasks.map { it.copy(title = "Review statistics and probability") }), null, day, ZoneOffset.UTC, "en")
        val originalFacts = facts.facts.toList()
        assertTrue("The synthetic report needs at least one recorded fact", originalFacts.isNotEmpty())

        try {
            withTimeout(480_000) {
                withContext(Dispatchers.Main) { controller.setForeground(true) }
                controller.selectModel(descriptor.id)
                controller.refreshModel()
                assertEquals(ModelDeliveryStatus.READY, controller.store.state.value.status)
                assertFalse("Verified on disk does not mean loaded", controller.state.value.loaded)

                // Stay on Main: cancel the old load while its IO continuation is
                // suspended, then attempt new admission before old finally can run.
                val cancelledLoad = withContext(Dispatchers.Main) {
                    val old = launch(start = CoroutineStart.UNDISPATCHED) { controller.load() }
                    assertEquals(LocalAIStatus.LOADING, controller.state.value.status)
                    old.cancel()
                    assertTrue(old.isCancelled)
                    assertFalse("The cancelled load has not finished its IO cleanup", old.isCompleted)
                    try {
                        controller.load()
                        fail("A new load must wait for the cancelled caller's cleanup")
                    } catch (expected: IllegalStateException) {
                        assertTrue(expected.message.orEmpty().contains("Wait"))
                    }
                    old
                }
                cancelledLoad.join()
                awaitUnloaded(controller)
                controller.load()
                assertReady(controller)
                controller.generate(facts) { facts.fingerprint }
                assertOriginalSelection(controller, facts)

                // A stale request is rejected before native generation starts.
                val validState = controller.state.value
                try {
                    controller.generate(facts) { changed.fingerprint }
                    fail("Stale source facts must be refused")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("facts changed", ignoreCase = true))
                }
                assertEquals(validState, controller.state.value)

                // The first source read is current; the post-inference read is stale.
                // Inference remains real; only the source revision changes deterministically.
                val reads = AtomicInteger()
                controller.generate(facts) { if (reads.getAndIncrement() == 0) facts.fingerprint else changed.fingerprint }
                assertTrue("The controller must recheck the current source after inference", reads.get() >= 2)
                assertReady(controller, allowError = true)
                assertTrue(controller.state.value.error.orEmpty().contains("facts changed", ignoreCase = true))
                assertFallback(controller, facts, originalFacts)

                supervisorScope {
                    val generation = startRealGeneration(controller, facts)
                    withContext(Dispatchers.Main) { controller.setForeground(false) }
                    generation.join()
                    assertTrue("Backgrounding must cancel the caller's generation", generation.isCancelled)
                    awaitUnloaded(controller)
                }
                assertFallback(controller, facts, originalFacts)
                try {
                    controller.load()
                    fail("A background controller must not load weights")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("Open the app"))
                }
                withContext(Dispatchers.Main) { controller.setForeground(true) }
                controller.load()
                assertReady(controller)

                supervisorScope {
                    val generation = startRealGeneration(controller, facts)
                    withContext(Dispatchers.Main) { controller.cancel() }
                    generation.join()
                    assertTrue("Explicit cancellation must cancel the generation", generation.isCancelled)
                    awaitUnloaded(controller)
                }
                assertFallback(controller, facts, originalFacts)
                controller.load()
                controller.generate(facts) { facts.fingerprint }
                assertOriginalSelection(controller, facts)

                // Cancel the unload caller while native generation owns the mutex.
                // Controller-owned NonCancellable cleanup must still release the handle.
                supervisorScope {
                    val generation = startRealGeneration(controller, facts)
                    val unloading = launch(start = CoroutineStart.UNDISPATCHED) { controller.unload() }
                    unloading.cancelAndJoin()
                    generation.join()
                    awaitUnloaded(controller)
                    assertTrue("The unload caller was cancelled", unloading.isCancelled)
                }
                controller.load()
                controller.generate(facts) { facts.fingerprint }
                assertOriginalSelection(controller, facts)
                assertEquals(originalFacts, facts.facts)
            }
        } finally {
            withContext(NonCancellable) {
                controller.unload()
                withContext(Dispatchers.Main) { controller.setForeground(false) }
                awaitUnloaded(controller)
                controller.selectModel(originalModelID)
                assertEquals("Local model operations must not change primary planning files", before, workspaceDigests(context))
                assertTrue("The installed model must be preserved", model.isFile && model.length() == descriptor.size)
            }
        }
    }

    private suspend fun CoroutineScope.startRealGeneration(controller: LocalAIController, facts: ReportFactSet): Job {
        val job = launch(Dispatchers.Main.immediate) { controller.generate(facts) { facts.fingerprint } }
        withTimeout(20_000) {
            controller.state.first { it.status == LocalAIStatus.GENERATING }
            // NativeLlamaTest covers the actual work mutex. A second native load
            // here would perturb its try-lock admission and could fail generation.
            delay(25)
            assertTrue("Real generation must still be running before cancellation", job.isActive)
            assertEquals(LocalAIStatus.GENERATING, controller.state.value.status)
        }
        return job
    }

    private fun assertReady(controller: LocalAIController, allowError: Boolean = false) {
        assertEquals(LocalAIStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.loaded)
        assertFalse(controller.state.value.busy)
        if (!allowError) assertNull(controller.state.value.error)
    }

    private fun assertOriginalSelection(controller: LocalAIController, facts: ReportFactSet) {
        assertReady(controller)
        val result = controller.state.value
        assertEquals(facts.fingerprint, result.selectionFingerprint)
        assertTrue(result.selection.isNotEmpty())
        assertTrue(result.selection.size <= ReportSelectionCodec.MAX_SELECTED)
        assertEquals(result.selection.size, result.selection.map { it.id }.toSet().size)
        assertTrue("Only original recorded fact objects can be selected", result.selection.all { selected -> facts.facts.any { it == selected } })
        assertNotNull(result.generationMillis)
    }

    private fun assertFallback(controller: LocalAIController, facts: ReportFactSet, original: List<ReportFact>) {
        assertNull(controller.state.value.selectionFingerprint)
        assertTrue(controller.state.value.selection.isEmpty())
        assertEquals("The complete recorded-facts fallback remains unchanged", original, facts.facts)
    }

    private suspend fun awaitUnloaded(controller: LocalAIController) = withTimeout(30_000) {
        controller.state.first { it.status == LocalAIStatus.UNLOADED && !it.loaded && !it.busy }
        // Join controller-owned teardown before another operation is admitted.
        controller.unload()
    }

    private suspend fun installOnlyIfMissing(context: Context, source: File, descriptor: ModelDescriptor): File = withContext(Dispatchers.IO) {
        assertTrue("llamaModelPath must be readable by this app", source.isFile && source.canRead())
        val directory = File(context.noBackupFilesDir, "models")
        val installed = File(directory, descriptor.fileName)
        if (!installed.exists()) {
            ModelFileValidation.verify(source, descriptor)
            check(directory.isDirectory || directory.mkdirs())
            val partial = File(directory, "controller-test-${UUID.randomUUID()}.part")
            try {
                source.copyTo(partial, overwrite = false)
                ModelFileValidation.verify(partial, descriptor)
                if (!installed.exists()) check(partial.renameTo(installed)) { "Verified test model could not be installed" }
            } finally { partial.delete() }
        }
        ModelFileValidation.verify(installed, descriptor)
        installed
    }

    private fun workspaceDigests(context: Context): Map<String, String?> = listOf("workspace.json", "workspace.json.backup").associateWith { name ->
        val file = File(context.filesDir, name)
        if (!file.exists()) null else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
