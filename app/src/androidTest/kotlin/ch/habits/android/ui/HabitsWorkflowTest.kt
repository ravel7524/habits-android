package ch.habits.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.core.PlanAssessment
import ch.habits.android.core.PlanCourse
import ch.habits.android.core.PlanPreparation
import ch.habits.android.core.PlanSemester
import ch.habits.android.data.Preferences
import ch.habits.android.data.ReceiptState
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.data.WorkTask
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic private-cache stores; no real phone data or desktop connection. */
class HabitsWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var directory: File
    private lateinit var repository: WorkspaceRepository
    private val fixture = AcademicPlan(
        proposalID = "f0000000-0000-0000-0000-000000000001",
        source = "Synthetic university timetable",
        semester = PlanSemester("f0000000-0000-0000-0000-000000000002", "Test semester", "2026-09-14", 14, "Europe/Zurich"),
        courses = listOf(PlanCourse("f0000000-0000-0000-0000-000000000003", "Statistics")),
        assessments = listOf(PlanAssessment("f0000000-0000-0000-0000-000000000004", "f0000000-0000-0000-0000-000000000003", "Statistics final exam", "exam", "2027-01-20", "09:00")),
        preparationTasks = listOf(PlanPreparation("f0000000-0000-0000-0000-000000000005", "f0000000-0000-0000-0000-000000000004", "Review statistics", estimatedWorkMinutes = 60)),
    )

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "habits-ui-${UUID.randomUUID()}")
        check(directory.mkdirs())
        repository = WorkspaceRepository(File(directory, "state.json"))
        runBlocking { repository.savePreferences(Preferences(language = "en", theme = "light")) }
        compose.setContent { HabitsApp(repository) }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun proposalPreviewCanBeCancelledWithoutPersistingAnything() {
        openAssistant()
        pasteAndReview()
        assistantScrollTo("assistant.plan.apply")
        compose.onNodeWithTag("assistant.plan.apply").assertIsDisplayed()
        assertEquals(0, repository.state.value.snapshot.assessments.size)
        assertEquals(0, repository.state.value.snapshot.tasks.size)
        compose.onNodeWithTag("assistant.plan.close").performClick()
        compose.onNodeWithTag("semester.empty").assertExists()
        assertEquals(0, repository.state.value.snapshot.receipts.size)
    }

    @Test fun appliedProposalIsIdempotentAndUndoPreservesConsumedReceipt() {
        openAssistant()
        pasteAndReview()
        assistantScrollTo("assistant.plan.apply")
        compose.onNodeWithTag("assistant.plan.apply").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.receipts.any { it.state == ReceiptState.APPLIED } }
        assistantScrollTo("assistant.plan.receipt")
        compose.onNodeWithTag("assistant.plan.receipt").assertIsDisplayed()
        assertEquals(1, repository.state.value.snapshot.tasks.count { !it.archived })
        assertEquals(1, repository.state.value.snapshot.assessments.count { !it.archived })
        assertFalse(repository.state.value.snapshot.assessments.single().completed)
        compose.onNodeWithTag("assistant.plan.close").performClick()
        compose.onNodeWithTag("semester.button.assessments").performClick()
        compose.onNodeWithTag("semester.assessments.list").performScrollToNode(hasTestTag("semester.assessment.${fixture.assessments.single().id}"))
        compose.onNodeWithTag("semester.assessment.${fixture.assessments.single().id}").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("planner.mode.work").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.task.${fixture.preparationTasks.single().id}"))
        compose.onNodeWithTag("planner.task.${fixture.preparationTasks.single().id}").assertIsDisplayed()

        openAssistant()
        pasteAndReview()
        assistantScrollTo("assistant.plan.receipt")
        assertEquals(0, compose.onAllNodesWithTag("assistant.plan.apply").fetchSemanticsNodes().size)
        assertEquals(1, repository.state.value.snapshot.tasks.count { !it.archived })
        assistantScrollTo("assistant.plan.undo")
        compose.onNodeWithTag("assistant.plan.undo").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.receipts.single().state == ReceiptState.UNDONE }
        assertEquals(0, repository.state.value.snapshot.tasks.count { !it.archived })
        assertEquals(0, repository.state.value.snapshot.assessments.count { !it.archived })
        compose.onNodeWithTag("assistant.plan.close").performClick()
        compose.onNodeWithTag("semester.empty").assertExists()

        openAssistant()
        pasteAndReview()
        assistantScrollTo("assistant.plan.receipt")
        compose.onNodeWithTag("assistant.plan.receipt").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("assistant.plan.apply").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("assistant.plan.undo").fetchSemanticsNodes().size)
        assertEquals(ReceiptState.UNDONE, repository.state.value.snapshot.receipts.single().state)
    }

    @Test fun malformedProposalShowsErrorAndCannotSave() {
        openAssistant()
        assistantScrollTo("assistant.plan.input")
        compose.onNodeWithTag("assistant.plan.input").performTextInput("{ definitely not JSON }")
        assistantScrollTo("assistant.plan.review")
        compose.onNodeWithTag("assistant.plan.review").performClick()
        awaitAssistantState("Proposal error")
        assistantScrollTo("assistant.plan.error")
        compose.onNodeWithTag("assistant.plan.error").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("assistant.plan.apply").fetchSemanticsNodes().size)
        assertEquals(0, repository.state.value.snapshot.tasks.size)
        assertEquals(0, repository.state.value.snapshot.semesters.size)
    }

    @Test fun changedWorkspaceRequiresFreshReviewAndPreservesExistingWork() {
        openAssistant()
        pasteAndReview()
        runBlocking { repository.saveTask(WorkTask(title = "Existing personal work")) }
        assistantScrollTo("assistant.plan.apply")
        compose.onNodeWithTag("assistant.plan.apply").performClick()
        awaitAssistantState("Proposal error")
        assistantScrollTo("assistant.plan.error")
        compose.onNodeWithTag("assistant.plan.error").assertIsDisplayed()
        assertEquals(0, repository.state.value.snapshot.assessments.size)
        assertEquals(1, repository.state.value.snapshot.tasks.count { !it.archived })
        assistantScrollTo("assistant.plan.refresh")
        compose.onNodeWithTag("assistant.plan.refresh").performClick()
        awaitAssistantState("Proposal review")
        assistantScrollTo("assistant.plan.apply")
        compose.onNodeWithTag("assistant.plan.apply").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.receipts.any { it.state == ReceiptState.APPLIED } }
        assertEquals(2, repository.state.value.snapshot.tasks.count { !it.archived })
        assertEquals(1, repository.state.value.snapshot.tasks.count { it.title == "Existing personal work" && !it.archived })
    }

    @Test fun taskCompletionAndHabitLogAppearAsRecordedFactsInEveningReport() {
        compose.onNodeWithTag("nav.planner").performClick()
        compose.onNodeWithTag("planner.add.task").performClick()
        compose.onNodeWithTag("editor.task.title").performTextInput("Read notes")
        compose.onNodeWithTag("editor.task.title").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("editor.task.review").assertIsEnabled().assertIsDisplayed().performClick()
        assertEquals(0, repository.state.value.snapshot.tasks.size)
        compose.onNodeWithTag("editor.task.save").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.size == 1 }
        val taskID = repository.state.value.snapshot.tasks.single().id
        compose.onNodeWithTag("planner.complete.$taskID").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.tasks.single().completed }

        compose.onNodeWithTag("nav.habits").performClick()
        compose.onNodeWithTag("habits.add").performClick()
        compose.onNodeWithTag("editor.habit.title").performTextInput("Walk")
        compose.onNodeWithTag("editor.habit.title").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("editor.habit.review").assertIsEnabled().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor.habit.save").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            compose.onRoot().printToLog("HabitReviewFailure")
            throw failure
        }
        assertEquals(0, repository.state.value.snapshot.habits.size)
        compose.onNodeWithTag("editor.habit.save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.habits.size == 1 }
        val habitID = repository.state.value.snapshot.habits.single().id
        compose.onNodeWithTag("habits.log.$habitID").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.habitLogs.any { it.habitID == habitID && it.completed } }

        compose.onNodeWithTag("nav.today").performClick()
        compose.onNodeWithTag("today.screen").performScrollToNode(hasTestTag("today.report.evening"))
        compose.onNodeWithTag("today.report.evening").performClick()
        compose.onNodeWithTag("report.source").assertTextContains("Offline · deterministic summary")
        compose.onNodeWithTag("report.screen").performScrollToNode(hasTestTag("report.completedTasks"))
        compose.onNodeWithTag("report.completedTasks").assertTextContains("1 task or assignment")
        compose.onNodeWithTag("report.habitLogs").assertTextContains("1 habit log")
    }

    private fun openAssistant() {
        compose.onNodeWithTag("nav.planner").performClick()
        compose.onNodeWithTag("planner.mode.semester").performClick()
        compose.onNodeWithTag("semester.board.scroll").performScrollToIndex(0)
        if (compose.onAllNodesWithTag("semester.button.tools").fetchSemanticsNodes().isNotEmpty()) {
            if (compose.onAllNodesWithTag("semester.button.examPlan").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("semester.button.tools").assertIsDisplayed().performClick()
        } else compose.onNodeWithTag("semester.board.scroll").performScrollToNode(hasTestTag("semester.button.examPlan"))
        compose.onNodeWithTag("semester.button.examPlan").assertIsDisplayed()
        compose.onNodeWithTag("semester.button.examPlan").performClick()
    }

    private fun pasteAndReview() {
        assistantScrollTo("assistant.plan.input")
        compose.onNodeWithTag("assistant.plan.input").performTextInput(AcademicPlanCodec.encode(fixture))
        assistantScrollTo("assistant.plan.review")
        compose.onNodeWithTag("assistant.plan.review").performClick()
        awaitAssistantState("Proposal review")
        assistantScrollTo("assistant.plan.assessment.${fixture.assessments.single().id}")
        compose.onNodeWithTag("assistant.plan.assessment.${fixture.assessments.single().id}").assertIsDisplayed()
    }

    private fun assistantScrollTo(tag: String) {
        compose.onNodeWithTag("assistant.plan.screen").performScrollToNode(hasTestTag(tag))
    }

    private fun awaitAssistantState(description: String) {
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("assistant.plan.screen").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == description
        }
    }
}
