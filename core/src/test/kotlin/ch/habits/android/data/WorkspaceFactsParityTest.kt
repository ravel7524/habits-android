package ch.habits.android.data

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceFactsParityTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val semester = AcademicSemester("f0000000-0000-0000-0000-000000000001", "Autumn", null, 14, zone.id)
    private val course = AcademicCourse("f0000000-0000-0000-0000-000000000002", semester.id, "Statistics")
    private val exam = AcademicAssessment("f0000000-0000-0000-0000-000000000003", semester.id, course.id,
        "Statistics exam", "exam", "2026-10-08")
    private fun snapshot() = WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(exam))

    @Test fun dismissedExamIsNotUpcomingOrCompletedAndPreparationRemainsIndependent() {
        val date = LocalDate.parse(exam.dueDate)
        for (parent in listOf(exam.copy(dismissed = true), exam.copy(completed = true, completedAt = "2026-10-08T12:00:00Z"))) {
            val preparation = WorkTask(id = "prep", title = "Review", dueDate = exam.dueDate, sourceAssessmentID = exam.id)
            val weekPreparation = WorkTask(id = "week", title = "Week notes", dueDate = exam.dueDate,
                sourceCourseID = course.id, sourceWeek = 3)
            val source = snapshot().copy(assessments = listOf(parent), tasks = listOf(preparation, weekPreparation))
            val facts = WorkspaceFacts.today(source, date, zone)
            assertTrue(facts.assessments.isEmpty())
            assertEquals(setOf("prep", "week"), facts.tasks.map { it.id }.toSet())
            assertTrue(facts.tasks.all { it.isPreparation })
            assertEquals(if (parent.completed) listOf(parent) else emptyList(),
                WorkspaceFacts.report(source, date, date.plusDays(1), zone).completedAssessments)
            assertTrue(WorkspaceFacts.report(source, date, date.plusDays(1), zone).completedTasks.isEmpty())
        }
    }

    @Test fun dstSourceClocksArePreservedAndDoNotPoisonValidDeadlinesOrHabitLogs() {
        for (rawDate in listOf("2026-03-29", "2026-10-25")) {
            val date = LocalDate.parse(rawDate)
            val unresolved = exam.copy(dueDate = rawDate, dueTime = "02:30")
            val valid = exam.copy(id = "valid", dueDate = rawDate, dueTime = "10:00")
            val invalidTask = WorkTask(id = "bad-task", title = "Imported preparation", dueDate = rawDate, dueTime = "02:30",
                sourceZone = zone.id, sourceAssessmentID = exam.id)
            val task = WorkTask(id = "task", title = "Civil deadline", dueDate = rawDate, sourceZone = zone.id)
            val habit = Habit("habit", "Read", weekdays = listOf(date.dayOfWeek.value))
            val source = snapshot().copy(assessments = listOf(unresolved, valid), tasks = listOf(invalidTask, task),
                habits = listOf(habit), habitLogs = listOf(HabitLog(habit.id, rawDate)))
            val facts = WorkspaceFacts.today(source, date, zone)
            assertEquals(listOf(task), facts.tasks)
            assertEquals(listOf(valid), facts.assessments)
            assertEquals(listOf(habit), facts.habits)
            assertEquals(setOf(habit.id), facts.completedHabitIDs)
            assertEquals(setOf(invalidTask.id), facts.unresolvedTaskIDs)
            assertEquals(setOf(unresolved.id), facts.unresolvedAssessmentIDs)
            assertEquals(2, facts.unresolvedDeadlineCount)
            assertNull(SourceClockResolution.instant(rawDate, "02:30", zone.id))
            assertEquals("02:30", source.assessments.first().dueTime)
            assertEquals("02:30", source.tasks.first().dueTime)
            assertNull(facts.tasks.single().dueTime, "Date-only records must not gain a clock")
        }
    }

    @Test fun archivedParentsAndDismissedBadClocksDoNotInflateTimingCoverage() {
        val date = LocalDate.parse("2026-10-25")
        val source = snapshot().copy(assessments = listOf(exam.copy(dueDate = date.toString(), dueTime = "02:30", dismissed = true)))
        assertEquals(0, WorkspaceFacts.today(source, date, zone).unresolvedDeadlineCount)
        assertEquals(0, WorkspaceFacts.today(source.copy(assessments = listOf(source.assessments.single().copy(dismissed = false)),
            courses = listOf(course.copy(archived = true))), date, zone).unresolvedDeadlineCount)
    }

    @Test fun completionReportsKeepArchivedRecordedHistoryWithoutTreatingWeekStatusAsLearning() {
        val date = LocalDate.parse("2026-10-08")
        val completed = exam.copy(completed = true, dismissed = true, archived = true, completedAt = "2026-10-08T12:00:00Z")
        val source = snapshot().copy(assessments = listOf(completed),
            semesters = listOf(semester.copy(statuses = listOf(AcademicStatus("ready", "Reviewed", "#112233")))),
            courses = listOf(course.copy(entries = mapOf(3 to AcademicWeekEntry(statusID = "ready", notes = "Read chapter")))))
        val facts = WorkspaceFacts.report(source, date, date.plusDays(1), zone)
        assertEquals(listOf(completed), facts.completedAssessments)
        assertTrue(facts.completedTasks.isEmpty())
        assertEquals("completed", completed.recordedState)
        assertEquals("dismissed", exam.copy(dismissed = true).recordedState)
    }

    @Test fun legacyExportsWhitelistOldAcademicFieldsAndOmitUnknownStartDate() {
        val hidden = buildJsonObject { put("privateCompatibility", "secret-compatibility") }
        val expandedSemester = semester.copy(endDate = "2027-02-01", showExamWeeks = true,
            statuses = listOf(AcademicStatus("ready", "secret-status", "#112233")), compatibilityFields = hidden)
        val expandedCourse = course.copy(moduleCode = "secret-module", sourceURL = "https://private.invalid/",
            scheduleNote = "secret-schedule", activeWeeks = listOf(3),
            entries = mapOf(3 to AcademicWeekEntry(notes = "secret-week-note")), externalID = "secret-course-external", compatibilityFields = hidden)
        val dismissed = exam.copy(dismissed = true, notes = "secret-assessment-note", externalID = "secret-exam-external",
            recurrence = JsonPrimitive("secret-recurrence"), compatibilityFields = hidden)
        val linked = WorkTask(id = "prep", title = "Reviewed preparation", sourceAssessmentID = exam.id, sourceCourseID = course.id,
            sourceWeek = 3, notes = "secret-preparation-note")
        val source = snapshot().copy(semesters = listOf(expandedSemester), courses = listOf(expandedCourse), assessments = listOf(dismissed),
            tasks = listOf(linked, WorkTask(title = "secret-week-task", sourceCourseID = course.id, sourceWeek = 3)),
            habits = listOf(Habit(title = "secret-habit")), preferences = Preferences(userName = "secret-profile"))
        val shared = Json.parseToJsonElement(AssistantContextExporter.sharedAcademicContext(source, semester.id)).jsonObject
        val offline = Json.parseToJsonElement(AssistantContextExporter.build(source, semester.id).substringAfterLast("\n\n")).jsonObject
        val sharedSemester = shared.getValue("semesters").jsonArray.single().jsonObject
        val offlineSemester = offline.getValue("semesters").jsonArray.single().jsonObject
        assertFalse("startDate" in sharedSemester)
        assertFalse("startDate" in offlineSemester)
        assertEquals(setOf("id", "name"), sharedSemester.getValue("courses").jsonArray.single().jsonObject.keys)
        assertEquals(setOf("id", "semesterID", "name", "archived"), offline.getValue("courses").jsonArray.single().jsonObject.keys)
        assertEquals("dismissed", sharedSemester.getValue("existingAssessments").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("dismissed", offline.getValue("assessments").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("", offline.getValue("assessments").jsonArray.single().jsonObject.getValue("notes").jsonPrimitive.content)
        val prep = offline.getValue("preparationTasks").jsonArray.single().jsonObject
        assertFalse("sourceCourseID" in prep)
        assertFalse("sourceWeek" in prep)
        for (secret in listOf("secret-compatibility", "secret-status", "secret-module", "private.invalid", "secret-schedule",
            "secret-week-note", "secret-course-external", "secret-exam-external", "secret-recurrence", "secret-assessment-note",
            "secret-preparation-note", "secret-week-task", "secret-habit", "secret-profile")) {
            assertFalse(shared.toString().contains(secret), secret)
            assertFalse(offline.toString().contains(secret), secret)
        }
        val explicitlyShared = Json.parseToJsonElement(AssistantContextExporter.sharedAcademicContext(source, semester.id, includeNotes = true)).jsonObject
        assertTrue(explicitlyShared.toString().contains("secret-assessment-note"))
        assertFalse(explicitlyShared.toString().contains("secret-week-note"))
    }
}
