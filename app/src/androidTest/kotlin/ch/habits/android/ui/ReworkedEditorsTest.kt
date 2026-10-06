package ch.habits.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.WorkTask
import java.util.TimeZone
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Native picker and real editor interactions; callbacks receive synthetic records only. */
class ReworkedEditorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewStaysVisibleWithKeyboardAndLargeTextWithoutInventingDeadlineOrEstimate() {
        var saved: WorkTask? = null
        content(dark = true) { TaskEditor(null, UiCopy("en"), false, { saved = it }, null) }
        compose.onNodeWithTag("editor.task.title").performClick().performTextInput("Read lecture notes")
        compose.onNodeWithTag("editor.task.review").assertIsDisplayed().performClick()
        assertNull(saved)
        compose.onNodeWithTag("editor.task.save").assertIsDisplayed().performClick()
        assertEquals("Read lecture notes", saved?.title)
        assertNull(saved?.dueDate)
        assertNull(saved?.dueTime)
        assertEquals(0, saved?.minutes)
    }

    @Test fun confirmingNativeDatePreservesCivilDayInAnotherDeviceZoneAndKeepsTimeAbsent() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            var saved: WorkTask? = null
            val initial = WorkTask(title = "Statistics review", dueDate = "2027-01-20", sourceZone = "Europe/Zurich")
            content { TaskEditor(initial, UiCopy("en"), false, { saved = it }, null) }
            scroll("editor.task", "editor.task.date")
            compose.onNodeWithTag("editor.task.date").performClick()
            compose.onNodeWithTag("editor.task.date.dialog").assertExists()
            compose.onNodeWithTag("editor.task.date.confirm").performClick()
            compose.onNodeWithTag("editor.task.review").performClick()
            assertNull(saved)
            compose.onNodeWithTag("editor.task.save").performClick()
            assertEquals("2027-01-20", saved?.dueDate)
            assertNull(saved?.dueTime)
            assertEquals("Europe/Zurich", saved?.sourceZone)
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun removingTimeKeepsDateAndRemovingDateAlsoClearsTime() {
        var saved: WorkTask? = null
        content { TaskEditor(WorkTask(title = "Read notes", dueDate = "2027-01-20", dueTime = "09:30", sourceZone = "Europe/Zurich"),
            UiCopy("en"), false, { saved = it }, null) }
        scroll("editor.task", "editor.task.time.remove")
        compose.onNodeWithTag("editor.task.time.remove").performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        compose.onNodeWithText("Date only").assertExists()
        assertNull(saved)
        compose.onNodeWithTag("editor.task.edit").performClick()
        scroll("editor.task", "editor.task.date.remove")
        compose.onNodeWithTag("editor.task.date.remove").performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        compose.onNodeWithTag("editor.task.save").performClick()
        assertNull(saved?.dueDate)
        assertNull(saved?.dueTime)
        assertNull(saved?.sourceZone)
    }

    @Test fun appointmentDatePickerRejectsRepeatedDstClockBeforeSaving() {
        var saved: Appointment? = null
        val initial = Appointment(title = "Study meeting", startDate = "2026-10-24", startTime = "02:30",
            endDate = "2026-10-24", endTime = "03:30", timeZoneIdentifier = "Europe/Zurich")
        content { AppointmentEditor(initial, UiCopy("en"), false, { saved = it }, null) }
        scroll("editor.appointment", "editor.appointment.startDate")
        compose.onNodeWithTag("editor.appointment.startDate").performClick()
        val repeatedDay = "Sunday, October 25, 2026"
        compose.onNodeWithTag("editor.appointment.startDate.dialog").assertExists()
        val dateCell = hasText(repeatedDay) or hasContentDescription(repeatedDay)
        compose.waitUntil(5_000) { compose.onAllNodes(dateCell).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(dateCell).assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor.appointment.startDate.confirm").performClick()
        compose.onNodeWithTag("editor.appointment.review").performClick()
        compose.onNodeWithTag("editor.error").assertIsDisplayed().assertTextContains("Choose another time", substring = true)
        assertNull(saved)
    }

    @Test fun habitWeekdayShortcutPreservesReviewBeforeSaveAtLargeText() {
        var saved: Habit? = null
        content(dark = true) { HabitEditor(null, UiCopy("en"), false, { saved = it }, null) }
        compose.onNodeWithTag("editor.habit.title").performTextInput("Walk")
        scroll("editor.habit", "editor.habit.schedule.weekdays")
        compose.onNodeWithTag("editor.habit.schedule.weekdays").performClick()
        compose.onNodeWithTag("editor.habit.review").assertIsDisplayed().performClick()
        assertNull(saved)
        compose.onNodeWithTag("editor.habit.save").performClick()
        assertEquals(listOf(1, 2, 3, 4, 5), saved?.weekdays)
    }

    @Test fun todayShortcutMovesFromFutureMonthAndPreservesDateOnlyReview() {
        var saved: WorkTask? = null
        val today = LocalDate.now()
        content { TaskEditor(WorkTask(title = "Read notes", dueDate = today.plusYears(1).toString()), UiCopy("en"), false, { saved = it }, null) }
        scroll("editor.task", "editor.task.date")
        compose.onNodeWithTag("editor.task.date").performClick()
        compose.onNodeWithTag("editor.task.date.today").assertIsDisplayed().performClick()
        val month = today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(month, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(month, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("editor.task.date.confirm").performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        assertNull(saved)
        compose.onNodeWithText("Date only").assertExists()
        compose.onNodeWithTag("editor.task.save").performClick()
        assertEquals(today.toString(), saved?.dueDate)
        assertNull(saved?.dueTime)
    }

    private fun scroll(container: String, target: String) = compose.onNodeWithTag(container).performScrollToNode(hasTestTag(target))
    private fun content(dark: Boolean = false, editor: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HabitsTheme(dark) { Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding)) { editor() } } }
            }
        }
    }
}
