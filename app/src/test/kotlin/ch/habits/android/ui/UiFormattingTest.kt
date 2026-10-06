package ch.habits.android.ui

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFormattingTest {
    @Test fun civilDatesAreNotShiftedByViewerTimeZone() {
        val copy = UiCopy("en")
        assertEquals("Jan 1, 2027", copy.displayDate("2027-01-01", zone = "Pacific/Kiritimati"))
        assertEquals("Jan 1, 2027", copy.displayDate("2027-01-01", zone = "America/Los_Angeles"))
        val todayAtSource = LocalDate.now(ZoneId.of("Pacific/Kiritimati"))
        assertEquals("Today", copy.displayDate(todayAtSource.toString(), relative = true, zone = "Pacific/Kiritimati"))
    }
    @Test fun absentTimesStayAbsentAndUnknownDatesRemainVisible() {
        val copy = UiCopy("en")
        assertEquals("", copy.displayTime(null))
        assertEquals("No deadline", copy.displayDate(null))
        assertEquals("invalid", copy.displayDate("invalid"))
        assertTrue(copy.displayTime("00:00").isNotBlank())
        assertNull(copy.zoneDetail(ZoneId.systemDefault().id))
        assertEquals("invalid-zone", copy.zoneDetail("invalid-zone"))
    }
    @Test fun dateAndCountCopyFollowSelectedLanguage() {
        assertTrue(UiCopy("de").displayDate("2027-01-01").contains("2027"))
        assertFalse(UiCopy("es").displayDate("2027-01-01").contains("Jan"))
        val en = UiCopy("en")
        assertEquals("1 task", en.quantity(1,"task","tasks","Aufgabe","Aufgaben","tarea","tareas"))
        assertEquals("2 tasks", en.quantity(2,"task","tasks","Aufgabe","Aufgaben","tarea","tareas"))
        assertEquals("1 Aufgabe", UiCopy("de").quantity(1,"task","tasks","Aufgabe","Aufgaben","tarea","tareas"))
    }
}
