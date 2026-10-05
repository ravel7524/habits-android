package ch.habits.android.data

import ch.habits.android.core.AcademicPlan
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSnapshot(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val semesters: List<AcademicSemester> = emptyList(),
    val courses: List<AcademicCourse> = emptyList(),
    val assessments: List<AcademicAssessment> = emptyList(),
    val tasks: List<WorkTask> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val habitLogs: List<HabitLog> = emptyList(),
    val receipts: List<PlanReceipt> = emptyList(),
    val preferences: Preferences = Preferences(),
)

data class WorkspaceState(
    val snapshot: WorkspaceSnapshot = WorkspaceSnapshot(),
    val recoveryRequired: Boolean = false,
    val error: String? = null,
    val backupAvailable: Boolean = false,
)

@Serializable
data class AcademicSemester(
    val id: String,
    val title: String,
    val startDate: String,
    val weekCount: Int,
    val timeZoneIdentifier: String,
    val archived: Boolean = false,
)

@Serializable
data class AcademicCourse(
    val id: String,
    val semesterID: String,
    val name: String,
    val archived: Boolean = false,
)

@Serializable
data class AcademicAssessment(
    val id: String,
    val semesterID: String,
    val courseID: String,
    val title: String,
    val kind: String,
    val dueDate: String,
    val dueTime: String? = null,
    val notes: String = "",
    val completed: Boolean = false,
    val completedAt: String? = null,
    val archived: Boolean = false,
)

/** Civil dates remain explicit: a null dueTime is a date-only deadline. */
@Serializable
data class WorkTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: String = "task",
    val notes: String = "",
    val dueDate: String? = null,
    val dueTime: String? = null,
    val sourceZone: String? = null,
    val priority: String = "medium",
    val minutes: Int = 60,
    val completed: Boolean = false,
    val completedAt: String? = null,
    val archived: Boolean = false,
    val sourceAssessmentID: String? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
)

@Serializable
data class Appointment(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startDate: String,
    val startTime: String,
    val endDate: String,
    val endTime: String,
    val timeZoneIdentifier: String,
    val notes: String = "",
    val archived: Boolean = false,
)

/** ISO weekday numbers: Monday=1 through Sunday=7. */
@Serializable
data class Habit(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val weekdays: List<Int> = (1..7).toList(),
    val notes: String = "",
    val archived: Boolean = false,
)

@Serializable
data class HabitLog(val habitID: String, val date: String, val completed: Boolean = true)

@Serializable
data class Preferences(val language: String = "system", val theme: String = "system", val userName: String = "")

@Serializable
enum class ReceiptState { APPLIED, UNDONE }

/** Expected created values make Undo fail closed after user edits or completion. */
@Serializable
data class PlanReceipt(
    val proposalID: String,
    val digest: String,
    val source: String,
    val plan: AcademicPlan,
    val createdSemesters: List<AcademicSemester> = emptyList(),
    val createdCourses: List<AcademicCourse> = emptyList(),
    val createdAssessments: List<AcademicAssessment> = emptyList(),
    val createdTasks: List<WorkTask> = emptyList(),
    val state: ReceiptState = ReceiptState.APPLIED,
    val appliedAt: String,
    val undoneAt: String? = null,
)

data class PlanCounts(val semesters: Int, val courses: Int, val assessments: Int, val preparationTasks: Int)

/** Only repository-created previews are accepted; the revision guards the review. */
data class PlanPreview internal constructor(
    val plan: AcademicPlan,
    val baselineRevision: Long,
    val counts: PlanCounts,
    val alreadyHandled: Boolean,
    val receipt: PlanReceipt?,
    internal val digest: String,
    internal val semesters: List<AcademicSemester>,
    internal val courses: List<AcademicCourse>,
    internal val assessments: List<AcademicAssessment>,
    internal val tasks: List<WorkTask>,
)

class WorkspaceException(message: String) : IllegalStateException(message)
