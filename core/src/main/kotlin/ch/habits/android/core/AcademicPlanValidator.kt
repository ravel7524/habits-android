package ch.habits.android.core

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

object AcademicPlanValidator {
    const val MAX_BYTES: Int = 2 * 1024 * 1024
    const val MAX_COURSES: Int = 10
    const val MAX_ASSESSMENTS: Int = 200
    const val MAX_PREPARATION_TASKS: Int = 200
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val timePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private val zonePattern = Regex("(?:UTC|[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)+)")
    private val kinds = setOf("exam", "presentation", "essay", "deadline")

    fun validate(plan: AcademicPlan) {
        checkPlan(plan.format == "habits.academic-plan", "format", "unsupported format")
        checkPlan(plan.version == 1, "version", "unsupported version")
        text(plan.source, "source", 300)
        text(plan.semester.title, "semester.title", 200)
        val start = parseDate(plan.semester.startDate, "semester.startDate")
        checkPlan(plan.semester.weekCount in 1..52, "semester.weekCount", "must be from 1 through 52")
        val zone = parseZone(plan.semester.timeZoneIdentifier)
        resolve(start, LocalTime.NOON, zone, "semester.startDate")
        val end = try {
            start.plusDays(plan.semester.weekCount * 7L - 1)
        } catch (_: DateTimeException) {
            invalidPlan("semester.startDate", "semester date range is invalid")
        }
        checkPlan(end.year in 1..9999, "semester.startDate", "semester date range exceeds supported years")
        checkPlan(plan.courses.size <= MAX_COURSES, "courses", "too many courses")
        checkPlan(plan.assessments.size <= MAX_ASSESSMENTS, "assessments", "too many assessments")
        checkPlan(plan.preparationTasks.size <= MAX_PREPARATION_TASKS, "preparationTasks", "too many preparation tasks")

        val identities = mutableSetOf<String>()
        fun identity(value: String, path: String): String {
            val canonical = AcademicRecordIds.canonicalUuid(value)
            checkPlan(identities.add(canonical), path, "duplicates another proposal or entity UUID")
            return canonical
        }
        identity(plan.proposalID, "proposalID")
        identity(plan.semester.id, "semester.id")
        val courseIds = mutableSetOf<String>()
        plan.courses.forEachIndexed { index, course ->
            courseIds += identity(course.id, "courses[$index].id")
            text(course.name, "courses[$index].name", 200)
        }

        val deadlines = mutableMapOf<String, Instant>()
        val assessmentKeys = mutableSetOf<AssessmentKey>()
        plan.assessments.forEachIndexed { index, assessment ->
            val path = "assessments[$index]"
            val id = identity(assessment.id, "$path.id")
            val courseId = AcademicRecordIds.canonicalUuid(assessment.courseID)
            checkPlan(courseId in courseIds, "$path.courseID", "must reference a proposal course")
            text(assessment.title, "$path.title", 200)
            text(assessment.notes, "$path.notes", 4000, notes = true)
            checkPlan(assessment.kind in kinds, "$path.kind", "unsupported assessment kind")
            val day = parseDate(assessment.dueDate, "$path.dueDate")
            val clock = assessment.dueTime?.let { parseTime(it, "$path.dueTime") } ?: LocalTime.of(23, 59, 59)
            deadlines[id] = resolve(day, clock, zone, "$path.dueDate/dueTime")
            val key = AssessmentKey(courseId, Normalizer.normalize(assessment.title, Normalizer.Form.NFC),
                assessment.kind, assessment.dueDate, assessment.dueTime)
            checkPlan(assessmentKeys.add(key), path, "duplicates another assessment's course/title/kind/date/time")
        }

        plan.preparationTasks.forEachIndexed { index, task ->
            val path = "preparationTasks[$index]"
            identity(task.id, "$path.id")
            val assessmentId = AcademicRecordIds.canonicalUuid(task.assessmentID)
            val assessmentDeadline = deadlines[assessmentId]
                ?: invalidPlan("$path.assessmentID", "must reference a proposal assessment")
            text(task.title, "$path.title", 200)
            text(task.notes, "$path.notes", 4000, notes = true)
            checkPlan(task.estimatedWorkMinutes in 0..1440, "$path.estimatedWorkMinutes", "must be from 0 through 1440")
            checkPlan(task.dueTime == null || task.dueDate != null, "$path.dueDate", "is required with a due time")
            if (task.dueDate != null) {
                val day = parseDate(task.dueDate, "$path.dueDate")
                if (task.dueTime == null) resolve(day, LocalTime.MIDNIGHT, zone, "$path.dueDate")
                val clock = task.dueTime?.let { parseTime(it, "$path.dueTime") } ?: LocalTime.of(23, 59, 59)
                val deadline = resolve(day, clock, zone, "$path.dueDate/dueTime")
                checkPlan(deadline <= assessmentDeadline, "$path.dueDate/dueTime", "must not follow the linked assessment")
            }
        }
    }

    /** Null means storage midnight. Deadline comparisons alone use civil day-end. */
    fun resolveInstant(date: String, time: String?, zone: String): Instant =
        resolve(parseDate(date, "date"), time?.let { parseTime(it, "time") } ?: LocalTime.MIDNIGHT, parseZone(zone), "date/time")

    private fun resolve(day: LocalDate, clock: LocalTime, zone: ZoneId, path: String): Instant {
        val local = LocalDateTime.of(day, clock)
        val offsets = zone.rules.getValidOffsets(local)
        checkPlan(offsets.size == 1, path, "local date/time is nonexistent or ambiguous")
        val instant = local.toInstant(offsets.single())
        // Match the portable Gregorian range used by the Python/Swift codecs.
        val utcYear = LocalDateTime.ofInstant(instant, ZoneId.of("UTC")).year
        checkPlan(utcYear in 1..9999, path, "local instant exceeds supported years")
        return instant
    }

    private fun parseDate(value: String, path: String): LocalDate {
        checkPlan(datePattern.matches(value), path, "must use Gregorian YYYY-MM-DD")
        val result = try { LocalDate.parse(value) } catch (_: DateTimeException) {
            invalidPlan(path, "must be a valid Gregorian date")
        }
        checkPlan(result.year in 1..9999, path, "date exceeds supported years")
        return result
    }

    private fun parseTime(value: String, path: String): LocalTime {
        checkPlan(timePattern.matches(value), path, "must use 24-hour HH:mm")
        return LocalTime.parse(value)
    }

    private fun parseZone(value: String): ZoneId {
        text(value, "semester.timeZoneIdentifier", 200)
        checkPlan(zonePattern.matches(value), "semester.timeZoneIdentifier", "must use UTC or an IANA path")
        checkPlan(value in ZoneId.getAvailableZoneIds(), "semester.timeZoneIdentifier", "IANA time zone is unavailable")
        return try { ZoneId.of(value) } catch (_: DateTimeException) {
            invalidPlan("semester.timeZoneIdentifier", "IANA time zone is unavailable")
        }
    }

    private fun text(value: String, path: String, limit: Int, notes: Boolean = false) {
        checkPlan(validUnicode(value), path, "must contain valid Unicode")
        checkPlan(value.codePointCount(0, value.length) <= limit, path, "exceeds Unicode code-point limit")
        if (!notes) {
            checkPlan(value.isNotEmpty() && !edgeWhitespace(value.codePointAt(0)) &&
                !edgeWhitespace(value.codePointBefore(value.length)), path, "must be nonempty without surrounding whitespace")
        }
        val unsupportedControl = value.codePoints().anyMatch { point ->
            isControlOrFormat(point) &&
                !(notes && point in setOf(9, 10, 13))
        }
        checkPlan(!unsupportedControl, path, "contains unsupported control characters")
    }

    private fun edgeWhitespace(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85

    // The schema's format controls supplement older Android Unicode tables.
    private fun isControlOrFormat(point: Int): Boolean {
        val type = Character.getType(point)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
            point in 0x0600..0x0605 || point == 0x061c || point == 0x06dd || point == 0x070f ||
            point in 0x0890..0x0891 || point == 0x08e2 || point == 0x180e || point in 0x200b..0x200f ||
            point in 0x202a..0x202e || point in 0x2060..0x2064 || point in 0x2066..0x206f ||
            point == 0xfeff || point in 0xfff9..0xfffb || point == 0x110bd || point == 0x110cd ||
            point in 0x13430..0x1343f || point in 0x1bca0..0x1bca3 || point in 0x1d173..0x1d17a ||
            point == 0xe0001 || point in 0xe0020..0xe007f
    }

    private data class AssessmentKey(val courseId: String, val title: String, val kind: String, val date: String, val time: String?)
}

/** Stable namespace shared with AcademicRecordID in AcademicCore. */
object AcademicRecordIds {
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun canonicalUuid(id: String): String {
        checkPlan(uuidPattern.matches(id), "id", "must be a hyphenated UUID")
        return UUID.fromString(id).toString()
    }
    fun semester(id: String): String = "academic/semester/${canonicalUuid(id)}"
    fun course(semesterID: String, courseID: String): String = "${semester(semesterID)}/course/${canonicalUuid(courseID)}"
    fun assessment(semesterID: String, courseID: String, assessmentID: String): String =
        "${course(semesterID, courseID)}/assessment/${canonicalUuid(assessmentID)}"
}

internal fun invalidPlan(path: String, reason: String): Nothing = throw PlanValidationException("$path: $reason")
internal fun checkPlan(condition: Boolean, path: String, reason: String) {
    if (!condition) invalidPlan(path, reason)
}

internal fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (Character.isHighSurrogate(char)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
            index += 2
        } else {
            if (Character.isLowSurrogate(char)) return false
            index += 1
        }
    }
    return true
}

internal fun boundedUtf8(value: String, maxBytes: Int = AcademicPlanValidator.MAX_BYTES) {
    checkPlan(maxBytes in 1..4 * 1024 * 1024, "JSON", "byte limit must be from 1 through 4 MiB")
    checkPlan(value.length <= maxBytes, "JSON", "exceeds $maxBytes-byte UTF-8 limit")
    val encoded = try {
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    } catch (_: CharacterCodingException) {
        invalidPlan("JSON", "must contain valid Unicode")
    }
    checkPlan(encoded.remaining() <= maxBytes, "JSON", "exceeds $maxBytes-byte UTF-8 limit")
}
