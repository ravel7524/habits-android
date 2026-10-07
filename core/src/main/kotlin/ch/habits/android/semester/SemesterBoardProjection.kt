package ch.habits.android.semester

import ch.habits.android.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Calendar columns are source-relative weeks, never ISO week numbers or mastery scores. */
object SemesterBoardProjection {
    fun statuses(semester: AcademicSemester): List<AcademicStatus> = semester.statuses.ifEmpty {
        listOf(Triple("todo", "To do", "#E45656"), Triple("done", "Done", "#37A269"),
            Triple("revisit", "Revisit", "#EAAB46"), Triple("progress", "Work in progress", "#4A86DE")).map { (key, name, color) ->
            AcademicStatus(UUID.nameUUIDFromBytes("academic/semester/${semester.id.lowercase()}/default-status/$key".toByteArray(Charsets.UTF_8)).toString(), name, color)
        }
    }

    fun courses(snapshot: WorkspaceSnapshot, semester: AcademicSemester): List<AcademicCourse> =
        snapshot.courses.filter { it.semesterID == semester.id && !it.archived }
            .sortedWith(compareBy { it.boardOrder ?: Int.MAX_VALUE })

    fun teachingEnd(semester: AcademicSemester): LocalDate? = semester.endDate?.let { parse(it) }
        ?: semester.startDate?.let { start -> runCatching { LocalDate.parse(start).plusDays(semester.weekCount * 7L - 1) }.getOrNull() }

    fun displayWeekCount(snapshot: WorkspaceSnapshot, semester: AcademicSemester): Int {
        val base = semester.weekCount.coerceIn(1, 52)
        val start = semester.startDate?.let(::parse) ?: return base
        val end = teachingEnd(semester) ?: return base
        if (!semester.showExamWeeks) return base
        val last = snapshot.assessments.asSequence().filter { it.semesterID == semester.id && !it.archived && !it.dismissed }
            .mapNotNull { parse(it.dueDate) }.maxOrNull() ?: return base
        if (last <= end || last < start) return base
        return maxOf(base, (ChronoUnit.DAYS.between(start, last) / 7 + 1).coerceAtMost(52).toInt())
    }

    fun dateForWeek(semester: AcademicSemester, week: Int): String? = if (week !in 1..52) null else
        semester.startDate?.let { start -> runCatching { LocalDate.parse(start).plusDays((week - 1) * 7L) }.getOrNull() }
            ?.takeIf { it.year in 1..9999 }?.toString()

    fun displayedWeek(semester: AcademicSemester, date: String, snapshot: WorkspaceSnapshot): Int? {
        val start = semester.startDate?.let(::parse) ?: return null
        val day = parse(date) ?: return null
        val end = teachingEnd(semester) ?: return null
        if (day < start) return null
        val count = displayWeekCount(snapshot, semester)
        val hasLater = semester.showExamWeeks && snapshot.assessments.any {
            it.semesterID == semester.id && !it.archived && !it.dismissed && (parse(it.dueDate)?.isAfter(end) == true)
        }
        val last = if (hasLater) runCatching { start.plusDays(count * 7L - 1) }.getOrNull() ?: end else end
        val week = (ChronoUnit.DAYS.between(start, day) / 7 + 1).toInt()
        return week.takeIf { day <= last && it in 1..count }
    }

    fun currentWeek(snapshot: WorkspaceSnapshot, semester: AcademicSemester, now: Instant = Instant.now()): Int? =
        runCatching { displayedWeek(semester, now.atZone(ZoneId.of(semester.timeZoneIdentifier)).toLocalDate().toString(), snapshot) }.getOrNull()

    fun assessments(snapshot: WorkspaceSnapshot, course: AcademicCourse, semester: AcademicSemester, week: Int): List<AcademicAssessment> =
        snapshot.assessments.filter { !it.archived && it.courseID == course.id && displayedWeek(semester, it.dueDate, snapshot) == week }
            .sortedWith(compareBy({ it.dueDate }, { it.dueTime.orEmpty() }, { it.boardOrder ?: Int.MAX_VALUE }))

    fun clockNeedsReview(assessment: AcademicAssessment, semester: AcademicSemester): Boolean = assessment.dueTime != null &&
        runCatching {
            val local = java.time.LocalDateTime.of(LocalDate.parse(assessment.dueDate), java.time.LocalTime.parse(assessment.dueTime))
            ZoneId.of(semester.timeZoneIdentifier).rules.getValidOffsets(local).size != 1
        }.getOrDefault(true)

    private fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}
