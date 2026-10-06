package ch.habits.android.localai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.calendar.CalendarConnection
import ch.habits.android.data.WorkspaceRepository
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real report acceptance on an operator-owned fixture device. Prints no source text. */
@RunWith(AndroidJUnit4::class)
class RecordedReportInferenceTest {
    @Test fun recordedReportProducesOnlyAcceptedOriginalFacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit recordedReportAcceptance fixture opt-in", args.getString("recordedReportAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val descriptor = ModelCatalog.models.single { it.id == args.getString("selectedModelID") }
        val path = args.getString("llamaModelPath") ?: error("Verified model path required")
        ModelFileValidation.verify(File(path), descriptor)
        val file = File(context.filesDir, "workspace.json")
        assertTrue("Use a prepared fixture workspace", file.isFile)
        val before = file.readBytes()
        val snapshot = WorkspaceRepository(file).state.value.snapshot
        val calendar = CalendarConnection(context)
        calendar.refresh()
        val zone = ZoneId.systemDefault()
        val language = snapshot.preferences.language.let { if (it == "system") Locale.getDefault().language else it }
        val facts = ReportFacts.build("morning", snapshot, calendar.state.value, LocalDate.now(zone), zone, language)
        assertTrue(facts.facts.isNotEmpty())
        println("REPORT_INPUT model=${descriptor.id} facts=${facts.facts.size} " +
            "systemUtf8Bytes=${facts.systemPrompt.toByteArray(Charsets.UTF_8).size} " +
            "userUtf8Bytes=${facts.userPrompt.toByteArray(Charsets.UTF_8).size} " +
            "systemCodePoints=${facts.systemPrompt.codePointCount(0, facts.systemPrompt.length)} " +
            "userCodePoints=${facts.userPrompt.codePointCount(0, facts.userPrompt.length)}")
        val handle = NativeLlama.load(path)
        val start = System.nanoTime()
        try {
            val raw = NativeLlama.generate(handle, facts.systemPrompt, facts.userPrompt, facts.grammar,
                if (descriptor.reasoning) 2048 else 128, descriptor.reasoning)
            val selection = ReportSelectionCodec.parse(raw, facts)
            assertTrue(selection.isNotEmpty())
            assertTrue(selection.all { it in facts.facts })
            println("REPORT_ACCEPTANCE model=${descriptor.id} facts=${facts.facts.size} selected=${selection.map { it.id }} elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
        } catch (failure: Exception) {
            val outcome = if (failure.message == "Local inference timed out.") "time_limit" else failure.javaClass.simpleName
            println("REPORT_FAILURE model=${descriptor.id} outcome=$outcome elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            throw failure
        } finally {
            NativeLlama.release(handle)
            assertArrayEquals("Inference must preserve planning files", before, file.readBytes())
        }
    }
}
