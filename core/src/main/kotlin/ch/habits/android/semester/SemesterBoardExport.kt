package ch.habits.android.semester

import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.data.WorkspaceSnapshot
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** A deliberate local export may include compatibility metadata; sharing never does. */
object SemesterBoardExport {
    fun document(snapshot: WorkspaceSnapshot, semesterID: String, includeNotes: Boolean = true,
        includeWeekProgress: Boolean = true, includeCompatibilityFields: Boolean = false): BoardSemester {
        val semester = snapshot.semesters.singleOrNull { it.id.equals(semesterID, true) && !it.archived }
            ?: throw IllegalArgumentException("The selected semester is unavailable.")
        val empty = JsonObject(emptyMap())
        fun fields(value: JsonObject) = if (includeCompatibilityFields) value else empty
        val courses = snapshot.courses.filter { it.semesterID == semester.id && !it.archived }
            .sortedWith(compareBy({ it.boardOrder ?: Int.MAX_VALUE }, { snapshot.courses.indexOf(it) }))
        return BoardSemester(id = semester.id, title = semester.title, weekCount = semester.weekCount,
            startDate = semester.startDate, endDate = semester.endDate ?: semester.startDate?.let {
                LocalDate.parse(it).plusDays(semester.weekCount * 7L - 1).toString()
            }, showExamWeeks = semester.showExamWeeks,
            timeZoneIdentifier = semester.timeZoneIdentifier, unknownFields = fields(semester.compatibilityFields),
            statuses = semester.statuses.map { BoardStatus(it.id, it.name, it.colorHex, fields(it.compatibilityFields)) },
            courses = courses.map { course ->
                BoardCourse(id = course.externalID ?: course.id, name = course.name,
                    moduleCode = course.moduleCode, sourceURL = course.sourceURL.takeIf { includeCompatibilityFields },
                    scheduleNote = course.scheduleNote.takeIf { includeNotes }, activeWeeks = course.activeWeeks.orEmpty(),
                    entries = if (!includeWeekProgress) emptyMap() else course.entries.mapValues { (_, entry) ->
                        BoardWeekEntry(entry.statusID, if (includeNotes) entry.notes else "", fields(entry.compatibilityFields),
                            if (includeNotes) BoardWeekEntry.FIELDS else BoardWeekEntry.FIELDS - "notes")
                    }, unknownFields = fields(course.compatibilityFields),
                    providedFields = BoardCourse.FIELDS.let { if (includeCompatibilityFields) it else it - "sourceURL" }
                        .let { if (includeNotes) it else it - "scheduleNote" }
                        .let { if (course.activeWeeks == null) it - "activeWeeks" else it },
                    assessments = snapshot.assessments.filter { it.courseID == course.id && !it.archived }
                        .sortedWith(compareBy({ it.boardOrder ?: Int.MAX_VALUE }, { snapshot.assessments.indexOf(it) }))
                        .map { assessment ->
                            BoardAssessment(id = assessment.externalID ?: assessment.id, title = assessment.title,
                                kind = assessment.kind, dueDate = assessment.dueDate, dueTime = assessment.dueTime,
                                notes = if (includeNotes) assessment.notes else "", state = assessment.recordedState,
                                completedAt = assessment.completedAt?.let(SemesterBoardCodec::instantToReferenceDate)
                                    ?: if (includeCompatibilityFields) assessment.compatibilityFields["completedAt"]
                                        ?.takeUnless { it is JsonNull }?.jsonPrimitive?.double else null,
                                recurrence = assessment.recurrence.takeIf { includeCompatibilityFields },
                                reminderDays = if (includeCompatibilityFields) assessment.compatibilityFields["reminderDays"]
                                    ?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList() else emptyList(),
                                snoozedUntil = if (includeCompatibilityFields) assessment.compatibilityFields["snoozedUntil"]
                                    ?.takeUnless { it is JsonNull }?.jsonPrimitive?.double else null,
                                unknownFields = fields(assessment.compatibilityFields).let { JsonObject(it - BoardAssessment.FIELDS) },
                                providedFields = (BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS)
                                    .let { if (includeNotes) it else it - "notes" }
                                    .let { if (includeCompatibilityFields) it + assessment.compatibilityFields.keys.intersect(BoardAssessment.DEVICE_FIELDS) else it - "recurrence" })
                        })
            })
    }

    /** Includes retained/archived academic data, but no planner, profile, Calendar or credentials. */
    fun digest(snapshot: WorkspaceSnapshot, semesterID: String): String {
        val semester = snapshot.semesters.singleOrNull { it.id.equals(semesterID, true) }
            ?: throw IllegalArgumentException("The selected semester is unavailable.")
        val academic = snapshot.copy(revision = 0, semesters = listOf(semester),
            courses = snapshot.courses.filter { it.semesterID == semester.id },
            assessments = snapshot.assessments.filter { it.semesterID == semester.id },
            tasks = emptyList(), appointments = emptyList(), habits = emptyList(), habitLogs = emptyList(),
            receipts = emptyList(), boardReceipts = emptyList(), preferences = ch.habits.android.data.Preferences())
        val root = WorkspaceRepository.workspaceJson.parseToJsonElement(WorkspaceRepository.workspaceJson.encodeToString(academic)).jsonObject
        val scope = JsonObject(mapOf("semester" to root.getValue("semesters").jsonArray.single(),
            "courses" to root.getValue("courses"), "assessments" to root.getValue("assessments")))
        return boardSha256(sortedBoardJson(scope).toString())
    }
}

internal fun sortedBoardJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { sortedBoardJson(it.value) })
    is JsonArray -> JsonArray(value.map(::sortedBoardJson))
    else -> value
}

internal fun boardSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
