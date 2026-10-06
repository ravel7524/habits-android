package ch.habits.android.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarProjectionTest {
    private val day = LocalDate.parse("2026-10-08")
    private val zurich = ZoneId.of("Europe/Zurich")
    private fun event(start: String, end: String, allDay: Boolean = false, calendar: Long = 1, event: Long = 2): CalendarEvent {
        val begin = Instant.parse(start).toEpochMilli()
        return CalendarEvent(CalendarProjection.instanceID(calendar, event, begin), calendar, event, "Recorded event",
            begin, Instant.parse(end).toEpochMilli(), allDay, "Selected calendar")
    }
    private fun state(events: List<CalendarEvent>, coverage: CalendarCoverage = CalendarCoverage.AVAILABLE) = CalendarState(
        enabled = true, selectedCalendarIDs = setOf(1), permissionGranted = true, events = events, coverage = coverage,
        window = CalendarProjection.window(day, zurich))

    @Test fun allDayUtcDateDoesNotShiftToPreviousDayInLosAngeles() {
        val value = event("2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z", allDay = true)
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertFalse(CalendarProjection.overlapsDay(value, day.minusDays(1), losAngeles))
        assertTrue(CalendarProjection.overlapsDay(value, day, losAngeles))
        assertFalse(CalendarProjection.overlapsDay(value, day.plusDays(1), losAngeles))
    }

    @Test fun multiDayAllDayUsesExclusiveEndCivilDateAcrossDst() {
        val value = event("2026-10-24T00:00:00Z", "2026-10-27T00:00:00Z", allDay = true)
        assertTrue(CalendarProjection.overlapsDay(value, LocalDate.parse("2026-10-25"), zurich))
        assertTrue(CalendarProjection.overlapsDay(value, LocalDate.parse("2026-10-26"), zurich))
        assertFalse(CalendarProjection.overlapsDay(value, LocalDate.parse("2026-10-27"), zurich))
    }

    @Test fun timedOccurrenceCrossingViewerMidnightAppearsOnBothDays() {
        val value = event("2026-10-08T21:30:00Z", "2026-10-09T00:00:00Z")
        assertTrue(CalendarProjection.overlapsDay(value, day, zurich))
        assertTrue(CalendarProjection.overlapsDay(value, day.plusDays(1), zurich))
        assertFalse(CalendarProjection.overlapsDay(value, day.minusDays(1), zurich))
    }

    @Test fun eventEndingAtViewerMidnightDoesNotAppearOnNextDay() {
        val value = event("2026-10-08T20:00:00Z", "2026-10-08T22:00:00Z")
        assertTrue(CalendarProjection.overlapsDay(value, day, zurich))
        assertFalse(CalendarProjection.overlapsDay(value, day.plusDays(1), zurich))
    }

    @Test fun zeroDurationTimedEventKeepsItsInstantAndDay() {
        val value = event("2026-10-08T22:00:00Z", "2026-10-08T22:00:00Z")
        assertFalse(CalendarProjection.overlapsDay(value, day, zurich))
        assertTrue(CalendarProjection.overlapsDay(value, day.plusDays(1), zurich))
    }

    @Test fun sameEventRecurringOccurrencesAndDifferentCalendarsKeepDifferentIdentity() {
        val first = event("2026-10-08T10:00:00Z", "2026-10-08T11:00:00Z")
        val next = event("2026-10-09T10:00:00Z", "2026-10-09T11:00:00Z")
        val otherCalendar = first.copy(id = CalendarProjection.instanceID(3, first.eventID, first.startMillis), calendarID = 3)
        assertEquals(3, setOf(first.id, next.id, otherCalendar.id).size)
        assertEquals(listOf(first), CalendarProjection.forDay(state(listOf(first, next)), day, zurich))
    }

    @Test fun everyUnavailableCoverageHidesEvenAccidentallyRetainedEvents() {
        val value = event("2026-10-08T10:00:00Z", "2026-10-08T11:00:00Z")
        CalendarCoverage.entries.filter { it != CalendarCoverage.AVAILABLE }.forEach { coverage ->
            assertTrue(CalendarProjection.forDay(state(listOf(value), coverage), day, zurich).isEmpty(), coverage.name)
        }
    }

    @Test fun missingPermissionOffAndNoSelectionCannotEstablishCoverage() {
        val available = state(listOf(event("2026-10-08T10:00:00Z", "2026-10-08T11:00:00Z")))
        assertTrue(CalendarProjection.forDay(available.copy(permissionGranted = false), day, zurich).isEmpty())
        assertTrue(CalendarProjection.forDay(available.copy(enabled = false), day, zurich).isEmpty())
        assertTrue(CalendarProjection.forDay(available.copy(selectedCalendarIDs = emptySet()), day, zurich).isEmpty())
    }

    @Test fun unselectedCalendarCannotEnterDayProjection() {
        val selected = event("2026-10-08T10:00:00Z", "2026-10-08T11:00:00Z")
        val other = event("2026-10-08T10:00:00Z", "2026-10-08T11:00:00Z", calendar = 3)
        assertEquals(listOf(selected), CalendarProjection.forDay(state(listOf(selected, other)), day, zurich))
    }

    @Test fun changedTimezoneOrOutsideWindowDoesNotClaimLoadedCoverage() {
        val available = state(emptyList())
        assertFalse(available.covers(day, ZoneId.of("America/Los_Angeles")))
        assertFalse(available.covers(day.minusDays(8), zurich))
        assertFalse(available.covers(day.plusDays(29), zurich))
        assertTrue(available.covers(day.minusDays(7), zurich))
        assertTrue(available.covers(day.plusDays(28), zurich))
    }

    @Test fun queryBoundsIncludeLocalTimedAndUtcAllDayExtremes() {
        for (zone in listOf(ZoneId.of("Pacific/Kiritimati"), ZoneId.of("America/Los_Angeles"))) {
            val window = CalendarProjection.window(day, zone)
            val start = window.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val utcStart = window.startDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            val end = window.endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            val utcEnd = window.endDateExclusive.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            assertTrue(window.queryStartMillis <= start && window.queryStartMillis <= utcStart)
            assertTrue(window.queryEndMillis >= end && window.queryEndMillis >= utcEnd)
            assertEquals(36, java.time.temporal.ChronoUnit.DAYS.between(window.startDate, window.endDateExclusive).toInt())
        }
    }
}
