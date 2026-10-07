package ch.habits.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ch.habits.android.data.*
import ch.habits.android.semester.*
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt-in rendered evidence from real composables; never MainActivity/service or device acceptance. */
class SemesterBoardCaptureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var output: File
    private val active = mutableStateOf<WorkspaceRepository?>(null)
    private val font = mutableStateOf(1f)
    private val repository get() = checkNotNull(active.value)
    private val start = LocalDate.now(ZoneId.of("Europe/Zurich")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(4)
    private var fixture = "empty"
    private var sourceIndex = 0
    private var sequence = 0

    @Before fun setup() {
        assumeTrue("Board screenshot review is opt-in: pass semesterBoardCapture=true",
            InstrumentationRegistry.getArguments().getString("semesterBoardCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ComponentActivity>().forEach { it.enableEdgeToEdge() }
        }
        val run = UUID.randomUUID().toString()
        directory = File(context.cacheDir, "semester-board-review-$run").also { check(it.mkdirs()) }
        output = File(context.filesDir, "semester-board-captures/$run").also { check(it.mkdirs()) }
        active.value = makeRepository()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) {
                active.value?.let { value -> key(value) { HabitsApp(value, destination = "semester") } }
            }
        }
    }
    @After fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun captureVerifiedBoardImportAndPreparationStates() {
        boardReveal("semester.empty"); capture("empty-board-light", "semester.empty", "semester.button.import")
        useFixture("populated-custom-status-current-week", SemesterBoardTestFixture.board(start = start))
        // Review the actual initial layout: no current-week jump or vertical scroll before frame 02.
        compose.onNodeWithTag("semester.title").assertTextContains("Autumn course board")
        compose.onNodeWithTag("semester.week.5").assertIsDisplayed()
        assertDescription("semester.week.5", "Current week")
        capture("populated-current-week-grid", "semester.title", "semester.grid.header", "semester.week.5", "semester.course.${course().id}")

        openWeek(1)
        compose.onNodeWithTag("semester.status.option.${SemesterBoardTestFixture.id(20)}").assertIsDisplayed()
        compose.onNodeWithTag("semester.week.notes").performScrollTo().assertTextContains(SemesterBoardTestFixture.weekNote)
        capture("week-status-and-saved-notes", "semester.week.detail", "semester.week.notes", "semester.status.option.${SemesterBoardTestFixture.id(21)}")
        val before = repository.state.value.snapshot
        val beforeBytes = sourceFile().readBytes()
        compose.onNodeWithTag("semester.week.planReview").performScrollTo().performClick()
        compose.onNodeWithTag("editor.task.title").assertTextContains("Review Statistics · Week 1")
        assertEquals(0, compose.onAllNodesWithTag("editor.task.archive").fetchSemanticsNodes().size)
        captureUnwritten("week-preparation-undated-zero-estimate-draft", before, beforeBytes, "editor.task.title", "editor.task.review")
        compose.onNodeWithTag("editor.task.review").performClick()
        compose.onNodeWithText("No deadline").assertExists()
        captureUnwritten("week-preparation-reviewed-unsaved", before, beforeBytes, "editor.task.save")
        compose.onNodeWithTag("detail.close").performClick()
        assertTrue(repository.state.value.snapshot.tasks.isEmpty())
        boardReveal("semester.course.${course().id}")
        compose.onNodeWithTag("semester.cell.${course().id}.18").performScrollTo().assertIsDisplayed()
        assertDescription("semester.cell.${course().id}.18", "exam")
        capture("later-exam-week-18-marker", "semester.week.18", "semester.cell.${course().id}.18")

        openAssessments()
        val dismissed = repository.state.value.snapshot.assessments.first { it.dismissed }
        listReveal("semester.assessment.${dismissed.id}")
        capture("all-assessments-dismissed-record-retained", "semester.assessments.list", "semester.assessment.${dismissed.id}")
        val completed = repository.state.value.snapshot.assessments.first { it.completed }
        listReveal("semester.assessment.${completed.id}"); compose.onNodeWithTag("semester.assessment.${completed.id}").performClick()
        compose.onNodeWithTag("semester.assessment.state").assertTextContains("Completed")
        compose.onNodeWithTag("assessment.complete").assertIsEnabled()
        capture("completed-assessment-reopen-action", "assessment.detail", "semester.assessment.state", "assessment.complete")

        useFixture("no-date-range-default-statuses", SemesterBoardTestFixture.board(number = 2, start = null, defaults = true))
        boardReveal("semester.dates.missing"); capture("no-source-dates-board", "semester.dates.missing")
        openWeek(1)
        val defaults = SemesterBoardProjection.statuses(repository.state.value.snapshot.semesters.single())
        compose.onNodeWithTag("semester.status.option.${defaults.last().id}").performScrollTo().assertIsDisplayed()
        capture("generated-default-status-options", "semester.week.detail", "semester.status.option.${defaults[1].id}", "semester.status.option.${defaults.last().id}")
        assertTrue(repository.state.value.snapshot.semesters.single().statuses.isEmpty())
        assertNull(repository.state.value.snapshot.semesters.single().startDate)

        useFixture("ten-courses-fifty-two-weeks-long-titles", SemesterBoardTestFixture.board(number = 3, start = start, courses = 10, weeks = 52))
        boardReveal("semester.course.${course().id}")
        compose.onNodeWithTag("semester.cell.${course().id}.52").performScrollTo()
        capture("large-board-week-52-first-course", "semester.week.52", "semester.course.${course().id}", "semester.cell.${course().id}.52")
        val lastCourse = repository.state.value.snapshot.courses.last()
        boardReveal("semester.course.${lastCourse.id}")
        assertDescription("semester.course.${lastCourse.id}", "Advanced quantitative methods")
        capture("large-board-tenth-course-and-sticky-week-header", "semester.course.${lastCourse.id}", "semester.week.52", "semester.cell.${lastCourse.id}.52")

        useFixture("dark-font13-current-week", SemesterBoardTestFixture.board(start = start), dark = true, scale = 1.3f)
        compose.onNodeWithTag("semester.title").assertTextContains("Autumn course board")
        compose.onNodeWithTag("semester.week.5").assertIsDisplayed(); assertDescription("semester.week.5", "Current week")
        capture("dark-font13-board-current-week", "semester.title", "semester.grid.header", "semester.course.${course().id}", "semester.week.5")
        openWeek(1); compose.onNodeWithTag("semester.week.notes").performScrollTo()
        capture("dark-font13-week-notes", "semester.week.detail", "semester.week.notes")

        useFixture("raw-ios-import-selection-and-receipt", null)
        revealBoardSetupAction("semester.button.import"); compose.onNodeWithTag("semester.button.import").performClick()
        revealImport("semester.import.input"); capture("raw-import-entry", "semester.import.input", "semester.import.open")
        val raw = SemesterBoardTestFixture.legacyRaw(withDates = false)
        paste(raw)
        revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        capture("raw-import-course-selection", "semester.import.course.${SemesterBoardTestFixture.id(101)}.selected", "semester.import.course.${SemesterBoardTestFixture.id(101)}")
        val importBefore = repository.state.value.snapshot
        val importBytes = sourceFile().readBytes()
        revealImport("semester.import.review"); compose.onNodeWithTag("semester.import.review").performClick(); awaitImport("Review changes")
        revealImport("semester.import.change.0")
        captureUnwritten("raw-import-real-preview-unsaved", importBefore, importBytes, "semester.import.change.0", "semester.import.change.0.details")
        revealImport("semester.import.confirm"); compose.onNodeWithTag("semester.import.confirm").assertIsEnabled()
        captureUnwritten("raw-import-confirmation-unsaved", importBefore, importBytes, "semester.import.confirm", "semester.import.cancel")
        compose.onNodeWithTag("semester.import.confirm").performClick(); awaitImport("Import receipt")
        assertEquals(1, repository.state.value.snapshot.courses.size)
        assertNull(repository.state.value.snapshot.semesters.single().startDate)
        captureReceipt("raw-import-receipt-applied", "Saved on this phone", applied = true)
        val saved = repository.state.value.snapshot
        revealImport("semester.import.another"); compose.onNodeWithTag("semester.import.another").performClick()
        paste(raw); revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        revealImport("semester.import.review"); compose.onNodeWithTag("semester.import.review").performClick(); awaitImport("Import receipt")
        assertEquals(saved, repository.state.value.snapshot)
        assertEquals(0, compose.onAllNodesWithTag("semester.import.confirm").fetchSemanticsNodes().size)
        captureReceipt("raw-import-replay-existing-receipt", "Already saved on this phone", applied = true)
        compose.onNodeWithTag("semester.import.undo").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.boardReceipts.single().state == ReceiptState.UNDONE }
        captureReceipt("raw-import-undone-receipt", "Import undone", applied = false)
        assertTrue(repository.state.value.snapshot.courses.all { it.archived })
        revealImport("semester.import.another"); compose.onNodeWithTag("semester.import.another").performClick()
        val invalidBefore = repository.state.value.snapshot
        revealImport("semester.import.input"); compose.onNodeWithTag("semester.import.input").performTextReplacement("{ invalid board JSON }")
        revealImport("semester.import.continue"); compose.onNodeWithTag("semester.import.continue").performClick(); awaitImport("Import error")
        revealImport("semester.import.error"); capture("raw-import-invalid-json-error", "semester.import.error")
        assertEquals(invalidBefore, repository.state.value.snapshot)

        useFixture("preserved-synthetic-snapshot-readonly-recovery", SemesterBoardTestFixture.board(start = start))
        val preserved = repository.state.value.snapshot
        sourceFile().writeText("synthetic corrupted fixture", Charsets.UTF_8)
        runBlocking { repository.refresh() }
        assertTrue(repository.state.value.recoveryRequired)
        assertEquals(preserved, repository.state.value.snapshot)
        revealBoardSetupAction("semester.button.import"); compose.onNodeWithTag("semester.button.import").assertIsNotEnabled()
        compose.onNodeWithTag("semester.button.tools").performClick()
        assertEquals("Collapsed", compose.onNodeWithTag("semester.button.tools").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        capture("preserved-board-readonly-recovery", "workspace.recovery", "semester.button.tools")
        openWeek(1)
        compose.onNodeWithTag("semester.week.recovery").assertIsDisplayed()
        compose.onNodeWithTag("semester.status.option.${SemesterBoardTestFixture.id(20)}").assertIsNotEnabled()
        capture("recovery-week-status-controls-disabled", "semester.week.detail", "semester.week.recovery", "semester.status.option.${SemesterBoardTestFixture.id(20)}")
        assertEquals(preserved, repository.state.value.snapshot)
    }

    private fun makeRepository(board: BoardSemester? = null, dark: Boolean = false): WorkspaceRepository = runBlocking {
        val value = WorkspaceRepository(File(directory, "source-${++sourceIndex}.json"))
        value.savePreferences(Preferences(language = "en", theme = if (dark) "dark" else "light"))
        if (board != null) value.applyBoard(value.previewBoard(BoardImportRequest.rawFile(board, timeZoneIdentifier = board.timeZoneIdentifier)))
        value
    }
    private fun useFixture(label: String, board: BoardSemester?, dark: Boolean = false, scale: Float = 1f) {
        val value = makeRepository(board, dark)
        fixture = label
        compose.runOnIdle { font.value = scale; active.value = value }
        compose.waitForIdle()
    }
    private fun sourceFile() = File(directory, "source-$sourceIndex.json")
    private fun course() = repository.state.value.snapshot.courses.first { it.externalID == SemesterBoardTestFixture.id(100) && !it.archived }
    private fun boardReveal(tag: String) { compose.onNodeWithTag("semester.board.scroll").performScrollToNode(hasTestTag(tag)) }
    private fun revealBoardSetupAction(tag: String) {
        compose.onNodeWithTag("semester.board.scroll").performScrollToIndex(0)
        if (compose.onAllNodesWithTag("semester.button.tools").fetchSemanticsNodes().isNotEmpty()) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("semester.button.tools").assertIsDisplayed().performClick()
        } else boardReveal(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun revealImport(tag: String) { compose.onNodeWithTag("semester.import.screen").performScrollToNode(hasTestTag(tag)) }
    private fun listReveal(tag: String) { compose.onNodeWithTag("semester.assessments.list").performScrollToNode(hasTestTag(tag)) }
    private fun openWeek(week: Int) {
        boardReveal("semester.course.${course().id}")
        compose.onNodeWithTag("semester.cell.${course().id}.$week").performScrollTo().performClick()
        compose.onNodeWithTag("semester.week.detail").assertIsDisplayed()
    }
    private fun openAssessments() { boardReveal("semester.button.assessments"); compose.onNodeWithTag("semester.button.assessments").performClick() }
    private fun paste(raw: String) {
        revealImport("semester.import.input"); compose.onNodeWithTag("semester.import.input").performTextReplacement(raw)
        revealImport("semester.import.continue"); compose.onNodeWithTag("semester.import.continue").performClick(); awaitImport("Choose courses")
    }
    private fun captureReceipt(name: String, headline: String, applied: Boolean) {
        compose.onNodeWithTag("semester.import.screen").performScrollToIndex(0)
        compose.onNodeWithText(headline).assertIsDisplayed()
        compose.onNodeWithTag("semester.import.done").assertIsDisplayed()
        if (applied) capture(name, "semester.import.receipt", "semester.import.done", "semester.import.undo")
        else capture(name, "semester.import.receipt", "semester.import.done")
    }
    private fun awaitImport(description: String) { compose.waitUntil(10_000) {
        compose.onNodeWithTag("semester.import.screen").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == description
    } }
    private fun assertDescription(tag: String, expected: String) {
        val descriptions = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("$tag must expose the actual source marker", descriptions.any { it.contains(expected, ignoreCase = true) })
    }
    private fun captureUnwritten(name: String, before: WorkspaceSnapshot, bytes: ByteArray, vararg markers: String) {
        assertEquals(before, repository.state.value.snapshot)
        assertArrayEquals(bytes, sourceFile().readBytes())
        capture(name, *markers, staged = true)
    }
    private fun capture(name: String, vararg markers: String, staged: Boolean = false) {
        compose.waitForIdle()
        markers.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        markers.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        val activity = AtomicReference("unknown test-host Activity")
        instrumentation.runOnMainSync {
            activity.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.javaClass?.name ?: "no resumed Activity")
        }
        val snapshot = repository.state.value.snapshot
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val stem = "%02d-%s".format(++sequence, name)
        val evidence = markers.map { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            JSONObject().put("tag", tag)
                .put("text", node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "")
                .put("description", JSONArray(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()))
                .put("state", node.config.getOrNull(SemanticsProperties.StateDescription) ?: JSONObject.NULL)
                .put("selected", node.config.getOrNull(SemanticsProperties.Selected) ?: JSONObject.NULL)
                .put("checked", node.config.getOrNull(SemanticsProperties.ToggleableState)?.toString() ?: JSONObject.NULL)
                .put("enabled", node.config.getOrNull(SemanticsProperties.Disabled) == null)
        }
        try {
            File(output, "$stem.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val metadata = JSONObject().put("capture", name).put("capturedAt", Instant.now().toString()).put("fixture", fixture)
                .put("verifiedVisibleMarkers", JSONArray(markers.toList())).put("markerEvidence", JSONArray(evidence)).put("stagedNoWriteVerified", staged)
                .put("source", "Synthetic private-cache WorkspaceRepository")
                .put("seeding", when {
                    fixture == "empty" -> "Preferences only; no academic records"
                    fixture == "raw-ios-import-selection-and-receipt" -> "Preferences only initially; raw-file review/save/replay/Undo exercised through real UI"
                    fixture == "preserved-synthetic-snapshot-readonly-recovery" -> "previewBoard/applyBoard seed; only the synthetic fixture file is then corrupted and refresh preserves its last readable snapshot"
                    else -> "previewBoard/applyBoard seed through repository APIs"
                })
                .put("seedStartDateForCurrentWeek", start.toString())
                .put("expectedCurrentWeek", if (snapshot.semesters.any { it.startDate == start.toString() }) 5 else JSONObject.NULL)
                .put("clock", "The real UI clock is not overridden; current-week fixtures place week 5 at the current source-local Monday")
                .put("viewport", if (name in setOf("populated-current-week-grid", "dark-font13-board-current-week"))
                    "Fresh natural Board viewport; no test jump or vertical scroll before capture" else "State reached through the recorded UI actions and marker reveals")
                .put("semesterDates", JSONArray(snapshot.semesters.map { JSONObject().put("id", it.id).put("start", it.startDate ?: JSONObject.NULL).put("weeks", it.weekCount) }))
                .put("workspaceRevision", snapshot.revision).put("recoveryRequired", repository.state.value.recoveryRequired)
                .put("courses", snapshot.courses.count { !it.archived }).put("assessments", snapshot.assessments.count { !it.archived }).put("tasks", snapshot.tasks.size)
                .put("theme", snapshot.preferences.theme).put("language", snapshot.preferences.language)
                .put("osFontScale", context.resources.configuration.fontScale.toDouble()).put("composeFontScale", font.value.toDouble()).put("osFontScaleChanged", false)
                .put("applicationVersion", packageInfo.versionName).put("applicationVersionCode", packageInfo.versionCode)
                .put("activity", activity.get()).put("testHostOnly", true).put("mainActivityOrServiceAcceptance", false)
                .put("buildScope", "Installed target app/test APK source; final APK hash and LLDB attachment recorded separately by QA")
                .put("systemBarsIncluded", true).put("pixelWidth", bitmap.width).put("pixelHeight", bitmap.height)
                .put("externalData", "No Calendar provider, network, desktop credentials, model downloads or inference")
            File(output, "$stem.json").writeText(metadata.toString(2), Charsets.UTF_8)
        } finally { bitmap.recycle() }
    }
}
