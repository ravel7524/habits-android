package ch.habits.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.Preferences
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.integrations.IntegrationPanel
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the app's real recovery callbacks with synthetic integration controls and records. */
class UiRecoveryRoutingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var directory: File
    private lateinit var repository: WorkspaceRepository
    private lateinit var recorded: WorkspaceSnapshot
    private lateinit var storedBytes: ByteArray
    private val focus = AtomicReference<String?>()
    private val calendarDay = AtomicReference<LocalDate?>()
    private val reportKind = AtomicReference<String?>()
    private val reportExits = AtomicInteger()
    private val taskID = "af000000-0000-0000-0000-000000000001"
    private val habitID = "af000000-0000-0000-0000-000000000002"

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-recovery-${UUID.randomUUID()}")
        check(directory.mkdirs())
        repository = WorkspaceRepository(File(directory, "state.json"))
        val day = LocalDate.now().toString()
        runBlocking {
            repository.savePreferences(Preferences(language = "en", theme = "light", userName = "Test"))
            repository.saveTask(WorkTask(id = taskID, title = "Recorded personal work", notes = "Synthetic source note"))
            repository.saveHabit(Habit(id = habitID, title = "Recorded routine"))
            repository.logHabit(habitID, day)
            repository.saveAppointment(Appointment(title = "Recorded appointment", startDate = day, startTime = "09:00",
                endDate = day, endTime = "10:00", timeZoneIdentifier = "UTC"))
        }
        recorded = repository.state.value.snapshot
        storedBytes = File(directory, "state.json").readBytes()
        compose.setContent {
            HabitsApp(repository,
                calendarSection = { requestedDay, onRecovery ->
                    SideEffect { calendarDay.set(requestedDay) }
                    Button(onClick = onRecovery, modifier = Modifier.testTag("test.calendar.recovery")) { Text("Review Calendar settings") }
                },
                reportAISection = { kind, _, _, onSetup ->
                    SideEffect { reportKind.set(kind) }
                    Button(onClick = onSetup, modifier = Modifier.testTag("test.ai.setup")) { Text("Set up local AI") }
                },
                integrationsSection = { requestedFocus ->
                    SideEffect { focus.set(requestedFocus) }
                    Column {
                        IntegrationPanel("calendar", "Calendar", focus = requestedFocus == "calendar", language = "en") {
                            Text("Calendar recovery controls", modifier = Modifier.testTag("test.focus.calendar"))
                        }
                        IntegrationPanel("localai", "Local AI", focus = requestedFocus == "localai", language = "en") {
                            Text("Local model setup controls", modifier = Modifier.testTag("test.focus.localai"))
                        }
                    }
                }, onReportAIExit = { reportExits.incrementAndGet() })
        }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun calendarRecoveryOpensFocusedSettingsAndReturnsToScheduleWithoutChangingRecords() {
        compose.onNodeWithTag("nav.planner").performClick()
        compose.onNodeWithTag("planner.mode.schedule").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("test.calendar.recovery"))
        assertNull("Schedule recovery is not limited to one day", calendarDay.get())
        compose.onNodeWithTag("test.calendar.recovery").performClick()
        assertFocusedSettings("calendar")
        assertRecordedDataUnchanged()
        compose.onNodeWithTag("detail.close").performClick()
        compose.onNodeWithTag("planner.mode.schedule").assertIsSelected()
        compose.onNodeWithTag("nav.planner").assertIsSelected()
        compose.onNodeWithTag("planner.mode.work").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.task.$taskID"))
        compose.onNodeWithTag("planner.task.$taskID").assertIsDisplayed()
        assertRecordedDataUnchanged()

        // Closing a focused section must clear its focus for ordinary Settings.
        compose.onNodeWithTag("settings.open").performClick()
        compose.onNodeWithTag("settings.screen").performScrollToNode(hasTestTag("integrations.open.calendar"))
        compose.waitForIdle()
        assertNull(focus.get())
        assertRecordedDataUnchanged()
    }

    @Test fun reportAiSetupOpensFocusedModelSettingsAndKeepsRecordedFactsOnReturn() {
        compose.onNodeWithTag("today.screen").performScrollToNode(hasTestTag("today.report.morning"))
        compose.onNodeWithTag("today.report.morning").performClick()
        compose.onNodeWithTag("report.screen").performScrollToNode(hasTestTag("test.ai.setup"))
        assertEquals("morning", reportKind.get())
        compose.onNodeWithTag("test.ai.setup").performClick()
        assertFocusedSettings("localai")
        assertTrue("Opening setup exits the report's AI scope", reportExits.get() > 0)
        assertRecordedDataUnchanged()
        compose.onNodeWithTag("detail.close").performClick()
        compose.onNodeWithTag("today.screen").assertExists()
        compose.onNodeWithTag("nav.today").assertIsSelected()
        compose.onNodeWithTag("nav.habits").performClick()
        compose.onNodeWithTag("habits.screen").performScrollToNode(hasTestTag("habits.row.$habitID"))
        compose.onNodeWithTag("habits.row.$habitID").assertIsDisplayed()
        assertRecordedDataUnchanged()
    }

    private fun assertFocusedSettings(section: String) {
        compose.onNodeWithTag("settings.screen").assertExists()
        compose.waitUntil(5_000) { focus.get() == section && compose.onAllNodesWithTag("test.focus.$section").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("test.focus.$section").assertIsDisplayed()
    }

    private fun assertRecordedDataUnchanged() {
        assertEquals(recorded, repository.state.value.snapshot)
        assertTrue("Routing must not rewrite the synthetic planning store", storedBytes.contentEquals(File(directory, "state.json").readBytes()))
    }
}
