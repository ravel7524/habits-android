package ch.habits.android.data

import ch.habits.android.core.AcademicPlanValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TodayFacts(
    val date: LocalDate,
    val tasks: List<WorkTask>,
    val appointments: List<Appointment>,
    val habits: List<Habit>,
    val completedHabitIDs: Set<String>,
    val assessments: List<AcademicAssessment>,
)

data class RecordedReportFacts(
    val completedTasks: List<WorkTask>,
    val completedAssessments: List<AcademicAssessment>,
    val habitLogs: List<HabitLog>,
    val coverage: List<String>,
)

/** Recorded-only projections. Missing logs never become missed habits or study estimates. */
object WorkspaceFacts {
    fun today(snapshot: WorkspaceSnapshot, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): TodayFacts {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val tasks = snapshot.tasks.filter { item ->
            !item.archived && !item.completed && item.dueDate?.let { day ->
                if (item.dueTime == null) LocalDate.parse(day) <= date
                else AcademicPlanValidator.resolveInstant(day, item.dueTime, item.sourceZone ?: zone.id) < end
            } == true
        }.sortedWith(compareBy<WorkTask> { it.dueDate }.thenBy { it.dueTime ?: "" }.thenBy { it.id })
        val appointments = snapshot.appointments.filter { item ->
            !item.archived && AcademicPlanValidator.resolveInstant(item.startDate, item.startTime, item.timeZoneIdentifier) < end &&
                AcademicPlanValidator.resolveInstant(item.endDate, item.endTime, item.timeZoneIdentifier) > start
        }.sortedBy { AcademicPlanValidator.resolveInstant(it.startDate, it.startTime, it.timeZoneIdentifier) }
        val habits = snapshot.habits.filter { !it.archived && date.dayOfWeek.value in it.weekdays }
        val completedHabitIDs = snapshot.habitLogs.filter { it.date == date.toString() && it.completed }.map { it.habitID }.toSet()
        val assessments = snapshot.assessments.filter { item ->
            if (item.archived || item.completed) false else {
                val semester = snapshot.semesters.find { it.id == item.semesterID && !it.archived }
                val course = snapshot.courses.find { it.id == item.courseID && !it.archived }
                semester != null && course != null && if (item.dueTime == null) {
                    // Preserve the recorded civil day and show source timezone in the UI.
                    LocalDate.parse(item.dueDate) <= date
                } else AcademicPlanValidator.resolveInstant(item.dueDate, item.dueTime, semester.timeZoneIdentifier) < end
            }
        }.sortedWith(compareBy<AcademicAssessment> { it.dueDate }.thenBy { it.dueTime ?: "" }.thenBy { it.id })
        return TodayFacts(date, tasks, appointments, habits, completedHabitIDs, assessments)
    }

    fun report(snapshot: WorkspaceSnapshot, start: LocalDate, endExclusive: LocalDate,
               zone: ZoneId = ZoneId.systemDefault()): RecordedReportFacts {
        requireWorkspace(endExclusive > start, "Choose a nonempty report period.")
        val from = start.atStartOfDay(zone).toInstant()
        val until = endExclusive.atStartOfDay(zone).toInstant()
        fun inPeriod(value: String?): Boolean = value?.let { Instant.parse(it) in from..<until } ?: false
        val tasks = snapshot.tasks.filter { it.completed && inPeriod(it.completedAt) }
        val assessments = snapshot.assessments.filter { it.completed && inPeriod(it.completedAt) }
        val logs = snapshot.habitLogs.filter { it.completed && LocalDate.parse(it.date) in start..<endExclusive }
        val untimed = snapshot.tasks.count { it.completed && it.completedAt == null } +
            snapshot.assessments.count { it.completed && it.completedAt == null }
        val coverage = buildList {
            add("Based on recorded local completions and habit logs. Missing logs are not evidence of missed habits.")
            if (untimed > 0) add("$untimed completed items have no completion timestamp and were excluded from this period.")
            add("Preparation completion and exam completion are separate records. Task estimates are planned minutes, not time studied.")
        }
        return RecordedReportFacts(tasks, assessments, logs, coverage)
    }
}

/** A user-requested export omits private notes by default and includes only linked preparation. */
object AssistantContextExporter {
    /** Phone-to-desktop wire snapshot: academic metadata only, explicitly selected by the user. */
    fun sharedAcademicContext(snapshot: WorkspaceSnapshot, semesterID: String, includeNotes: Boolean = false): String {
        val selected = canonical(semesterID)
        val semester = snapshot.semesters.find { it.id == selected && !it.archived }
            ?: throw WorkspaceException("Select an active semester before sharing academic context.")
        val courses = snapshot.courses.filter { it.semesterID == selected && !it.archived }
        val courseIDs = courses.map { it.id }.toSet()
        val assessments = snapshot.assessments.filter { it.semesterID == selected && !it.archived && it.courseID in courseIDs }
        requireWorkspace(courses.size <= 10 && assessments.size <= 200,
            "Academic context exceeds the supported course or assessment limit. No records were shared.")
        val context = buildJsonObject {
            put("format", "habits.academic-context")
            put("version", 1)
            put("capturedAt", Instant.now().toString())
            put("semesters", JsonArray(listOf(buildJsonObject {
                put("id", semester.id)
                put("title", semester.title)
                put("startDate", semester.startDate)
                put("weekCount", semester.weekCount)
                put("timeZoneIdentifier", semester.timeZoneIdentifier)
                put("courses", JsonArray(courses.map { course -> buildJsonObject {
                    put("id", course.id)
                    put("name", course.name)
                } }))
                put("existingAssessments", JsonArray(assessments.map { item -> buildJsonObject {
                    put("id", item.id)
                    put("courseID", item.courseID)
                    put("title", item.title)
                    put("kind", item.kind)
                    put("dueDate", item.dueDate)
                    item.dueTime?.let { put("dueTime", it) }
                    put("notes", if (includeNotes) item.notes else "")
                    put("state", if (item.completed) "completed" else "pending")
                } }))
            })))
        }.toString()
        requireWorkspace(context.toByteArray(Charsets.UTF_8).size <= AcademicPlanValidator.MAX_BYTES,
            "Academic context exceeds the 2 MiB limit. No records were shared.")
        return context
    }

    fun build(snapshot: WorkspaceSnapshot, semesterID: String? = null, includeNotes: Boolean = false): String {
        val selected = semesterID?.let(::canonical)
        val semesters = snapshot.semesters.filter { selected == null || it.id == selected }
        val semesterIDs = semesters.map { it.id }.toSet()
        val courses = snapshot.courses.filter { it.semesterID in semesterIDs }
        val assessments = snapshot.assessments.filter { it.semesterID in semesterIDs }.map {
            if (includeNotes) it else it.copy(notes = "")
        }
        val assessmentIDs = assessments.map { it.id }.toSet()
        val preparation = snapshot.tasks.filter { it.sourceAssessmentID in assessmentIDs }.map {
            if (includeNotes) it else it.copy(notes = "")
        }
        val context = WorkspaceRepository.workspaceJson.encodeToString(AssistantAcademicContext(
            semesters = semesters, courses = courses, assessments = assessments, preparationTasks = preparation,
            notesIncluded = includeNotes))
        return """
            Create a reviewable academic proposal JSON file for Habits. I will preview and import it myself.
            Use format "habits.academic-plan", version 1, and a fresh globally unique proposalID UUID. Keep IDs stable on retries.
            Required keys: format, version, proposalID, source, semester, courses, assessments, preparationTasks.
            semester: {id, title, startDate: YYYY-MM-DD, weekCount: 1...52, timeZoneIdentifier: IANA zone or UTC}.
            courses: [{id, name}]. Reuse existing semester/course IDs and exact metadata. Include relevant courses in the proposal.
            assessments: [{id, courseID, title, kind: exam|presentation|essay|deadline, dueDate: YYYY-MM-DD, optional dueTime: HH:mm, notes}].
            preparationTasks: [{id, assessmentID, title, optional dueDate: YYYY-MM-DD, optional dueTime: HH:mm, estimatedWorkMinutes: 0...1440, notes}].
            Add new assessments and preparation only. Preserve history, completion, existing data and archived identities. Never invent an unknown date or time.
            Omit unknown optional fields; do not output null or extra keys. Dates/times use the source semester timezone. Reject skipped or repeated DST clock times.
            Preparation must not follow its linked assessment; a date-only deadline means the end of its civil day for this comparison.
            Maximum ten courses, 200 assessments and 200 preparation tasks. Titles/names 200 characters, source 300, notes 4000.
            No edits, deletions, completion marks, notification opt-ins or sync changes. Estimates are planned work, never evidence of study or mastery.
            Exported context below includes academic titles, dates and linked preparation. Private notes ${if (includeNotes) "are included by my explicit choice" else "are omitted"}.

            $context
        """.trimIndent()
    }
}

@Serializable
private data class AssistantAcademicContext(
    val format: String = "habits.academic-context",
    val version: Int = 1,
    val notesIncluded: Boolean,
    val semesters: List<AcademicSemester>,
    val courses: List<AcademicCourse>,
    val assessments: List<AcademicAssessment>,
    val preparationTasks: List<WorkTask>,
)
