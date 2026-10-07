package ch.habits.android.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import ch.habits.android.data.*
import ch.habits.android.semester.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real routes and repository operations, using only a synthetic private-cache store. */
class SemesterBoardWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var repository: WorkspaceRepository
    private var expectedInput = "none"

    @Before fun setup() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "board-workflow-${UUID.randomUUID()}")
        check(directory.mkdirs())
        file = File(directory, "workspace.json")
        repository = WorkspaceRepository(file)
        runBlocking { repository.savePreferences(Preferences(language = "en", theme = "light")) }
        compose.setContent { HabitsApp(repository) }
    }
    @After fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun rawPreviewCancelSelectiveSaveReplayAndUndoKeepUnknownDatesAndScopedLegacyExam() {
        val raw = SemesterBoardTestFixture.legacyRaw(withDates = false)
        val original = repository.state.value.snapshot
        val originalBytes = file.readBytes()
        openImport(); paste(raw)
        revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        reviewImport()
        revealImport("semester.import.confirm")
        assertEquals(original, repository.state.value.snapshot)
        assertArrayEquals(originalBytes, file.readBytes())
        compose.onNodeWithTag("semester.import.cancel").performClick()
        compose.onNodeWithTag("semester.empty").assertExists()
        assertEquals(original, repository.state.value.snapshot)

        openImport(); paste(raw)
        revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        reviewImport(); revealImport("semester.import.confirm")
        compose.onNodeWithTag("semester.import.confirm").assertIsEnabled().performClick()
        awaitImport("Import receipt")
        val saved = repository.state.value.snapshot
        assertEquals(1, saved.courses.size)
        assertEquals(1, saved.assessments.size)
        assertNull(saved.semesters.single().startDate)
        assertNull(saved.semesters.single().endDate)
        assertEquals(SemesterBoardTestFixture.id(100), saved.assessments.single().externalID)
        assertNotEquals(saved.courses.single().id, saved.assessments.single().id)
        assertEquals(saved.courses.single().id, saved.assessments.single().courseID)
        assertTrue(saved.tasks.isEmpty())
        assertTrue(saved.boardReceipts.single().rawFile)
        revealImport("semester.import.done"); compose.onNodeWithTag("semester.import.done").performClick()
        revealBoard("semester.dates.missing"); compose.onNodeWithTag("semester.dates.missing").assertIsDisplayed()

        openImport(); paste(raw)
        revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        reviewImport("Import receipt")
        assertEquals(saved, repository.state.value.snapshot)
        assertEquals(0, compose.onAllNodesWithTag("semester.import.confirm").fetchSemanticsNodes().size)
        revealImport("semester.import.undo"); compose.onNodeWithTag("semester.import.undo").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.boardReceipts.single().state == ReceiptState.UNDONE }
        assertTrue(repository.state.value.snapshot.semesters.all { it.archived })
        assertTrue(repository.state.value.snapshot.courses.all { it.archived })
        assertTrue(repository.state.value.snapshot.assessments.all { it.archived })
        revealImport("semester.import.done"); compose.onNodeWithTag("semester.import.done").performClick()
        openImport(); paste(raw)
        revealImport("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected")
        compose.onNodeWithTag("semester.import.course.${SemesterBoardTestFixture.id(101)}.selected").performClick()
        reviewImport("Import receipt")
        assertEquals(0, compose.onAllNodesWithTag("semester.import.confirm").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("semester.import.undo").fetchSemanticsNodes().size)
        assertEquals(1, repository.state.value.snapshot.boardReceipts.size)
    }

    @Test fun weekStatusPreservesNotesAndExternalChangesRequireExplicitReload() {
        seed()
        openWeek(1)
        val course = course()
        val semester = repository.state.value.snapshot.semesters.single()
        compose.onNodeWithTag("semester.week.notes").performScrollTo().assertTextContains(SemesterBoardTestFixture.weekNote)
        runBlocking { repository.setWeekStatus(semester.id, course.id, 1, SemesterBoardTestFixture.id(21), course.entries[1], semester.statuses) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("semester.week.error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("semester.status.option.${SemesterBoardTestFixture.id(20)}").performScrollTo().assertIsNotEnabled()
        val changed = repository.state.value.snapshot
        compose.onNodeWithTag("semester.week.reload").performScrollTo().performClick()
        compose.onNodeWithTag("semester.status.option.${SemesterBoardTestFixture.id(21)}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("semester.status.option.${SemesterBoardTestFixture.id(20)}").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { course().entries[1]?.statusID == SemesterBoardTestFixture.id(20) }
        assertEquals(course.entries[1]?.notes, course().entries[1]?.notes)
        assertEquals(changed.assessments, repository.state.value.snapshot.assessments)
        assertEquals(changed.tasks, repository.state.value.snapshot.tasks)
        assertFalse(repository.state.value.snapshot.assessments.first().completed)
    }

    @Test fun weekPreparationDraftStagesUndatedZeroEstimateAndCompletionDoesNotChangeProgress() {
        seed(); openWeek(1)
        val before = repository.state.value.snapshot
        val beforeBytes = file.readBytes()
        compose.onNodeWithTag("semester.week.planReview").performScrollTo().performClick()
        compose.onNodeWithTag("editor.task.title").assertTextContains("Review Statistics · Week 1")
        assertEquals(0, compose.onAllNodesWithTag("editor.task.archive").fetchSemanticsNodes().size)
        assertEquals(before, repository.state.value.snapshot)
        compose.onNodeWithTag("editor.task.review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor.task.save").assertIsDisplayed()
        compose.onNodeWithText("No deadline").assertExists()
        assertArrayEquals(beforeBytes, file.readBytes())
        compose.onNodeWithTag("detail.close").performClick()
        assertEquals(before, repository.state.value.snapshot)
        openWeek(1)
        compose.onNodeWithTag("semester.week.planReview").performScrollTo().performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        compose.onNodeWithTag("editor.task.save").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.size == 1 }
        val preparation = repository.state.value.snapshot.tasks.single()
        assertNull(preparation.dueDate); assertNull(preparation.dueTime)
        assertEquals(0, preparation.minutes)
        assertEquals(course().id, preparation.sourceCourseID)
        assertEquals(1, preparation.sourceWeek)
        assertNull(preparation.sourceAssessmentID)
        assertEquals(before.courses, repository.state.value.snapshot.courses)
        assertEquals(before.assessments, repository.state.value.snapshot.assessments)
        compose.onNodeWithTag("planner.mode.work").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.complete.${preparation.id}"))
        compose.onNodeWithTag("planner.complete.${preparation.id}").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.single().completed }
        assertEquals(before.courses, repository.state.value.snapshot.courses)
        assertEquals(before.assessments, repository.state.value.snapshot.assessments)
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.showCompleted"))
        compose.onNodeWithTag("planner.showCompleted").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.source.${preparation.id}"))
        compose.onNodeWithTag("planner.source.${preparation.id}").performClick()
        compose.onNodeWithTag("semester.week.detail").assertIsDisplayed()
        compose.onNodeWithTag("semester.week.notes").performScrollTo().assertTextContains(SemesterBoardTestFixture.weekNote)
    }

    @Test fun allAssessmentsKeepsCompletedDismissedAndReopenReachableWithScopedIdentities() {
        val first = SemesterBoardCodec.decode(SemesterBoardTestFixture.legacyRaw())
        val second = first.copy(id = SemesterBoardTestFixture.id(2), title = "Second semester")
        runBlocking {
            repository.applyBoard(repository.previewBoard(BoardImportRequest.rawFile(first, timeZoneIdentifier = "Europe/Zurich")))
            repository.applyBoard(repository.previewBoard(BoardImportRequest.rawFile(second, timeZoneIdentifier = "Europe/Zurich")))
        }
        val all = repository.state.value.snapshot.assessments
        assertEquals(4, all.map { it.id }.distinct().size)
        assertEquals(2, all.count { it.externalID == SemesterBoardTestFixture.id(100) })
        assertTrue(all.none { it.id == it.externalID })
        plannerSemester(); openAssessments()
        val assessment = all.first { it.semesterID == first.id }
        openAssessment(assessment.id)
        compose.onNodeWithTag("assessment.complete").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.assessments.first { it.id == assessment.id }.completed }
        closeAssessment()
        openAssessment(assessment.id)
        compose.onNodeWithTag("semester.assessment.state").assertTextContains("Completed")
        compose.onNodeWithTag("assessment.complete").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.assessments.first { it.id == assessment.id }.pending }
        compose.onNodeWithTag("semester.assessment.dismiss").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.assessments.first { it.id == assessment.id }.dismissed }
        closeAssessment(); openAssessment(assessment.id)
        compose.onNodeWithTag("semester.assessment.state").assertTextContains("Dismissed")
        compose.onNodeWithTag("assessment.complete").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.assessments.first { it.id == assessment.id }.pending }
        assertNull(repository.state.value.snapshot.assessments.first { it.id == assessment.id }.completedAt)
        assertEquals(all.filter { it.semesterID == second.id }, repository.state.value.snapshot.assessments.filter { it.semesterID == second.id })
        assertTrue(repository.state.value.snapshot.tasks.isEmpty())
    }

    @Test fun dismissedAssessmentCanHaveIndependentReviewedPreparationAndSourceNavigation() {
        seed(); plannerSemester(); openAssessments()
        val assessment = repository.state.value.snapshot.assessments.first { it.dismissed }
        openAssessment(assessment.id)
        val before = repository.state.value.snapshot
        compose.onNodeWithTag("semester.assessment.prepare").performClick()
        compose.onNodeWithTag("editor.task.title").assertTextContains("Prepare for ${assessment.title}")
        assertEquals(0, compose.onAllNodesWithTag("editor.task.archive").fetchSemanticsNodes().size)
        compose.onNodeWithTag("editor.task.review").performClick()
        assertEquals(before, repository.state.value.snapshot)
        compose.onNodeWithTag("editor.task.save").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.size == 1 }
        val preparation = repository.state.value.snapshot.tasks.single()
        assertEquals(assessment.id, preparation.sourceAssessmentID)
        assertNull(preparation.sourceCourseID); assertNull(preparation.sourceWeek)
        assertNull(preparation.dueDate); assertNull(preparation.dueTime); assertEquals(0, preparation.minutes)
        compose.onNodeWithTag("planner.mode.work").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.complete.${preparation.id}"))
        compose.onNodeWithTag("planner.complete.${preparation.id}").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.single().completed }
        assertEquals(before.assessments, repository.state.value.snapshot.assessments)
        assertEquals(before.courses, repository.state.value.snapshot.courses)
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.showCompleted"))
        compose.onNodeWithTag("planner.showCompleted").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.source.${preparation.id}"))
        compose.onNodeWithTag("planner.source.${preparation.id}").performClick()
        compose.onNodeWithTag("assessment.detail").assertIsDisplayed()
        compose.onNodeWithTag("semester.assessment.state").assertTextContains("Dismissed")
    }

    @Test fun invalidAndStaleImportCannotWriteUntilFreshReview() {
        openImport(); val original = repository.state.value.snapshot
        compose.onNodeWithTag("semester.import.input").performTextReplacement("{ not board JSON }")
        revealImport("semester.import.continue"); compose.onNodeWithTag("semester.import.continue").performClick()
        awaitImportError()
        assertEquals(original, repository.state.value.snapshot)
        assertEquals(0, compose.onAllNodesWithTag("semester.import.confirm").fetchSemanticsNodes().size)
        revealImport("semester.import.input"); paste(SemesterBoardCodec.encode(SemesterBoardTestFixture.board()))
        reviewImport()
        runBlocking { repository.saveTask(WorkTask(title = "Existing independent work")) }
        revealImport("semester.import.confirm")
        compose.onNodeWithTag("semester.import.confirm").assertIsNotEnabled()
        val current = repository.state.value.snapshot
        assertTrue(current.semesters.isEmpty())
        revealImport("semester.import.refresh"); compose.onNodeWithTag("semester.import.refresh").performClick()
        awaitImport("Review changes")
        revealImport("semester.import.confirm")
        compose.waitUntil(10_000) { compose.onNodeWithTag("semester.import.confirm").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
        compose.onNodeWithTag("semester.import.confirm").assertIsEnabled().performClick()
        awaitImport("Import receipt")
        assertEquals(current.tasks, repository.state.value.snapshot.tasks)
        assertEquals(1, repository.state.value.snapshot.boardReceipts.size)
    }

    private fun seed() = runBlocking {
        val board = SemesterBoardTestFixture.board()
        repository.applyBoard(repository.previewBoard(BoardImportRequest.rawFile(board, timeZoneIdentifier = board.timeZoneIdentifier)))
    }
    private fun course() = repository.state.value.snapshot.courses.first { it.externalID == SemesterBoardTestFixture.id(100) && !it.archived }
    private fun plannerSemester() {
        compose.onNodeWithTag("nav.planner").performClick()
        compose.onNodeWithTag("planner.mode.semester").performClick()
    }
    private fun openImport() { plannerSemester(); revealBoardSetupAction("semester.button.import"); compose.onNodeWithTag("semester.button.import").performClick() }
    private fun revealBoardSetupAction(tag: String) {
        compose.onNodeWithTag("semester.board.scroll").performScrollToIndex(0)
        if (compose.onAllNodesWithTag("semester.button.tools").fetchSemanticsNodes().isNotEmpty()) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("semester.button.tools").assertIsDisplayed().performClick()
        } else revealBoard(tag)
        // Populated-board actions are in a separate popup root, not inside the LazyColumn.
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun paste(raw: String) {
        expectedInput = inputEvidence(raw)
        revealImport("semester.import.input")
        // A real focused Android text field makes focus clearing exercise the UI-thread invariant.
        compose.onNodeWithTag("semester.import.input").performClick().performTextReplacement(raw)
        revealImport("semester.import.continue"); compose.onNodeWithTag("semester.import.continue").performClick()
        awaitImport("Choose courses")
    }
    private fun reviewImport(stage: String = "Review changes") {
        revealImport("semester.import.review"); compose.onNodeWithTag("semester.import.review").performClick(); awaitImport(stage)
    }
    private fun awaitImport(stage: String) {
        var observed = "no import screen"
        try {
            compose.waitUntil(10_000) {
                val screen = compose.onAllNodesWithTag("semester.import.screen").fetchSemanticsNodes().singleOrNull()
                observed = screen?.config?.getOrNull(SemanticsProperties.StateDescription)
                    ?: if (screen == null) "no import screen" else "stateDescription absent"
                if (observed == "Import error" && stage != observed) throw AssertionError("Unexpected terminal import error")
                observed == stage
            }
        } catch (failure: Throwable) {
            val diagnostics = importFailureEvidence(stage, observed)
            Log.e("SemesterBoardWorkflow", diagnostics)
            throw AssertionError("Import state assertion failed (10,000 ms maximum): $diagnostics").also { it.initCause(failure) }
        }
    }
    /** Only fixed synthetic fields and bounded validation diagnostics; never dump pasted JSON or notes. */
    private fun importFailureEvidence(expected: String, observed: String): String {
        val report = JSONObject().put("expectedState", expected).put("observedState", observed).put("expectedInput", expectedInput)
        if (observed == "Import error") {
            try {
                revealImport("semester.import.error")
                val error = compose.onNodeWithTag("semester.import.error").fetchSemanticsNode()
                report.put("errorCopy", error.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .joinToString(" ") { it.text }.take(600))
                if (compose.onAllNodesWithTag("semester.import.error.details").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("semester.import.error.details").performClick()
                    revealImport("semester.import.error.diagnostic")
                    val text = compose.onNodeWithTag("semester.import.error.diagnostic").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
                    report.put("diagnostic", safeValidationDiagnostic(text))
                }
            } catch (diagnosticFailure: Throwable) { report.put("diagnosticReadFailure", diagnosticFailure.javaClass.simpleName) }
        }
        try {
            revealImport("semester.import.input")
            val actual = compose.onNodeWithTag("semester.import.input").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text
            report.put("actualInput", actual?.let(::inputEvidence) ?: "input contents unavailable")
        } catch (_: Throwable) { report.put("actualInput", "input not present in this stage") }
        val state = repository.state.value
        report.put("workspaceRevision", state.snapshot.revision).put("recoveryRequired", state.recoveryRequired)
            .put("semesterCount", state.snapshot.semesters.size).put("courseCount", state.snapshot.courses.size)
            .put("assessmentCount", state.snapshot.assessments.size).put("taskCount", state.snapshot.tasks.size)
            .put("localBoardReceiptStates", state.snapshot.boardReceipts.joinToString { it.state.name })
        return report.toString()
    }
    private fun safeValidationDiagnostic(text: String): String {
        // Fixed framework-only thread names carry no pasted data, paths or credentials.
        if (Regex("Only the original thread that created a view hierarchy can touch its views\\. Expected: main Calling: DefaultDispatcher-worker-[0-9]+").matches(text)) return text
        // These codecs deliberately report field paths/reasons without source values.
        if (!Regex("^(?:semester(?:\\.|:|\\s)|proposal(?:\\.|:|\\s)|JSON(?::|\\s)|board\\s|timestamp\\s|This\\s|The\\s).*", RegexOption.DOT_MATCHES_ALL).matches(text))
            return "Non-field diagnostic withheld (length ${text.length})"
        return text.replace(Regex("https?://\\S+"), "[URL]")
            .replace(Regex("(?:/(?:Users|private|data|storage|sdcard)/|file:)\\S+"), "[path]")
            .replace(Regex("\\s+"), " ").take(600)
    }
    private fun inputEvidence(text: String): String = "chars=${text.length},bytes=${text.toByteArray(Charsets.UTF_8).size},sha256=" +
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun awaitImportError() { awaitImport("Import error"); revealImport("semester.import.error"); compose.onNodeWithTag("semester.import.error").assertIsDisplayed() }
    private fun revealImport(tag: String) { compose.onNodeWithTag("semester.import.screen").performScrollToNode(hasTestTag(tag)) }
    private fun revealBoard(tag: String) { compose.onNodeWithTag("semester.board.scroll").performScrollToNode(hasTestTag(tag)) }
    private fun openWeek(week: Int) {
        plannerSemester(); revealBoard("semester.course.${course().id}")
        compose.onNodeWithTag("semester.cell.${course().id}.$week").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("semester.week.detail").assertIsDisplayed()
    }
    private fun openAssessments() { revealBoard("semester.button.assessments"); compose.onNodeWithTag("semester.button.assessments").performClick() }
    private fun openAssessment(id: String) {
        compose.onNodeWithTag("semester.assessments.list").performScrollToNode(hasTestTag("semester.assessment.$id"))
        compose.onNodeWithTag("semester.assessment.$id").performClick()
        compose.onNodeWithTag("assessment.detail").assertIsDisplayed()
    }
    private fun closeAssessment() { compose.onNodeWithText("Close").performClick() }
}

/** Fixed IDs/copy; capture callers may explicitly choose a source start day for current-week evidence. */
internal object SemesterBoardTestFixture {
    const val weekNote = "Review probability definitions and bring two questions. This is a saved note, not proof of study."
    fun id(number: Int) = "bd000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    fun board(number: Int = 1, start: LocalDate? = LocalDate.parse("2026-09-14"), courses: Int = 2,
              weeks: Int = 14, defaults: Boolean = false): BoardSemester = BoardSemester(
        id = id(number), title = when (number) { 1 -> "Autumn course board"; 2 -> "Undated course board"; else -> "Extended course board" },
        weekCount = weeks, startDate = start?.toString(), endDate = start?.plusDays(weeks * 7L - 1)?.toString(),
        showExamWeeks = true, timeZoneIdentifier = "Europe/Zurich",
        statuses = if (defaults) emptyList() else listOf(BoardStatus(id(20), "Reviewed", "#37A269"), BoardStatus(id(21), "Revisit", "#EAAB46")),
        courses = (0 until courses).map { index -> BoardCourse(id(100 + index), when (index) {
            0 -> "Statistics"; 1 -> "Corporate Finance"; else -> "Advanced quantitative methods and evidence analysis ${index + 1}"
        }, activeWeeks = (1..weeks).filter { it % 4 != 0 }, entries = mapOf(1 to BoardWeekEntry(if (defaults) null else id(20), weekNote),
            5 to BoardWeekEntry(if (defaults) null else id(21), "Check examples before the tutorial.")),
            moduleCode = "QA-${index + 1}", scheduleNote = "Teaching schedule supplied by the synthetic source.",
            assessments = if (index == 0) listOf(
                BoardAssessment(id(200), "Statistics exam", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(2).toString(), "09:00"),
                BoardAssessment(id(201), "Later statistics exam", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(17 * 7L + 2).toString(), "09:00"),
                BoardAssessment(id(202), "Recorded essay", "essay", (start ?: LocalDate.parse("2026-09-14")).plusDays(1).toString(), state = "completed",
                    completedAt = Instant.parse("2026-10-01T12:00:00Z").epochSecond - Instant.parse("2001-01-01T00:00:00Z").epochSecond.toDouble()),
                BoardAssessment(id(203), "Dismissed presentation", "presentation", (start ?: LocalDate.parse("2026-09-14")).plusDays(3).toString(), state = "dismissed"))
                else listOf(BoardAssessment(id(200 + index * 10), "Finance exam ${index + 1}", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(8).toString()))) })
    fun legacyRaw(withDates: Boolean = true): String {
        val value = JSONObject(SemesterBoardCodec.encode(board(start = if (withDates) LocalDate.parse("2026-09-14") else null)))
        for (index in 0..1) value.getJSONArray("courses").getJSONObject(index).apply {
            remove("assessments"); put("examDate", "2026-10-05"); put("examTime", "09:00"); put("examNote", "Synthetic legacy course exam")
        }
        return value.toString()
    }
}
