package ch.habits.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicSemester
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/** Synthetic in-memory sources only; verifies the real Today and Morning presentation. */
class UiSemesterProjectionTest {
    @get:Rule val compose = createComposeRule()
    private val semester = AcademicSemester("semester", "Autumn", null, 14, "Europe/Zurich")
    private val course = AcademicCourse("course", semester.id, "Statistics")
    private val unresolved = AcademicAssessment("unresolved", semester.id, course.id, "Unresolved exam", "exam", "2026-10-25", "02:30")
    private fun source() = WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course),
        assessments = listOf(unresolved, unresolved.copy(id = "dismissed", title = "Dismissed exam", dismissed = true)))

    @Test fun todayFallbackCannotReintroduceUnresolvedOrDismissedExams() {
        val snapshot = source().copy(tasks = listOf(WorkTask(id = "week", title = "Week preparation", dueDate = "9998-10-08",
            sourceCourseID = course.id, sourceWeek = 3)))
        compose.setContent { HabitsTheme(dark = false) { TodayScreen(snapshot, UiCopy("en"), onReport = {}, onPlanner = {}) } }
        compose.onNodeWithTag("today.timing.coverage").assertIsDisplayed()
        compose.onNodeWithTag("today.work.task/week").assertIsDisplayed()
        compose.onNodeWithText("Preparation").assertIsDisplayed()
        compose.onNodeWithText("Unresolved exam").assertDoesNotExist()
        compose.onNodeWithText("Dismissed exam").assertDoesNotExist()
        compose.onNodeWithText("No open deadlines due today or earlier.").assertDoesNotExist()
    }

    @Test fun morningShowsCoverageAndKeepsValidWorkAndScheduledHabits() {
        val date = LocalDate.now()
        val snapshot = source().copy(tasks = listOf(WorkTask(id = "valid", title = "Valid civil work", dueDate = date.toString())),
            habits = listOf(Habit("habit", "Recorded routine", weekdays = listOf(date.dayOfWeek.value))))
        compose.setContent { HabitsTheme(dark = true) { ReportScreen("morning", snapshot, UiCopy("en")) } }
        compose.onNodeWithTag("report.timing.coverage").assertIsDisplayed()
        compose.onNodeWithTag("report.task.valid").assertIsDisplayed()
        compose.onNodeWithTag("report.screen").performScrollToNode(hasText("Recorded routine"))
        compose.onNodeWithText("Recorded routine").assertIsDisplayed()
        compose.onNodeWithTag("report.screen").performScrollToNode(hasTestTag("report.timing.coverage"))
        compose.onNodeWithText("Unresolved exam").assertDoesNotExist()
        compose.onNodeWithText("Dismissed exam").assertDoesNotExist()
    }
}
