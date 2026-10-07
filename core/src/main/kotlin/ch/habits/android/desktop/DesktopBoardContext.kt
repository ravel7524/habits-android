package ch.habits.android.desktop

import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.semester.BoardAssessment
import ch.habits.android.semester.BoardCourse
import ch.habits.android.semester.BoardSemester
import ch.habits.android.semester.BoardStatus
import ch.habits.android.semester.BoardWeekEntry
import ch.habits.android.semester.SemesterBoardCodec
import java.time.Instant
import kotlinx.serialization.json.*

/** Whitelisted selected-semester sharing, separate from the lossless local Board export. */
object DesktopBoardContext {
    private val noFields = JsonObject(emptyMap())
    private val semesterFields = BoardSemester.FIELDS
    private val courseFields = BoardCourse.FIELDS - "sourceURL"
    private val assessmentFields = BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS - "recurrence"
    private val contextFields = setOf("format", "version", "capturedAt", "boardDigest", "notesIncluded", "weekProgressIncluded", "semester")

    fun encode(semester: BoardSemester, boardDigest: String, notesIncluded: Boolean = true,
        weekProgressIncluded: Boolean = true, capturedAt: String = Instant.now().toString()): String {
        val shared = semester.copy(unknownFields = noFields,
            providedFields = semester.providedFields.intersect(semesterFields) + setOf("schemaVersion", "id", "title", "weekCount", "statuses", "courses"),
            statuses = semester.statuses.map { it.copy(unknownFields = noFields, providedFields = BoardStatus.FIELDS) },
            courses = semester.courses.map { course ->
                course.copy(sourceURL = null, unknownFields = noFields,
                    scheduleNote = course.scheduleNote.takeIf { notesIncluded },
                    providedFields = course.providedFields.intersect(courseFields).let { if (notesIncluded) it else it - "scheduleNote" } + setOf("id", "name", "entries"),
                    entries = if (!weekProgressIncluded) emptyMap() else course.entries.mapValues { (_, entry) ->
                        entry.copy(notes = if (notesIncluded) entry.notes else "", unknownFields = noFields,
                            providedFields = entry.providedFields.intersect(BoardWeekEntry.FIELDS).let { if (notesIncluded) it else it - "notes" })
                    },
                    assessments = course.assessments.map { assessment ->
                        assessment.copy(notes = if (notesIncluded) assessment.notes else "", recurrence = null,
                            reminderDays = emptyList(), snoozedUntil = null, unknownFields = noFields,
                            providedFields = assessment.providedFields.intersect(assessmentFields).let { if (notesIncluded) it else it - "notes" })
                    })
            })
        val encoded = JsonObject(mapOf("format" to JsonPrimitive("habits.semester-board-context"),
            "version" to JsonPrimitive(1), "capturedAt" to JsonPrimitive(capturedAt), "boardDigest" to JsonPrimitive(boardDigest),
            "notesIncluded" to JsonPrimitive(notesIncluded), "weekProgressIncluded" to JsonPrimitive(weekProgressIncluded),
            "semester" to AcademicPlanCodec.parseStrictJson(SemesterBoardCodec.encodeContextSemester(shared)))).toString()
        validate(encoded)
        return encoded
    }

    /** Reject all compatibility/device/URL fields even if a caller constructs raw context JSON. */
    fun validate(text: String) {
        val root = AcademicPlanCodec.parseStrictJson(text, 2 * 1024 * 1024).jsonObject
        require(root.keys == contextFields) { "Unsupported Semester Board context fields." }
        require(root["format"] == JsonPrimitive("habits.semester-board-context") && root["version"] == JsonPrimitive(1)) { "Unsupported Semester Board context." }
        val capturedAt = root.getValue("capturedAt").jsonPrimitive.also { require(it.isString) }.content
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z").matches(capturedAt)) { "Invalid context capture time." }
        Instant.parse(capturedAt)
        val digest = root.getValue("boardDigest").jsonPrimitive.also { require(it.isString) }.content
        require(Regex("[0-9a-f]{64}").matches(digest)) { "Invalid Board context digest." }
        fun included(key: String): Boolean = root.getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
        val notes = included("notesIncluded")
        val progress = included("weekProgressIncluded")
        val semester = root.getValue("semester").jsonObject
        require(semester.keys.all { it in semesterFields } && semester["schemaVersion"] == JsonPrimitive(3)) { "Unsupported shared semester fields." }
        require(("startDate" in semester) == ("endDate" in semester)) { "Shared semester date coverage must stay explicit." }
        val statuses = semester.getValue("statuses").jsonArray
        require(statuses.size <= 64 && statuses.all { it.jsonObject.keys == BoardStatus.FIELDS }) { "Invalid shared statuses." }
        val courses = semester.getValue("courses").jsonArray
        require(courses.size <= 10) { "Selected Board context supports at most ten courses; nothing was shared." }
        var assessments = 0
        courses.forEach { courseValue ->
            val course = courseValue.jsonObject
            require(course.keys.all { it in courseFields }) { "Unsupported shared course fields." }
            require(notes || "scheduleNote" !in course) { "Notes were excluded from this context." }
            course["activeWeeks"]?.let { require(it.jsonArray.size <= 52) }
            val entries = course.getValue("entries").jsonObject
            require(entries.size <= 52 && (progress || entries.isEmpty())) { "Week progress was excluded from this context." }
            entries.values.forEach { entryValue ->
                val entry = entryValue.jsonObject
                require(entry.keys.all { it in BoardWeekEntry.FIELDS } && (notes || "notes" !in entry)) { "Unsupported shared week fields." }
            }
            val rows = course["assessments"]?.jsonArray ?: JsonArray(emptyList())
            assessments += rows.size
            rows.forEach { value ->
                val row = value.jsonObject
                require(row.keys.all { it in assessmentFields } && (notes || "notes" !in row)) { "Unsupported shared assessment fields." }
            }
        }
        require(assessments <= 200) { "Selected Board context supports at most 200 assessments; nothing was shared." }
        SemesterBoardCodec.decodeContextSemester(semester.toString())
    }
}
