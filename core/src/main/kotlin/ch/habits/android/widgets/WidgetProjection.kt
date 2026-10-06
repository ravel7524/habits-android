package ch.habits.android.widgets

import ch.habits.android.core.AcademicPlanValidator
import ch.habits.android.data.WorkspaceFacts
import ch.habits.android.data.WorkspaceState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WidgetEntryKind { TASK, ASSIGNMENT, ASSESSMENT, APPOINTMENT }

data class NextUpEntry(
    val id: String,
    val title: String,
    val kind: WidgetEntryKind,
    val date: String,
    val time: String?,
    val sourceZone: String?,
    val overdue: Boolean,
    val ongoing: Boolean = false,
)

data class NextUpProjection(
    val entries: List<NextUpEntry>,
    val totalCount: Int,
    val generatedAt: Instant,
    val recoveryRequired: Boolean,
)

data class TodayHabitEntry(val id: String, val title: String, val completed: Boolean)

data class TodayHabitsProjection(
    val entries: List<TodayHabitEntry>,
    val scheduledCount: Int,
    val completedCount: Int,
    val date: String,
    val revision: Long,
    val generatedAt: Instant,
    val recoveryRequired: Boolean,
)

data class WidgetHabitAction(
    val habitID: String,
    val date: String,
    val revision: Long,
    val expectedCompleted: Boolean,
    val targetCompleted: Boolean,
)

/** Pure projections contain only visible titles and civil deadline metadata, never notes/history. */
object WidgetProjection {
    fun nextUp(state: WorkspaceState, now: Instant, deviceZone: ZoneId, limit: Int = 3): NextUpProjection {
        if (state.recoveryRequired) return NextUpProjection(emptyList(), 0, now, true)
        val snapshot = state.snapshot
        val queued = mutableListOf<Pair<Instant, NextUpEntry>>()
        fun deadline(id: String, title: String, kind: WidgetEntryKind, date: String, time: String?, zone: String?) {
            val source = zone?.let(ZoneId::of) ?: deviceZone
            val day = LocalDate.parse(date)
            // End-of-day is used only to order a date-only deadline. The view
            // retains the civil date and a null time, never an invented clock.
            val order = if (time == null) day.plusDays(1).atStartOfDay(source).toInstant().minusNanos(1)
                else AcademicPlanValidator.resolveInstant(date, time, source.id)
            val overdue = if (time == null) day < now.atZone(source).toLocalDate() else order < now
            queued += order to NextUpEntry(id, title, kind, date, time, zone, overdue)
        }
        snapshot.tasks.filter { !it.archived && !it.completed && it.dueDate != null }.forEach { task ->
            deadline(task.id, task.title, if (task.kind == "assignment") WidgetEntryKind.ASSIGNMENT else WidgetEntryKind.TASK,
                checkNotNull(task.dueDate), task.dueTime, task.sourceZone)
        }
        val semesters = snapshot.semesters.filterNot { it.archived }.associateBy { it.id }
        val courses = snapshot.courses.filterNot { it.archived }.associateBy { it.id }
        snapshot.assessments.filter { !it.archived && !it.completed }.forEach { item ->
            val semester = semesters[item.semesterID]
            val course = courses[item.courseID]
            if (semester != null && course?.semesterID == semester.id) deadline(item.id, item.title,
                WidgetEntryKind.ASSESSMENT, item.dueDate, item.dueTime, semester.timeZoneIdentifier)
        }
        snapshot.appointments.filterNot { it.archived }.forEach { item ->
            val start = AcademicPlanValidator.resolveInstant(item.startDate, item.startTime, item.timeZoneIdentifier)
            val end = AcademicPlanValidator.resolveInstant(item.endDate, item.endTime, item.timeZoneIdentifier)
            if (end > now) queued += start to NextUpEntry(item.id, item.title, WidgetEntryKind.APPOINTMENT,
                item.startDate, item.startTime, item.timeZoneIdentifier, overdue = false, ongoing = start <= now)
        }
        val ordered = queued.sortedWith(compareBy<Pair<Instant, NextUpEntry>>({ it.first }, { it.second.kind.ordinal }, { it.second.id }))
        return NextUpProjection(ordered.take(limit.coerceIn(0, 3)).map { it.second }, ordered.size, now, false)
    }

    fun habitsToday(state: WorkspaceState, now: Instant, deviceZone: ZoneId, limit: Int = 3): TodayHabitsProjection {
        val date = now.atZone(deviceZone).toLocalDate()
        if (state.recoveryRequired) return TodayHabitsProjection(emptyList(), 0, 0, date.toString(), state.snapshot.revision, now, true)
        val facts = WorkspaceFacts.today(state.snapshot, date, deviceZone)
        val habits = facts.habits.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
        val completed = facts.completedHabitIDs.intersect(habits.map { it.id }.toSet())
        return TodayHabitsProjection(habits.take(limit.coerceIn(0, 3)).map { TodayHabitEntry(it.id, it.title, it.id in completed) },
            habits.size, completed.size, date.toString(), state.snapshot.revision, now, false)
    }

    /** An old widget button must never toggle a new day or overwrite a newer log. */
    fun canApplyHabitAction(state: WorkspaceState, action: WidgetHabitAction, currentDate: LocalDate): Boolean {
        if (state.recoveryRequired || action.date != currentDate.toString() || action.revision != state.snapshot.revision ||
            action.targetCompleted == action.expectedCompleted) return false
        val habit = state.snapshot.habits.firstOrNull { it.id == action.habitID && !it.archived } ?: return false
        if (currentDate.dayOfWeek.value !in habit.weekdays) return false
        val completed = state.snapshot.habitLogs.any { it.habitID == habit.id && it.date == action.date && it.completed }
        return completed == action.expectedCompleted
    }
}
