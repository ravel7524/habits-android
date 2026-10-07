package ch.habits.android.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ch.habits.android.calendar.CalendarAgendaSection
import ch.habits.android.calendar.CalendarCoverage
import ch.habits.android.calendar.CalendarState
import ch.habits.android.core.*
import ch.habits.android.data.*
import ch.habits.android.desktop.*
import ch.habits.android.integrations.IntegrationPanel
import ch.habits.android.localai.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/** Opt-in visual evidence, not MainActivity, service, delivery or inference acceptance. */
class UiReviewCaptureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var output: File
    private lateinit var repository: WorkspaceRepository
    private lateinit var ai: LocalAIController
    private lateinit var isolatedContext: CaptureContext
    private val fontScale = mutableStateOf(1f)
    private val calendar = CalendarState(coverage = CalendarCoverage.OFF)
    private var sequence = 0
    private var checkpoint = "all"
    private val day = LocalDate.now()
    private val taskID = id(1)
    private val habitID = id(2)
    private val appointmentID = id(3)

    @Before fun setup() {
        assumeTrue("Screenshot review is opt-in: pass uiReviewCapture=true",
            InstrumentationRegistry.getArguments().getString("uiReviewCapture") == "true")
        checkpoint = InstrumentationRegistry.getArguments().getString("uiReviewCheckpoint") ?: "all"
        require(checkpoint in setOf("all", "editors")) { "uiReviewCheckpoint must be all or editors" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        // Match MainActivity's window setup; platform dialogs use their own window.
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ComponentActivity>().forEach { it.enableEdgeToEdge() }
        }
        val runID = UUID.randomUUID().toString()
        fixture = File(context.cacheDir, "ui-review-$runID").also { check(it.mkdirs()) }
        output = File(context.filesDir, "ui-review-captures/$runID").also { check(it.mkdirs()) }
        repository = WorkspaceRepository(File(fixture, "workspace.json"))
        runBlocking { repository.savePreferences(Preferences(language = "en", theme = "light", userName = "Maya")) }
        isolatedContext = CaptureContext(context, File(fixture, "model-storage"), "ui-review-$runID")
        ai = LocalAIController(isolatedContext)
        val desktop = DesktopConnection(object : DesktopCredentialStore {
            override fun read(): DesktopCredential? = null
            override fun save(value: DesktopCredential) { throw AssertionError("Capture must not pair a desktop") }
            override fun clear() = Unit
        })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                HabitsApp(repository,
                    desktopSection = { onPlan -> DesktopConnectionSection(desktop, repository, onPlan) },
                    calendarSection = { date, recover -> CalendarAgendaSection(calendar, "en", date, onOpenSettings = recover) },
                    reportAISection = { kind, snapshot, scope, setup ->
                        val facts = ReportFacts.build(kind, snapshot, calendar, LocalDate.now(), ZoneId.systemDefault(), "en")
                        ReportAISection(ai, facts, "en", scope, currentFingerprint = {
                            ReportFacts.build(kind, repository.state.value.snapshot, calendar, LocalDate.now(), ZoneId.systemDefault(), "en").fingerprint
                        }, onModelSetup = setup, snapshot = snapshot, calendar = calendar)
                    },
                    integrationsSection = { focus -> Column {
                        IntegrationPanel("calendar", "Calendar", focus = focus == "calendar", summary = "Off in this synthetic capture", language = "en") {
                            CalendarAgendaSection(calendar, "en")
                        }
                        IntegrationPanel("localai", "On-device models", focus = focus == "localai", summary = "Not downloaded or loaded", language = "en") {
                            ModelSettingsSection(ai, "en")
                        }
                    } })
            }
        }
    }

    @After fun cleanup() {
        if (::ai.isInitialized) runBlocking { ai.unload() }
        if (::isolatedContext.isInitialized) isolatedContext.clearPreferences()
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    @Test fun captureVerifiedEmptyPopulatedAndReviewedStates() {
        if (checkpoint == "editors") {
            seedRecordedWorkspace()
            // Match the full driver's source data without claiming its skipped UI prefix ran.
            runBlocking { repository.apply(repository.preview(plan(30, "Exam preparation", "Corporate Finance", "Corporate Finance exam"))) }
            captureEditors()
        captureStaleEditorFailure()
            captureDarkTail()
            return
        }
        assertTrue(repository.state.value.snapshot.tasks.isEmpty())
        capture("empty-today", "today.screen", "today.openPlanner")
        planner("work"); capture("empty-work", "planner.mode.work", "planner.add.task")
        planner("schedule"); capture("empty-schedule", "planner.mode.schedule", "planner.add.appointment")
        planner("semester"); reveal("semester.board.scroll", "semester.empty"); capture("empty-semester", "semester.empty")
        compose.onNodeWithTag("nav.habits").performClick(); capture("empty-habits", "habits.screen", "habits.add")

        seedRecordedWorkspace()
        compose.onNodeWithTag("nav.today").performClick(); capture("populated-today", "today.screen", "today.openPlanner")
        planner("work"); capture("populated-work", "planner.mode.work", "planner.add.task")
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.task.${id(14)}"))
        capture("populated-work-preparation", "planner.task.${id(14)}")
        reveal("planner.screen", "planner.showCompleted"); compose.onNodeWithTag("planner.showCompleted").performClick()
        compose.onNodeWithTag("planner.screen").performScrollToNode(hasTestTag("planner.task.${id(6)}"))
        capture("populated-work-completed", "planner.task.${id(6)}")
        planner("schedule"); reveal("planner.screen", "planner.appointment.$appointmentID"); capture("populated-schedule-records", "planner.appointment.$appointmentID")
        planner("semester"); reveal("semester.board.scroll", "semester.button.assessments")
        compose.onNodeWithTag("semester.button.assessments").performClick()
        reveal("semester.assessments.list", "semester.assessment.${id(13)}"); capture("populated-semester-assessments-list", "semester.assessment.${id(13)}")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("nav.habits").performClick(); capture("populated-habits", "habits.screen", "habits.add")
        captureReports()
        captureSettings()
        captureAssistant()
        captureEditors()
        captureStaleEditorFailure()
        captureDarkTail()
    }

    private fun captureDarkTail() {
        runBlocking { repository.savePreferences(repository.state.value.snapshot.preferences.copy(theme = "dark")) }
        compose.runOnIdle { fontScale.value = 1.3f }
        compose.onNodeWithTag("nav.today").performClick(); capture("dark-font13-today", "today.screen", "today.openPlanner")
        planner("work"); capture("dark-font13-work", "planner.mode.work", "planner.add.task")
        compose.onNodeWithTag("planner.add.task").performClick()
        compose.onNodeWithTag("editor.task.title").performTextInput("Plan tomorrow's reading")
        val before = repository.state.value.snapshot
        captureUnwritten("dark-font13-task-draft", before, "editor.task.title", "editor.task.review")
        reveal("editor.task", "editor.task.date"); compose.onNodeWithTag("editor.task.date").performClick()
        captureUnwritten("dark-font13-task-datepicker-unconfirmed", before, "editor.task.date.dialog", "editor.task.date.cancel")
        compose.onNodeWithTag("editor.task.date.cancel").performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        captureUnwritten("dark-font13-task-reviewed", before, "editor.task.save")
        closeDetail()
        openReport("morning"); capture("dark-font13-morning-report", "report.source")
        compose.onNodeWithTag("report.screen").performScrollToNode(hasTestTag("localai.report.details"))
        capture("dark-font13-report-ai-unloaded", "localai.report.setup", "localai.report.details")
        closeDetail()
        assertFalse(ai.state.value.loaded)
        assertFalse(ai.state.value.status == LocalAIStatus.GENERATING)
    }

    private fun seedRecordedWorkspace() = runBlocking {
        repository.saveTask(WorkTask(id = taskID, title = "Read Statistics chapter 4", dueDate = day.toString(), minutes = 25))
        repository.saveTask(WorkTask(id = id(4), title = "Submit problem set", kind = "assignment", dueDate = day.plusDays(1).toString(), minutes = 40))
        repository.saveTask(WorkTask(id = id(5), title = "Prepare seminar questions", minutes = 0))
        repository.saveTask(WorkTask(id = id(6), title = "Review lecture notes", completed = true, completedAt = Instant.now().toString()))
        repository.saveTask(WorkTask(id = id(7), title = "Previous reading task")); repository.archiveTask(id(7))
        repository.saveAppointment(Appointment(id = appointmentID, title = "Economics study group", startDate = day.toString(), startTime = "14:00",
            endDate = day.toString(), endTime = "15:00", timeZoneIdentifier = ZoneId.systemDefault().id))
        repository.saveHabit(Habit(id = habitID, title = "Read for ten minutes")); repository.logHabit(habitID, day.toString())
        repository.saveHabit(Habit(id = id(8), title = "Walk outside"))
        repository.saveHabit(Habit(id = id(9), title = "Evening stretching")); repository.logHabit(id(9), day.minusDays(1).toString()); repository.archiveHabit(id(9))
        repository.apply(repository.preview(plan(10, "Autumn semester", "Statistics", "Statistics exam")))
    }

    private fun captureReports() {
        for (kind in listOf("morning", "evening", "weekly")) {
            openReport(kind)
            capture("$kind-report-recorded-facts", "report.source")
            compose.onNodeWithTag("report.screen").performScrollToNode(hasTestTag("localai.report.details"))
            assertFalse(ai.state.value.loaded)
            capture("$kind-report-ai-unloaded", "localai.report.setup", "localai.report.details")
            closeDetail()
        }
    }

    private fun captureSettings() {
        compose.onNodeWithTag("settings.open").performClick(); capture("settings-compact", "settings.screen", "integrations.open.profile")
        reveal("settings.screen", "integrations.open.data"); compose.onNodeWithTag("integrations.open.data").performClick()
        reveal("settings.screen", "settings.export"); capture("settings-backup-controls", "settings.export")
        reveal("settings.screen", "integrations.open.data"); compose.onNodeWithTag("integrations.open.data").performClick()
        reveal("settings.screen", "integrations.open.archive"); compose.onNodeWithTag("integrations.open.archive").performClick()
        compose.onNodeWithText("Previous reading task").assertExists()
        capture("settings-archive-expanded", "integrations.open.archive")
        closeDetail()
    }

    private fun captureAssistant() {
        openLegacyAssistant()
        capture("assistant-entry", "assistant.plan.screen")
        reveal("assistant.plan.screen", "desktop.section")
        compose.onNodeWithText("Connect Codex or Claude").performClick()
        reveal("assistant.plan.screen", "desktop.invitation"); capture("assistant-desktop-unpaired", "desktop.invitation")
        compose.onNodeWithTag("assistant.plan.close").performClick()
        openLegacyAssistant()
        val proposal = plan(30, "Exam preparation", "Corporate Finance", "Corporate Finance exam")
        val before = repository.state.value.snapshot
        reveal("assistant.plan.screen", "assistant.plan.input")
        compose.onNodeWithTag("assistant.plan.input").performTextInput(AcademicPlanCodec.encode(proposal))
        reveal("assistant.plan.screen", "assistant.plan.review"); compose.onNodeWithTag("assistant.plan.review").performClick()
        awaitAssistantState("Proposal review")
        reveal("assistant.plan.screen", "assistant.plan.assessment.${id(33)}")
        captureUnwritten("assistant-proposal-preview-unsaved", before, "assistant.plan.assessment.${id(33)}")
        reveal("assistant.plan.screen", "assistant.plan.apply"); captureUnwritten("assistant-proposal-confirmation-unsaved", before, "assistant.plan.apply")
        compose.onNodeWithTag("assistant.plan.apply").performClick()
        compose.waitUntil(10_000) { repository.state.value.snapshot.receipts.any { it.proposalID == proposal.proposalID && it.state == ReceiptState.APPLIED } }
        reveal("assistant.plan.screen", "assistant.plan.receipt"); capture("assistant-receipt-applied", "assistant.plan.receipt")
        compose.onNodeWithTag("assistant.plan.close").performClick()
        openLegacyAssistant()
        val errorBaseline = repository.state.value.snapshot
        reveal("assistant.plan.screen", "assistant.plan.input"); compose.onNodeWithTag("assistant.plan.input").performTextInput("{ not a proposal }")
        reveal("assistant.plan.screen", "assistant.plan.review"); compose.onNodeWithTag("assistant.plan.review").performClick()
        awaitAssistantState("Proposal error")
        reveal("assistant.plan.screen", "assistant.plan.error"); captureUnwritten("assistant-invalid-json-error", errorBaseline, "assistant.plan.error")
        compose.onNodeWithTag("assistant.plan.close").performClick()
    }

    private fun captureEditors() {
        planner("work"); compose.onNodeWithTag("planner.add.task").performClick()
        val taskBefore = repository.state.value.snapshot
        compose.onNodeWithTag("editor.task.title").performTextInput("Prepare the next study session")
        captureUnwritten("task-draft-unsaved", taskBefore, "editor.task.title", "editor.task.review")
        reveal("editor.task", "editor.task.date"); compose.onNodeWithTag("editor.task.date").performClick()
        captureUnwritten("task-datepicker-unconfirmed", taskBefore, "editor.task.date.dialog", "editor.task.date.cancel")
        compose.onNodeWithTag("editor.task.date.today").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor.task.date.confirm").assertIsDisplayed().performClick()
        reveal("editor.task", "editor.task.time"); compose.onNodeWithTag("editor.task.time").performClick()
        captureUnwritten("task-timepicker-unconfirmed", taskBefore, "editor.task.time.dialog", "editor.task.time.cancel")
        compose.onNodeWithTag("editor.task.time.cancel").performClick()
        compose.onNodeWithTag("editor.task.time").assertTextContains("Add time")
        reveal("editor.task", "editor.task.details.toggle"); compose.onNodeWithTag("editor.task.details.toggle").performClick()
        reveal("editor.task", "editor.task.minutes"); compose.onNodeWithTag("editor.task.minutes").performTextInput("1441")
        compose.onNodeWithTag("editor.task.review").performClick(); captureUnwritten("task-error-invalid-estimate", taskBefore, "editor.error")
        reveal("editor.task", "editor.task.minutes"); compose.onNodeWithTag("editor.task.minutes").performTextReplacement("25")
        compose.onNodeWithTag("editor.task.review").performClick()
        compose.onNodeWithText("Date only").assertExists()
        captureUnwritten("task-reviewed-date-only-unsaved", taskBefore, "editor.task.save")
        closeDetail()

        planner("schedule"); compose.onNodeWithTag("planner.add.appointment").performClick()
        val appointmentBefore = repository.state.value.snapshot
        compose.onNodeWithTag("editor.appointment.title").performTextInput("Office hours")
        captureUnwritten("appointment-draft-unsaved", appointmentBefore, "editor.appointment.title", "editor.appointment.review")
        compose.onNodeWithTag("editor.appointment.review").performClick(); captureUnwritten("appointment-reviewed-unsaved", appointmentBefore, "editor.appointment.save")
        closeDetail()

        compose.onNodeWithTag("nav.habits").performClick(); compose.onNodeWithTag("habits.add").performClick()
        val habitBefore = repository.state.value.snapshot
        compose.onNodeWithTag("editor.habit.title").performTextInput("Practice Spanish")
        captureUnwritten("habit-draft-unsaved", habitBefore, "editor.habit.title", "editor.habit.review")
        for (weekday in 1..7) {
            reveal("editor.habit", "editor.habit.day.$weekday"); compose.onNodeWithTag("editor.habit.day.$weekday").performClick()
        }
        compose.onNodeWithTag("editor.habit.review").performClick(); captureUnwritten("habit-error-no-days", habitBefore, "editor.error")
        reveal("editor.habit", "editor.habit.schedule.weekdays"); compose.onNodeWithTag("editor.habit.schedule.weekdays").performClick()
        compose.onNodeWithTag("editor.habit.review").performClick(); captureUnwritten("habit-reviewed-unsaved", habitBefore, "editor.habit.save")
        closeDetail()
    }

    private fun captureStaleEditorFailure() {
        val staleID = id(80)
        runBlocking { repository.saveTask(WorkTask(id = staleID, title = "Retain the newer task completion")) }
        planner("work")
        reveal("planner.screen", "planner.task.$staleID")
        compose.onNodeWithTag("planner.task.$staleID").performClick()
        compose.onNodeWithTag("editor.task.review").performClick()
        runBlocking { repository.completeTask(staleID) }
        val current = repository.state.value.snapshot
        compose.onNodeWithTag("editor.task.save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor.saveError").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("A stale save must preserve newer source records", current, repository.state.value.snapshot)
        assertTrue(repository.state.value.snapshot.tasks.single { it.id == staleID }.completed)
        capture("editor-save-failure-newer-record-retained", "editor.saveError", "editor.saveError.close")
        compose.onNodeWithTag("editor.saveError.close").performClick()
        compose.onNodeWithTag("editor.task.save").assertIsDisplayed()
        closeDetail()
    }

    private fun plan(base: Int, semester: String, course: String, exam: String) = AcademicPlan(proposalID = id(base), source = "Synthetic university timetable",
        semester = PlanSemester(id(base + 1), semester, day.minusDays(21).toString(), 14, ZoneId.systemDefault().id),
        courses = listOf(PlanCourse(id(base + 2), course)), assessments = listOf(PlanAssessment(id(base + 3), id(base + 2), exam, "exam", day.plusDays(5).toString(), "10:00")),
        preparationTasks = listOf(PlanPreparation(id(base + 4), id(base + 3), "Prepare for $course", estimatedWorkMinutes = 45)))

    private fun planner(mode: String) {
        compose.onNodeWithTag("nav.planner").performClick()
        reveal("planner.screen", "planner.mode.$mode"); compose.onNodeWithTag("planner.mode.$mode").performClick()
        compose.onNodeWithTag("planner.mode.$mode").assertIsSelected()
    }
    private fun openLegacyAssistant() {
        planner("semester")
        compose.onNodeWithTag("semester.board.scroll").performScrollToIndex(0)
        if (compose.onAllNodesWithTag("semester.button.tools").fetchSemanticsNodes().isNotEmpty()) {
            if (compose.onAllNodesWithTag("semester.button.examPlan").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("semester.button.tools").assertIsDisplayed().performClick()
        } else reveal("semester.board.scroll", "semester.button.examPlan")
        compose.onNodeWithTag("semester.button.examPlan").assertIsDisplayed()
        compose.onNodeWithTag("semester.button.examPlan").performClick()
    }
    private fun openReport(kind: String) {
        compose.onNodeWithTag("nav.today").performClick()
        reveal("today.screen", "today.report.$kind"); compose.onNodeWithTag("today.report.$kind").performClick()
        compose.onNodeWithTag("report.source").assertIsDisplayed()
    }
    private fun closeDetail() { compose.onNodeWithTag("detail.close").performClick() }
    private fun awaitAssistantState(description: String) {
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("assistant.plan.screen").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == description
        }
    }
    private fun reveal(container: String, tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            try { compose.onNodeWithTag(tag).assertIsDisplayed(); return } catch (_: AssertionError) { }
        }
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun captureUnwritten(name: String, before: WorkspaceSnapshot, vararg markers: String) {
        assertEquals("Review must not persist source records", before, repository.state.value.snapshot)
        capture(name, *markers, staged = true)
    }
    private fun capture(name: String, vararg markers: String, staged: Boolean = false) {
        compose.waitForIdle()
        markers.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        assertFalse("Capture must not load a model", ai.state.value.loaded)
        assertFalse("Capture must not run inference", ai.state.value.status == LocalAIStatus.GENERATING)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        markers.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        val stem = "%02d-%s".format(++sequence, name)
        val activity = AtomicReference("unknown test-host Activity")
        instrumentation.runOnMainSync {
            activity.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.javaClass?.name ?: "no resumed Activity")
        }
        try {
            File(output, "$stem.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val snapshot = repository.state.value.snapshot
            val metadata = JSONObject().put("capture", name).put("capturedAt", Instant.now().toString())
                .put("checkpoint", checkpoint).put("prefixScreensExercised", checkpoint == "all")
                .put("seeding", if (checkpoint == "editors") "Repository APIs recreate the populated editor baseline; assistant prefix seeded through preview/apply, not exercised in UI"
                    else "Repository APIs seed populated records; assistant review/apply is exercised in UI")
                .put("verifiedVisibleMarkers", JSONArray(markers.toList())).put("stagedNoWriteVerified", staged)
                .put("source", "Synthetic private-cache WorkspaceRepository").put("activity", activity.get())
                .put("testHostOnly", true).put("mainActivityOrServiceAcceptance", false).put("systemBarsIncluded", true)
                .put("osFontScale", context.resources.configuration.fontScale.toDouble()).put("composeFontScale", fontScale.value.toDouble())
                .put("osFontScaleChanged", false).put("theme", snapshot.preferences.theme).put("language", snapshot.preferences.language)
                .put("calendarSource", "Real CalendarAgendaSection with injected OFF state; no provider query")
                .put("aiSource", "Real ReportAISection; isolated missing model store; no download, load or inference")
                .put("desktopSource", "Real DesktopConnectionSection; unpaired in-memory credentials; no network action")
                .put("workspaceRevision", snapshot.revision).put("taskCount", snapshot.tasks.size).put("habitCount", snapshot.habits.size)
                .put("appointmentCount", snapshot.appointments.size).put("assessmentCount", snapshot.assessments.size)
                .put("pixelWidth", bitmap.width).put("pixelHeight", bitmap.height)
            File(output, "$stem.json").writeText(metadata.toString(2), Charsets.UTF_8)
        } finally { bitmap.recycle() }
    }

    private class CaptureContext(base: Context, private val storage: File, private val prefix: String) : ContextWrapper(base) {
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = storage
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix-$name"; preferenceNames += owned
            return super.getSharedPreferences(owned, mode)
        }
        fun clearPreferences() { preferenceNames.forEach { super.deleteSharedPreferences(it) } }
    }
    private fun id(number: Int): String = "cc000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
