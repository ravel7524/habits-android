package ch.habits.android.reminders

import ch.habits.android.data.WorkspaceSnapshot
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class ReminderPreferences(
    val tasksEnabled: Boolean = false,
    val assessmentsEnabled: Boolean = false,
    val appointmentsEnabled: Boolean = false,
    val habitsEnabled: Boolean = false,
    val morningEnabled: Boolean = false,
    val eveningEnabled: Boolean = false,
    val weeklyEnabled: Boolean = false,
    val includePreparation: Boolean = false,
    val appointmentLeadMinutes: Int = 30,
    val assessmentLeadMinutes: Int = 30,
    val habitTime: String = "18:00",
) {
    fun validate() {
        require(appointmentLeadMinutes in 1..120 && assessmentLeadMinutes in 1..120)
        require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(habitTime))
    }
}

@Serializable
enum class ReminderKind { TASK, ASSESSMENT, APPOINTMENT, HABIT, MORNING_REPORT, EVENING_REPORT, WEEKLY_REPORT }

@Serializable
data class ReminderPlan(
    val token: String,
    val stableKey: String,
    val kind: ReminderKind,
    val sourceID: String? = null,
    val title: String,
    val fireAt: String,
    val expiresAt: String,
    val sourceDate: String,
    val sourceZone: String,
    val timeSupplied: Boolean,
    val route: String,
    val reportKind: String? = null,
) {
    val instant: Instant get() = Instant.parse(fireAt)
    val expiry: Instant get() = Instant.parse(expiresAt)
}

/** Offline, source-grounded planning. It does not change work or generate reports. */
object ReminderPlanBuilder {
    const val MAX_QUEUE = 64
    fun build(snapshot: WorkspaceSnapshot, prefs: ReminderPreferences, now: Instant, zone: ZoneId,
              deliveredTokens: Set<String> = emptySet(), snoozes: Map<String, Instant> = emptyMap()): List<ReminderPlan> =
        candidates(snapshot, prefs, now, zone).mapNotNull { original ->
            val snooze = snoozes[original.token]
            if (snooze != null) {
                val fire = outsideQuietHours(snooze, zone) ?: return@mapNotNull null
                if (fire <= now || fire >= original.expiry) null else original.copy(fireAt = fire.toString())
            } else if (original.token in deliveredTokens || original.instant <= now) null else original
        }.sortedWith(compareBy<ReminderPlan> { it.instant }.thenBy { it.token }).take(MAX_QUEUE)

    /** Receiver validation includes a short valid delivery window, never stale sources. */
    fun current(snapshot: WorkspaceSnapshot, prefs: ReminderPreferences, token: String, now: Instant, zone: ZoneId): ReminderPlan? =
        candidates(snapshot, prefs, now, zone).firstOrNull { it.token == token && now < it.expiry }

    fun outsideQuietHours(instant: Instant, zone: ZoneId): Instant? {
        val local = instant.atZone(zone)
        return when {
            local.hour >= 22 -> strict(local.toLocalDate().plusDays(1), LocalTime.of(7, 0), zone)
            local.hour < 7 -> strict(local.toLocalDate(), LocalTime.of(7, 0), zone)
            else -> instant
        }
    }

    private fun candidates(snapshot: WorkspaceSnapshot, prefs: ReminderPreferences, now: Instant, zone: ZoneId): List<ReminderPlan> {
        prefs.validate()
        val result = mutableListOf<ReminderPlan>()
        fun add(key: String, kind: ReminderKind, id: String?, title: String, base: Instant?, expiry: Instant?,
                day: String, sourceZone: ZoneId, timed: Boolean, route: String, version: String, report: String? = null) {
            if (base == null || expiry == null || expiry <= now) return
            val fire = outsideQuietHours(base, zone) ?: return
            if (fire >= expiry) return
            // Delivery identity follows the source version/civil period, not the
            // viewing timezone or quiet-hour adjustment. Travel cannot replay it.
            val token = hash("$key|$version")
            result += ReminderPlan(token, key, kind, id, title, fire.toString(), expiry.toString(), day, sourceZone.id, timed, route, report)
        }
        if (prefs.tasksEnabled) for (task in snapshot.tasks) {
            if (task.completed || task.archived || task.dueDate == null || (task.sourceAssessmentID != null && !prefs.includePreparation)) continue
            val day = runCatching { LocalDate.parse(task.dueDate) }.getOrNull() ?: continue
            val source = runCatching { ZoneId.of(task.sourceZone ?: zone.id) }.getOrNull() ?: continue
            val clock = runCatching { LocalTime.parse(task.dueTime ?: "09:00") }.getOrNull() ?: continue
            val fire = strict(day, clock, source) ?: continue
            val expiry = minOf(fire.plus(Duration.ofHours(4)), strict(day, LocalTime.of(22, 0), source) ?: continue)
            add("task:${task.id}", ReminderKind.TASK, task.id, task.title, fire, expiry, day.toString(), source,
                task.dueTime != null, "planner", "${task.kind}|${task.title}|${task.dueDate}|${task.dueTime}|${task.sourceZone}")
        }
        if (prefs.assessmentsEnabled) for (item in snapshot.assessments) {
            if (item.archived || item.completed) continue
            val semester = snapshot.semesters.firstOrNull { it.id == item.semesterID && !it.archived } ?: continue
            if (snapshot.courses.none { it.id == item.courseID && it.semesterID == semester.id && !it.archived }) continue
            val day = runCatching { LocalDate.parse(item.dueDate) }.getOrNull() ?: continue
            val source = runCatching { ZoneId.of(semester.timeZoneIdentifier) }.getOrNull() ?: continue
            val clock = runCatching { LocalTime.parse(item.dueTime ?: "09:00") }.getOrNull() ?: continue
            val due = strict(day, clock, source) ?: continue
            val fire = if (item.dueTime == null) due else due.minusSeconds(prefs.assessmentLeadMinutes * 60L)
            val expiry = if (item.dueTime == null) strict(day, LocalTime.of(22, 0), source) else due
            add("assessment:${item.id}", ReminderKind.ASSESSMENT, item.id, item.title, fire, expiry, day.toString(), source,
                item.dueTime != null, "semester", "${item.kind}|${item.title}|${item.dueDate}|${item.dueTime}|${source.id}|${prefs.assessmentLeadMinutes}")
        }
        if (prefs.appointmentsEnabled) for (item in snapshot.appointments) {
            if (item.archived) continue
            val day = runCatching { LocalDate.parse(item.startDate) }.getOrNull() ?: continue
            val source = runCatching { ZoneId.of(item.timeZoneIdentifier) }.getOrNull() ?: continue
            val clock = runCatching { LocalTime.parse(item.startTime) }.getOrNull() ?: continue
            val start = strict(day, clock, source) ?: continue
            add("appointment:${item.id}", ReminderKind.APPOINTMENT, item.id, item.title,
                start.minusSeconds(prefs.appointmentLeadMinutes * 60L), start, day.toString(), source, true, "planner",
                "${item.title}|${item.startDate}|${item.startTime}|${source.id}|${prefs.appointmentLeadMinutes}")
        }
        val today = now.atZone(zone).toLocalDate()
        for (offset in -1L..14L) {
            val day = today.plusDays(offset)
            val dayEnd = strict(day, LocalTime.of(22, 0), zone)
            if (prefs.habitsEnabled) {
                val scheduled = snapshot.habits.filter { !it.archived && day.dayOfWeek.value in it.weekdays }
                val completed = snapshot.habitLogs.filter { it.date == day.toString() && it.completed }.map { it.habitID }.toSet()
                if (scheduled.any { it.id !in completed }) add("habits:$day", ReminderKind.HABIT, null, "Habits",
                    strict(day, LocalTime.parse(prefs.habitTime), zone), dayEnd, day.toString(), zone, true, "habits",
                    scheduled.sortedBy { it.id }.joinToString { it.id } + "|${prefs.habitTime}")
            }
            if (prefs.morningEnabled) add("morning:$day", ReminderKind.MORNING_REPORT, null, "Morning report",
                strict(day, LocalTime.of(8, 0), zone), strict(day, LocalTime.of(14, 0), zone), day.toString(), zone, true, "report", "$day", "morning")
            if (prefs.eveningEnabled) add("evening:$day", ReminderKind.EVENING_REPORT, null, "Evening report",
                strict(day, LocalTime.of(20, 0), zone), dayEnd, day.toString(), zone, true, "report", "$day", "evening")
            if (prefs.weeklyEnabled && day.dayOfWeek.value == 7) add("weekly:$day", ReminderKind.WEEKLY_REPORT, null, "Weekly review",
                strict(day, LocalTime.of(18, 0), zone), dayEnd, day.toString(), zone, true, "report", "$day", "weekly")
        }
        return result.distinctBy { it.token }
    }

    private fun strict(day: LocalDate, clock: LocalTime, zone: ZoneId): Instant? {
        val local = LocalDateTime.of(day, clock)
        val offsets = zone.rules.getValidOffsets(local)
        return if (offsets.size == 1) local.toInstant(offsets.single()) else null
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
