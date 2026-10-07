package ch.habits.android.semester

import ch.habits.android.core.AcademicPlanCodec
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Mirrors the two actual Android paste fixtures without Android or filesystem dependencies. */
class SemesterBoardWorkflowFixtureTest {
    @Test fun fullWorkflowPasteClassifiesAsRawBoardAndRoundTripsItsRecordedValues() {
        val source = board(LocalDate.parse("2026-09-14"))
        val encoded = SemesterBoardCodec.encode(source)
        assertFalse("format" in AcademicPlanCodec.parseStrictJson(encoded).jsonObject)
        val decoded = SemesterBoardCodec.decode(encoded)
        assertEquals(source, decoded)
        assertEquals("completed", decoded.courses.first().assessments[2].state)
        assertEquals("dismissed", decoded.courses.first().assessments[3].state)
    }

    @Test fun legacyWorkflowPasteClassifiesAsRawBoardAndRetainsMissingDatesAndCourseExamIDs() {
        val original = AcademicPlanCodec.parseStrictJson(SemesterBoardCodec.encode(board(null))).jsonObject
        val courses = original.getValue("courses").jsonArray.map { element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                remove("assessments")
                put("examDate", JsonPrimitive("2026-10-05"))
                put("examTime", JsonPrimitive("09:00"))
                put("examNote", JsonPrimitive("Synthetic legacy course exam"))
            })
        }
        val pasted = JsonObject(original.toMutableMap().apply { put("courses", JsonArray(courses)) }).toString()
        assertFalse("format" in AcademicPlanCodec.parseStrictJson(pasted).jsonObject)
        val decoded = SemesterBoardCodec.decode(pasted)
        assertNull(decoded.startDate); assertNull(decoded.endDate)
        assertEquals("Europe/Zurich", decoded.timeZoneIdentifier)
        assertEquals(listOf(id(100), id(101)), decoded.courses.map { it.assessments.single().id })
        assertEquals(listOf("09:00", "09:00"), decoded.courses.map { it.assessments.single().dueTime })
        assertEquals("Synthetic legacy course exam", decoded.courses.first().assessments.single().notes)
        assertEquals(decoded, SemesterBoardCodec.decode(SemesterBoardCodec.encode(decoded)))
    }

    private fun id(number: Int) = "bd000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    private fun board(start: LocalDate?): BoardSemester = BoardSemester(
        id = id(1), title = "Autumn course board", weekCount = 14, startDate = start?.toString(), endDate = start?.plusDays(97)?.toString(),
        showExamWeeks = true, timeZoneIdentifier = "Europe/Zurich",
        statuses = listOf(BoardStatus(id(20), "Reviewed", "#37A269"), BoardStatus(id(21), "Revisit", "#EAAB46")),
        courses = (0..1).map { index -> BoardCourse(id(100 + index), if (index == 0) "Statistics" else "Corporate Finance",
            activeWeeks = (1..14).filter { it % 4 != 0 }, entries = mapOf(
                1 to BoardWeekEntry(id(20), "Review probability definitions and bring two questions. This is a saved note, not proof of study."),
                5 to BoardWeekEntry(id(21), "Check examples before the tutorial.")),
            moduleCode = "QA-${index + 1}", scheduleNote = "Teaching schedule supplied by the synthetic source.",
            assessments = if (index == 0) listOf(
                BoardAssessment(id(200), "Statistics exam", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(2).toString(), "09:00"),
                BoardAssessment(id(201), "Later statistics exam", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(121).toString(), "09:00"),
                BoardAssessment(id(202), "Recorded essay", "essay", (start ?: LocalDate.parse("2026-09-14")).plusDays(1).toString(), state = "completed",
                    completedAt = Instant.parse("2026-10-01T12:00:00Z").epochSecond - Instant.parse("2001-01-01T00:00:00Z").epochSecond.toDouble()),
                BoardAssessment(id(203), "Dismissed presentation", "presentation", (start ?: LocalDate.parse("2026-09-14")).plusDays(3).toString(), state = "dismissed"))
                else listOf(BoardAssessment(id(210), "Finance exam 2", "exam", (start ?: LocalDate.parse("2026-09-14")).plusDays(8).toString()))) })
}
