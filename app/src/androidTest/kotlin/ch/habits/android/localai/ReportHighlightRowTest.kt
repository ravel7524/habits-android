package ch.habits.android.localai

import ch.habits.android.calendar.CalendarCoverage
import ch.habits.android.calendar.CalendarEvent
import ch.habits.android.calendar.CalendarProjection
import ch.habits.android.calendar.CalendarState
import ch.habits.android.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Presentation resolution only: no model, provider, storage mutation or inferred content. */
class ReportHighlightRowTest {
    private val day = LocalDate.parse("2026-10-08")
    private val utc = ZoneId.of("UTC")
    private fun task(title: String = "A full original title ".repeat(12)) = WorkTask(id = "task", title = title,
        dueDate = day.toString(), dueTime = "18:00", sourceZone = "UTC", minutes = 60,
        createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z")

    @Test fun fullOriginalTitleAndLocalizedDeadlineReplaceBoundedPromptText() {
        val record = task()
        val snapshot = WorkspaceSnapshot(tasks = listOf(record))
        val fact = ReportFacts.build("morning", snapshot, null, day, utc, "en").facts.single()
        assertNotEquals(record.title, fact.text)
        for ((language, planned) in listOf("en" to "Planned estimate", "de" to "Geplanter Aufwand", "es" to "Estimación prevista")) {
            val display = requireNotNull(resolveReportHighlight(fact, snapshot, null, language, utc))
            assertEquals(record.title, display.title)
            assertTrue(display.details.any { it.contains(planned) && it.contains("60") })
            assertTrue(display.details.none { it.contains("2026-10-08") || it.contains("planned 60 min") })
        }
    }

    @Test fun absentChangedDuplicateAndWrongKindReferencesAreUnavailable() {
        val record = task()
        val snapshot = WorkspaceSnapshot(tasks = listOf(record))
        val fact = ReportFacts.build("morning", snapshot, null, day, utc, "en").facts.single()
        assertNull(resolveReportHighlight(fact, null, null, "en", utc))
        assertNull(resolveReportHighlight(fact.copy(sourceRef = null), snapshot, null, "en", utc))
        assertNull(resolveReportHighlight(fact.copy(sourceKind = "assessment"), snapshot, null, "en", utc))
        assertNull(resolveReportHighlight(fact, snapshot.copy(tasks = listOf(record.copy(title = "Changed"))), null, "en", utc))
        assertNull(resolveReportHighlight(fact, snapshot.copy(tasks = listOf(record, record)), null, "en", utc))
    }

    @Test fun completedPreparationShowsTimestampEvidenceWithoutPlannedTimeWorked() {
        val record = task("Recorded preparation").copy(sourceAssessmentID = "exam", completed = true, completedAt = "2026-10-08T12:00:00Z", minutes = 900)
        val snapshot = WorkspaceSnapshot(tasks = listOf(record))
        for (kind in listOf("evening", "weekly")) {
            val fact = ReportFacts.build(kind, snapshot, null, day, utc, "en").facts.single()
            val display = requireNotNull(resolveReportHighlight(fact, snapshot, null, "en", utc))
            assertEquals(record.title, display.title)
            assertTrue(display.details.any { it.contains("Preparation") && it.contains("Completion recorded") })
            assertTrue(display.details.none { it.contains("900") || it.contains("Planned") || it.contains("Assessment completed") })
        }
    }

    @Test fun habitLogUsesTypedCivilDateAndOriginalTitleWhileMissingLogFailsClosed() {
        val habit = Habit("habit", "Full logged habit title ".repeat(8), archived = true)
        val log = HabitLog(habit.id, day.toString())
        val snapshot = WorkspaceSnapshot(habits = listOf(habit), habitLogs = listOf(log))
        val fact = ReportFacts.build("weekly", snapshot, null, day, utc, "en").facts.single()
        val display = requireNotNull(resolveReportHighlight(fact, snapshot, null, "en", utc))
        assertEquals(habit.title, display.title)
        assertTrue(display.details.contains("Completion logged"))
        assertNull(resolveReportHighlight(fact, snapshot.copy(habitLogs = emptyList()), null, "en", utc))
    }

    @Test fun calendarAllDayCivilDateSurvivesTimezoneAndRevocationClearsResolution() {
        val selectedDay = LocalDate.parse("2026-03-29")
        val zone = ZoneId.of("America/Los_Angeles")
        val begin = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        val event = CalendarEvent(CalendarProjection.instanceID(1, 2, begin), 1, 2, "Full all-day event title ".repeat(8),
            begin, Instant.parse("2026-03-30T00:00:00Z").toEpochMilli(), true, "Selected calendar")
        val calendar = CalendarState(enabled = true, permissionGranted = true, selectedCalendarIDs = setOf(1), events = listOf(event),
            coverage = CalendarCoverage.AVAILABLE, window = CalendarProjection.window(selectedDay, zone))
        val fact = ReportFacts.build("morning", WorkspaceSnapshot(), calendar, selectedDay, zone, "en").facts.single()
        val display = requireNotNull(resolveReportHighlight(fact, WorkspaceSnapshot(), calendar, "en", zone))
        assertEquals(event.title, display.title)
        assertTrue(display.details.first().contains("All day"))
        assertTrue(display.details.first().contains("Mar 29, 2026"))
        assertFalse(display.details.first().contains("Mar 28"))
        assertNull(resolveReportHighlight(fact, WorkspaceSnapshot(), calendar.copy(permissionGranted = false), "en", zone))
        assertNull(resolveReportHighlight(fact, WorkspaceSnapshot(), calendar.copy(coverage = CalendarCoverage.TRUNCATED), "en", zone))
        assertNull(resolveReportHighlight(fact, WorkspaceSnapshot(), calendar.copy(events = listOf(event.copy(title = "Changed"))), "en", zone))
    }

    @Test fun assessmentAndSavedAppointmentResolveTheirOriginalRelationshipsAndTimes() {
        val semester = AcademicSemester("semester", "Semester", "2026-09-14", 14, "UTC")
        val course = AcademicCourse("course", semester.id, "Original course")
        val assessment = AcademicAssessment("exam", semester.id, course.id, "Full original assessment title ".repeat(7), "exam", day.toString())
        val appointment = Appointment("appointment", "Full original appointment title ".repeat(7), day.toString(), "09:00", day.toString(), "10:00", "UTC")
        val snapshot = WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(assessment), appointments = listOf(appointment))
        val facts = ReportFacts.build("morning", snapshot, null, day, utc, "en").facts
        val examFact = facts.single { it.sourceKind == "assessment" }
        val examDisplay = requireNotNull(resolveReportHighlight(examFact, snapshot, null, "en", utc))
        assertEquals(assessment.title, examDisplay.title)
        assertTrue(examDisplay.details.contains(course.name))
        assertTrue(examDisplay.details.any { it.contains("Time not recorded") })
        assertNull(resolveReportHighlight(examFact, snapshot.copy(courses = emptyList()), null, "en", utc))
        val appointmentDisplay = requireNotNull(resolveReportHighlight(facts.single { it.sourceKind == "appointment" }, snapshot, null, "en", utc))
        assertEquals(appointment.title, appointmentDisplay.title)
        assertTrue(appointmentDisplay.details.contains("Saved appointment"))
    }

    @Test fun dismissedOrUnresolvedOpenAssessmentReferencesFailClosedWhileRecordedCompletionSurvives() {
        val semester = AcademicSemester("semester", "Semester", null, 14, "Europe/Zurich")
        val course = AcademicCourse("course", semester.id, "Statistics")
        val assessment = AcademicAssessment("exam", semester.id, course.id, "Exam", "exam", day.toString(), "10:00")
        val source = WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(assessment))
        val fact = ReportFacts.build("morning", source, null, day, utc, "en").facts.single()
        val dismissed = assessment.copy(dismissed = true)
        assertNull(resolveReportHighlight(fact, source.copy(assessments = listOf(dismissed)), null, "en", utc))
        assertNull(resolveReportHighlight(fact.copy(sourceRef = ReportSourceRef.Assessment(dismissed, course, semester, completion = false)),
            source.copy(assessments = listOf(dismissed)), null, "en", utc))
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val unresolved = assessment.copy(dueDate = date, dueTime = "02:30")
            assertNull(resolveReportHighlight(fact.copy(sourceRef = ReportSourceRef.Assessment(unresolved, course, semester, completion = false)),
                source.copy(assessments = listOf(unresolved)), null, "en", utc))
        }
        val completed = dismissed.copy(completed = true, completedAt = "2026-10-08T12:00:00Z", archived = true)
        val history = source.copy(assessments = listOf(completed))
        val recorded = ReportFacts.build("evening", history, null, day, utc, "en").facts.single()
        assertNotNull(resolveReportHighlight(recorded, history, null, "en", utc))
    }

    @Test fun courseWeekPreparationUsesOwnTypedRecordAndCompletionEvidence() {
        val record = task("Week preparation").copy(sourceCourseID = "course", sourceWeek = 3)
        val source = WorkspaceSnapshot(tasks = listOf(record))
        val fact = ReportFacts.build("morning", source, null, day, utc, "en").facts.single()
        val display = requireNotNull(resolveReportHighlight(fact, source, null, "en", utc))
        assertTrue(display.details.any { it.startsWith("Preparation") })
        assertNull(resolveReportHighlight(fact, source.copy(tasks = listOf(record.copy(sourceWeek = 4))), null, "en", utc))
        val completed = record.copy(completed = true, completedAt = "2026-10-08T12:00:00Z", minutes = 900)
        val history = source.copy(tasks = listOf(completed))
        val recorded = ReportFacts.build("evening", history, null, day, utc, "en").facts.single()
        val evidence = requireNotNull(resolveReportHighlight(recorded, history, null, "en", utc))
        assertTrue(evidence.details.any { it.contains("Preparation") && it.contains("Completion recorded") })
        assertFalse(evidence.details.any { it.contains("900") || it.contains("studied") })
    }
}
