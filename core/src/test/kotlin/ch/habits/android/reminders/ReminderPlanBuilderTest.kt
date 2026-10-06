package ch.habits.android.reminders

import ch.habits.android.data.*
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ReminderPlanBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private fun task(title: String = "Review", time: String? = "10:00") = WorkTask(title = title, dueDate = "2026-10-07", dueTime = time, sourceZone = "UTC")
    private fun academic(completed: Boolean = false): WorkspaceSnapshot {
        val semester = AcademicSemester("semester", "Autumn", "2026-09-14", 14, "Europe/Zurich")
        val course = AcademicCourse("course", semester.id, "Statistics")
        val exam = AcademicAssessment("exam", semester.id, course.id, "Final exam", "exam", "2026-10-07", completed = completed)
        return WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(exam))
    }
    @Test fun allDefaultsAreOffAndPlanningNeverMutatesSource() {
        val snapshot = academic().copy(tasks = listOf(task()), habits = listOf(Habit(title = "Read")))
        assertTrue(ReminderPlanBuilder.build(snapshot, ReminderPreferences(), now, zone).isEmpty())
        assertFalse(snapshot.tasks.single().completed)
        assertFalse(snapshot.assessments.single().completed)
    }
    @Test fun taskClocksAndUnknownTimesRetainExplicitDateSemantics() {
        val timed = task().copy(sourceZone = "Europe/Zurich")
        val untimed = task("Unknown clock", null).copy(sourceZone = "Europe/Zurich")
        val plans = ReminderPlanBuilder.build(WorkspaceSnapshot(tasks = listOf(timed, untimed)), ReminderPreferences(tasksEnabled = true), now, zone)
        val first = plans.first { it.sourceID == untimed.id }
        assertEquals(Instant.parse("2026-10-07T07:00:00Z"), first.instant)
        assertFalse(first.timeSupplied)
        assertEquals("2026-10-07", first.sourceDate)
        assertEquals("Europe/Zurich", first.sourceZone)
        assertEquals(Instant.parse("2026-10-07T08:00:00Z"), plans.first { it.sourceID == timed.id }.instant)
    }
    @Test fun completedArchivedAndUndatedWorkNeverEnrolls() {
        val snapshot = WorkspaceSnapshot(tasks = listOf(task().copy(completed = true), task().copy(archived = true), WorkTask(title = "No deadline")))
        assertTrue(ReminderPlanBuilder.build(snapshot, ReminderPreferences(tasksEnabled = true), now, zone).isEmpty())
    }
    @Test fun preparationRequiresIndependentOptInAndOwnCompletion() {
        val snapshot = academic(completed = true).copy(tasks = listOf(task().copy(sourceAssessmentID = "exam")))
        assertTrue(ReminderPlanBuilder.build(snapshot, ReminderPreferences(tasksEnabled = true), now, zone).isEmpty())
        val prefs = ReminderPreferences(tasksEnabled = true, includePreparation = true)
        assertEquals(1, ReminderPlanBuilder.build(snapshot, prefs, now, zone).size)
        assertTrue(ReminderPlanBuilder.build(snapshot.copy(tasks = snapshot.tasks.map { it.copy(completed = true) }), prefs, now, zone).isEmpty())
        assertTrue(ReminderPlanBuilder.build(academic(), ReminderPreferences(tasksEnabled = true), now, zone).isEmpty(), "Work opt-in cannot enroll exams")
    }
    @Test fun assessmentsRequireActiveParentsAndIndependentCompletion() {
        val snapshot = academic()
        val prefs = ReminderPreferences(assessmentsEnabled = true)
        val plan = ReminderPlanBuilder.build(snapshot, prefs, now, zone).single()
        assertEquals(ReminderKind.ASSESSMENT, plan.kind)
        assertEquals(Instant.parse("2026-10-07T07:00:00Z"), plan.instant)
        assertFalse(plan.timeSupplied, "09:00 is an alert time, not an invented exam start")
        assertEquals("semester", plan.route)
        assertTrue(ReminderPlanBuilder.build(snapshot.copy(semesters = snapshot.semesters.map { it.copy(archived = true) }), prefs, now, zone).isEmpty())
        assertTrue(ReminderPlanBuilder.build(snapshot.copy(courses = snapshot.courses.map { it.copy(archived = true) }), prefs, now, zone).isEmpty())
        assertTrue(ReminderPlanBuilder.build(academic(completed = true), prefs, now, zone).isEmpty())
        val timed = snapshot.copy(assessments = snapshot.assessments.map { it.copy(dueTime = "10:00") })
        assertEquals(Instant.parse("2026-10-07T07:30:00Z"), ReminderPlanBuilder.build(timed, prefs, now, zone).single().instant)
    }
    @Test fun appointmentLeadAndQuietHoursNeverInventAUsefulLateReminder() {
        val early = Appointment(title = "Morning", startDate = "2026-10-07", startTime = "07:15", endDate = "2026-10-07", endTime = "08:00", timeZoneIdentifier = "UTC")
        val night = early.copy(id = "night", title = "Night", startTime = "03:00", endTime = "04:00")
        val plans = ReminderPlanBuilder.build(WorkspaceSnapshot(appointments = listOf(early, night)), ReminderPreferences(appointmentsEnabled = true), now, zone)
        assertEquals(1, plans.size)
        assertEquals(Instant.parse("2026-10-07T07:00:00Z"), plans.single().instant)
        assertEquals(Instant.parse("2026-10-07T07:15:00Z"), plans.single().expiry)
    }
    @Test fun boundedQueueAlwaysSelectsNearestFutureRecords() {
        val items = (0 until 300).map { index ->
            task("Work $index", "%02d:%02d".format(8 + index / 60, index % 60))
        }
        val plans = ReminderPlanBuilder.build(WorkspaceSnapshot(tasks = items), ReminderPreferences(tasksEnabled = true), now, zone)
        assertEquals(64, plans.size)
        assertEquals(plans.map { it.instant }.sorted(), plans.map { it.instant })
        assertEquals(Instant.parse("2026-10-07T09:03:00Z"), plans.last().instant)
        assertEquals(64, plans.map { it.token }.distinct().size)
    }
    @Test fun dstGapsFoldsAndMissingZonesFailConservatively() {
        val prefs = ReminderPreferences(tasksEnabled = true)
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val item = task(time = "02:30").copy(dueDate = date, sourceZone = "Europe/Zurich")
            assertTrue(ReminderPlanBuilder.build(WorkspaceSnapshot(tasks = listOf(item)), prefs, Instant.parse("2026-01-01T00:00:00Z"), zone).isEmpty())
        }
        val item = task(time = "01:45").copy(dueDate = "2026-04-05", sourceZone = "Australia/Lord_Howe")
        assertTrue(ReminderPlanBuilder.build(WorkspaceSnapshot(tasks = listOf(item)), prefs, Instant.parse("2026-01-01T00:00:00Z"), zone).isEmpty())
        assertTrue(ReminderPlanBuilder.build(WorkspaceSnapshot(tasks = listOf(task().copy(sourceZone = "Missing/Zone"))), prefs, now, zone).isEmpty())
    }
    @Test fun sourceChangesCompletionAndExpiryInvalidateReceiverTokens() {
        val item = task()
        val snapshot = WorkspaceSnapshot(tasks = listOf(item))
        val prefs = ReminderPreferences(tasksEnabled = true)
        val plan = ReminderPlanBuilder.build(snapshot, prefs, now, zone).single()
        assertNotNull(ReminderPlanBuilder.current(snapshot, prefs, plan.token, plan.instant.plusSeconds(30), zone))
        assertNull(ReminderPlanBuilder.current(snapshot.copy(tasks = listOf(item.copy(title = "Changed"))), prefs, plan.token, plan.instant, zone))
        assertNull(ReminderPlanBuilder.current(snapshot.copy(tasks = listOf(item.copy(completed = true))), prefs, plan.token, plan.instant, zone))
        assertNull(ReminderPlanBuilder.current(snapshot, prefs, plan.token, plan.expiry, zone))
        assertTrue(ReminderPlanBuilder.build(snapshot, prefs, plan.instant.plusSeconds(1), zone).isEmpty(), "New overdue sources are not retroactively scheduled")
    }
    @Test fun deliveredVersionDoesNotReplayOnActivationOrTimezoneTravel() {
        val item = task(nullTimeTitle, null).copy(sourceZone = null)
        val snapshot = WorkspaceSnapshot(tasks = listOf(item))
        val prefs = ReminderPreferences(tasksEnabled = true)
        val before = Instant.parse("2026-10-07T06:00:00Z")
        val european = ReminderPlanBuilder.build(snapshot, prefs, before, ZoneId.of("Europe/Zurich")).single()
        val later = Instant.parse("2026-10-07T08:00:00Z")
        val american = ReminderPlanBuilder.build(snapshot, prefs, later, ZoneId.of("America/New_York")).single()
        assertEquals(european.token, american.token)
        assertTrue(ReminderPlanBuilder.build(snapshot, prefs, later, ZoneId.of("America/New_York"), setOf(european.token)).isEmpty())
    }
    private val nullTimeTitle = "Date only"
    @Test fun snoozeIsBoundedAndCannotReviveCompletedOrExpiredSources() {
        val item = task()
        val snapshot = WorkspaceSnapshot(tasks = listOf(item))
        val prefs = ReminderPreferences(tasksEnabled = true)
        val plan = ReminderPlanBuilder.build(snapshot, prefs, now, zone).single()
        val after = plan.instant.plusSeconds(60)
        val snooze = after.plusSeconds(15 * 60)
        val queued = ReminderPlanBuilder.build(snapshot, prefs, after, zone, setOf(plan.token), mapOf(plan.token to snooze))
        assertEquals(snooze, queued.single().instant)
        assertEquals(plan.token, queued.single().token)
        assertTrue(ReminderPlanBuilder.build(snapshot.copy(tasks = listOf(item.copy(completed = true))), prefs, after, zone, setOf(plan.token), mapOf(plan.token to snooze)).isEmpty())
        assertTrue(ReminderPlanBuilder.build(snapshot, prefs, after, zone, setOf(plan.token), mapOf(plan.token to plan.expiry.plusSeconds(1))).isEmpty())
    }
    @Test fun habitInvitationAggregatesOnlyScheduledUnrecordedRoutines() {
        val first = Habit(title = "Read", weekdays = listOf(3))
        val second = Habit(title = "Walk", weekdays = listOf(3))
        val snapshot = WorkspaceSnapshot(habits = listOf(first, second))
        val prefs = ReminderPreferences(habitsEnabled = true)
        val plan = ReminderPlanBuilder.build(snapshot, prefs, now, zone).first { it.sourceDate == "2026-10-07" }
        val partial = snapshot.copy(habitLogs = listOf(HabitLog(first.id, "2026-10-07")))
        assertEquals(plan.token, ReminderPlanBuilder.build(partial, prefs, now, zone).first { it.sourceDate == "2026-10-07" }.token)
        val completed = partial.copy(habitLogs = partial.habitLogs + HabitLog(second.id, "2026-10-07"))
        assertTrue(ReminderPlanBuilder.build(completed, prefs, now, zone).none { it.sourceDate == "2026-10-07" })
    }
    @Test fun reportInvitationsAreLocalFutureRoutesWithoutSourceOrGeneration() {
        val plans = ReminderPlanBuilder.build(WorkspaceSnapshot(), ReminderPreferences(morningEnabled = true, eveningEnabled = true, weeklyEnabled = true), now, zone)
        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.route == "report" && it.sourceID == null && it.reportKind in setOf("morning", "evening", "weekly") })
        assertTrue(plans.filter { it.kind == ReminderKind.WEEKLY_REPORT }.all { java.time.LocalDate.parse(it.sourceDate).dayOfWeek.value == 7 })
        assertTrue(plans.all { it.instant > now && it.instant.atZone(zone).hour in 7..21 })
    }
}
