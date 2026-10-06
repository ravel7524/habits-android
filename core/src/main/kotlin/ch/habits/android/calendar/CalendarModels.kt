package ch.habits.android.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarChoice(
    val id: Long,
    val displayName: String,
    val color: Int? = null,
    val visible: Boolean = true,
    val accountLabel: String? = null,
)

/** A provider occurrence, never a writable planner item or a persisted workspace record. */
data class CalendarEvent(
    val id: String,
    val calendarID: Long,
    val eventID: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String,
)

enum class CalendarCoverage {
    OFF, PERMISSION_REQUIRED, NO_SELECTION, LOADING, AVAILABLE,
    UNAVAILABLE, TRUNCATED, SELECTION_UNAVAILABLE, SETTINGS_ERROR,
}

data class CalendarWindow(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val timeZoneIdentifier: String,
    val queryStartMillis: Long,
    val queryEndMillis: Long,
)

data class CalendarState(
    val enabled: Boolean = false,
    val selectedCalendarIDs: Set<Long> = emptySet(),
    val permissionGranted: Boolean = false,
    val busy: Boolean = false,
    val calendarChoices: List<CalendarChoice> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val coverage: CalendarCoverage = CalendarCoverage.OFF,
    val window: CalendarWindow? = null,
    val refreshedAtMillis: Long? = null,
    val error: String? = null,
) {
    val availableCalendars: List<CalendarChoice> get() = calendarChoices
    val windowStart: LocalDate? get() = window?.startDate
    val windowEndExclusive: LocalDate? get() = window?.endDateExclusive

    fun covers(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        coverage == CalendarCoverage.AVAILABLE && enabled && permissionGranted && selectedCalendarIDs.isNotEmpty() && window?.let {
            it.timeZoneIdentifier == zone.id && day >= it.startDate && day < it.endDateExclusive
        } == true
}

/** CalendarContract stores all-day bounds at UTC midnight; those dates must not shift with travel. */
object CalendarProjection {
    const val MAX_EVENTS = 500
    const val MAX_CALENDARS = 200

    fun window(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): CalendarWindow {
        val start = today.minusDays(7)
        val end = today.plusDays(29) // Includes today + 28, using an exclusive end.
        val localStart = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val localEnd = end.atStartOfDay(zone).toInstant().toEpochMilli()
        val utcStart = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcEnd = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarWindow(start, end, zone.id, minOf(localStart, utcStart), maxOf(localEnd, utcEnd))
    }

    fun instanceID(calendarID: Long, eventID: Long, beginMillis: Long): String =
        "calendar/$calendarID/event/$eventID/instance/$beginMillis"

    fun overlapsDay(event: CalendarEvent, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (event.endMillis < event.startMillis) return false
        if (event.allDay) {
            val start = Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val end = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate()
            return day >= start && day < end
        }
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return if (event.startMillis == event.endMillis) event.startMillis >= start && event.startMillis < end
        else event.startMillis < end && event.endMillis > start
    }

    fun inWindow(event: CalendarEvent, window: CalendarWindow): Boolean {
        if (event.endMillis < event.startMillis) return false
        if (event.allDay) {
            val start = Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val end = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate()
            return start < window.endDateExclusive && end > window.startDate
        }
        val zone = ZoneId.of(window.timeZoneIdentifier)
        val start = window.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = window.endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return if (event.startMillis == event.endMillis) event.startMillis >= start && event.startMillis < end
        else event.startMillis < end && event.endMillis > start
    }

    fun forDay(state: CalendarState, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        if (!state.covers(day, zone)) return emptyList()
        return state.events.filter { it.calendarID in state.selectedCalendarIDs && overlapsDay(it, day, zone) }
            .sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.startMillis }.thenBy { it.id })
    }
}
