package ch.habits.android.widgets

import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicSemester
import ch.habits.android.data.Appointment
import ch.habits.android.data.Habit
import ch.habits.android.data.HabitLog
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.data.WorkspaceState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetProjectionTest {
    private val now = Instant.parse("2026-10-05T10:00:00Z")
    private val zone = ZoneId.of("Europe/Zurich")
    private val monday = LocalDate.parse("2026-10-05")
    private val habit = Habit(id = "habit-1", title = "Walk", weekdays = listOf(1))

    @Test fun nextUpCapsQueueAndCountsAllPendingDatedWork() {
        val tasks = (1..10).map { WorkTask(id = "task-$it", title = "Task $it", dueDate = "2026-10-${(5 + it).toString().padStart(2, '0')}") }
        val state = WorkspaceState(WorkspaceSnapshot(tasks = tasks + listOf(
            WorkTask(title = "Undated"), WorkTask(title = "Completed", dueDate = "2026-10-01", completed = true),
            WorkTask(title = "Archived", dueDate = "2026-10-01", archived = true))))
        val projection = WidgetProjection.nextUp(state, now, zone, limit = 100)
        assertEquals(10, projection.totalCount)
        assertEquals(listOf("Task 1", "Task 2", "Task 3"), projection.entries.map { it.title })
        assertEquals(now, projection.generatedAt)
    }

    @Test fun dateOnlyDeadlineKeepsCivilDayAndDoesNotBecomeMidnight() {
        val state = WorkspaceState(WorkspaceSnapshot(tasks = listOf(
            WorkTask(title = "Read notes", dueDate = "2026-10-05", sourceZone = "Europe/Zurich"))))
        val projection = WidgetProjection.nextUp(state, Instant.parse("2026-10-05T20:30:00Z"), zone)
        val item = projection.entries.single()
        assertEquals("2026-10-05", item.date)
        assertNull(item.time)
        assertFalse(item.overdue, "A date-only deadline stays pending through its civil day")
        val text = WidgetCopy("en").whenText(item)
        assertTrue(text.contains("date only"))
        assertTrue(text.contains("Europe/Zurich"))
        assertFalse(text.contains("00:00"))
    }

    @Test fun sourceCivilMidnightControlsOverdueWithoutChangingDisplayedDate() {
        val state = WorkspaceState(WorkspaceSnapshot(tasks = listOf(
            WorkTask(title = "Island deadline", dueDate = "2026-10-05", sourceZone = "Pacific/Kiritimati"))))
        val item = WidgetProjection.nextUp(state, Instant.parse("2026-10-05T13:00:00Z"), ZoneId.of("America/Los_Angeles")).entries.single()
        assertTrue(item.overdue, "The source is already on October 6, even while the device is on October 5")
        assertEquals("2026-10-05", item.date)
        assertNull(item.time)
        assertEquals("Pacific/Kiritimati", item.sourceZone)
    }

    @Test fun unknownSourceZoneIsDisclosedAsDeviceZone() {
        val item = WidgetProjection.nextUp(WorkspaceState(WorkspaceSnapshot(tasks = listOf(
            WorkTask(title = "Date only", dueDate = "2026-10-06")))), now, zone).entries.single()
        assertNull(item.sourceZone)
        assertTrue(WidgetCopy("en").whenText(item).contains("device time zone"))
    }

    @Test fun timedDeadlineUsesSourceClockForOrderingAndKeepsThatClock() {
        val state = WorkspaceState(WorkspaceSnapshot(tasks = listOf(
            WorkTask(id = "a", title = "Assignment", kind = "assignment", dueDate = "2026-10-05", dueTime = "09:00", sourceZone = "Europe/Zurich"),
            WorkTask(id = "b", title = "Date only", dueDate = "2026-10-05", sourceZone = "Europe/Zurich"))))
        val items = WidgetProjection.nextUp(state, now, ZoneId.of("America/New_York")).entries
        assertEquals(listOf("Assignment", "Date only"), items.map { it.title })
        assertEquals(WidgetEntryKind.ASSIGNMENT, items.first().kind)
        assertEquals("09:00", items.first().time)
        assertTrue(items.first().overdue)
    }

    @Test fun appointmentsIncludeOngoingAndFutureAndExcludeEndedOrArchived() {
        fun appointment(title: String, start: String, end: String, archived: Boolean = false) =
            Appointment(title = title, startDate = "2026-10-05", startTime = start, endDate = "2026-10-05", endTime = end, timeZoneIdentifier = "UTC", archived = archived)
        val projection = WidgetProjection.nextUp(WorkspaceState(WorkspaceSnapshot(appointments = listOf(
            appointment("Ended", "08:00", "10:00"), appointment("Now", "09:30", "10:30"),
            appointment("Later", "11:00", "12:00"), appointment("Hidden", "11:00", "12:00", true)))), now, zone)
        assertEquals(listOf("Now", "Later"), projection.entries.map { it.title })
        assertTrue(projection.entries.first().ongoing)
        assertFalse(projection.entries.last().ongoing)
        assertFalse(projection.entries.first().overdue)
    }

    @Test fun assessmentRequiresActiveMatchingSemesterAndCourse() {
        val semester = AcademicSemester("semester", "Autumn", "2026-09-14", 14, "Europe/Zurich")
        val course = AcademicCourse("course", semester.id, "Statistics")
        val exam = AcademicAssessment("exam", semester.id, course.id, "Final exam", "exam", "2027-01-20", "09:00")
        val snapshot = WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(exam))
        assertEquals("Europe/Zurich", WidgetProjection.nextUp(WorkspaceState(snapshot), now, zone).entries.single().sourceZone)
        assertEquals(0, WidgetProjection.nextUp(WorkspaceState(snapshot.copy(courses = listOf(course.copy(archived = true)))), now, zone).totalCount)
        assertEquals(0, WidgetProjection.nextUp(WorkspaceState(snapshot.copy(semesters = listOf(semester.copy(archived = true)))), now, zone).totalCount)
        assertEquals(0, WidgetProjection.nextUp(WorkspaceState(snapshot.copy(assessments = listOf(exam.copy(completed = true)))), now, zone).totalCount)
    }

    @Test fun emptyAndRecoveryContainNoSourceEntries() {
        val empty = WidgetProjection.nextUp(WorkspaceState(), now, zone)
        assertFalse(empty.recoveryRequired)
        assertEquals(0, empty.totalCount)
        val recovery = WorkspaceState(WorkspaceSnapshot(tasks = listOf(WorkTask(title = "Private title", dueDate = "2026-10-06")), habits = listOf(habit)), recoveryRequired = true)
        assertTrue(WidgetProjection.nextUp(recovery, now, zone).recoveryRequired)
        assertTrue(WidgetProjection.nextUp(recovery, now, zone).entries.isEmpty())
        assertTrue(WidgetProjection.habitsToday(recovery, now, zone).entries.isEmpty())
    }

    @Test fun habitCountsUseOnlyActiveScheduledHabitsAndTodaysCompletedLogs() {
        val habits = listOf(habit, Habit(id = "habit-2", title = "Read", weekdays = listOf(1)),
            Habit(id = "habit-3", title = "Tuesday", weekdays = listOf(2)), Habit(id = "habit-4", title = "Archived", archived = true))
        val logs = listOf(HabitLog(habit.id, monday.toString()), HabitLog("habit-2", monday.minusDays(1).toString()),
            HabitLog("habit-3", monday.toString()), HabitLog("habit-4", monday.toString()))
        val projection = WidgetProjection.habitsToday(WorkspaceState(WorkspaceSnapshot(habits = habits, habitLogs = logs)), now, zone)
        assertEquals(2, projection.scheduledCount)
        assertEquals(1, projection.completedCount)
        assertEquals(listOf("Read", "Walk"), projection.entries.map { it.title })
        assertEquals(monday.toString(), projection.date)
        assertEquals(listOf(false, true), projection.entries.map { it.completed })
    }

    @Test fun habitsCapVisibleRowsWithoutLosingTotalScheduledCount() {
        val habits = (1..20).map { Habit(id = "habit-$it", title = "Routine $it") }
        val state = WorkspaceState(WorkspaceSnapshot(habits = habits, habitLogs = listOf(HabitLog("habit-20", monday.toString()))))
        val projection = WidgetProjection.habitsToday(state, now, zone, limit = 100)
        assertEquals(3, projection.entries.size)
        assertEquals(20, projection.scheduledCount)
        assertEquals(1, projection.completedCount)
        assertEquals(0, WidgetProjection.habitsToday(state, now, zone, limit = 0).entries.size)
    }

    @Test fun freshHabitActionRequiresExpectedStateAndOppositeTarget() {
        val state = WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit)))
        val action = WidgetHabitAction(habit.id, monday.toString(), 4, false, true)
        assertTrue(WidgetProjection.canApplyHabitAction(state, action, monday))
        assertFalse(WidgetProjection.canApplyHabitAction(state, action.copy(expectedCompleted = true), monday))
        assertFalse(WidgetProjection.canApplyHabitAction(state, action.copy(targetCompleted = false), monday))
    }

    @Test fun oldDayOrOldRevisionNeverWritesATodayLog() {
        val state = WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit)))
        val action = WidgetHabitAction(habit.id, monday.toString(), 4, false, true)
        assertFalse(WidgetProjection.canApplyHabitAction(state, action.copy(date = monday.minusDays(1).toString()), monday))
        assertFalse(WidgetProjection.canApplyHabitAction(state, action.copy(revision = 3), monday))
        assertFalse(WidgetProjection.canApplyHabitAction(state, action, monday.plusDays(1)))
    }

    @Test fun duplicateActionAfterFirstCompletionFailsBothRevisionAndStateGuards() {
        val original = WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit)))
        val action = WidgetHabitAction(habit.id, monday.toString(), 4, false, true)
        val changed = original.copy(snapshot = original.snapshot.copy(revision = 5, habitLogs = listOf(HabitLog(habit.id, monday.toString()))))
        assertFalse(WidgetProjection.canApplyHabitAction(changed, action, monday))
        assertFalse(WidgetProjection.canApplyHabitAction(changed, action.copy(revision = 5), monday))
        assertTrue(WidgetProjection.canApplyHabitAction(changed, action.copy(revision = 5, expectedCompleted = true, targetCompleted = false), monday))
    }

    @Test fun missingArchivedUnscheduledOrRecoveringHabitCannotBeChanged() {
        val action = WidgetHabitAction(habit.id, monday.toString(), 4, false, true)
        assertFalse(WidgetProjection.canApplyHabitAction(WorkspaceState(WorkspaceSnapshot(revision = 4)), action, monday))
        assertFalse(WidgetProjection.canApplyHabitAction(WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit.copy(archived = true)))), action, monday))
        assertFalse(WidgetProjection.canApplyHabitAction(WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit.copy(weekdays = listOf(2))))), action, monday))
        assertFalse(WidgetProjection.canApplyHabitAction(WorkspaceState(WorkspaceSnapshot(revision = 4, habits = listOf(habit)), recoveryRequired = true), action, monday))
    }

    @Test fun primaryCopySupportsEnglishGermanSpanishWithoutInventedStreaks() {
        assertEquals("Next up", WidgetCopy("en").nextUp)
        assertEquals("Als Nächstes", WidgetCopy("de").nextUp)
        assertEquals("Hábitos de hoy", WidgetCopy("es").todayHabits)
        assertEquals("Als Nächstes", WidgetCopy("system", "de").nextUp)
        assertTrue(WidgetCopy("en").habitSummary(0, 2).contains("recorded"))
        assertFalse(WidgetCopy("en").habitSummary(0, 2).contains("missed"))
    }
}
