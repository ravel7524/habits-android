package ch.habits.android.core

import kotlinx.serialization.Serializable

/** Portable proposal data. Constructing or decoding these values never writes app data. */
@Serializable
data class AcademicPlan(
    val format: String = "habits.academic-plan",
    val version: Int = 1,
    val proposalID: String,
    val source: String,
    val semester: PlanSemester,
    val courses: List<PlanCourse>,
    val assessments: List<PlanAssessment>,
    val preparationTasks: List<PlanPreparation>,
)

@Serializable
data class PlanSemester(
    val id: String,
    val title: String,
    val startDate: String,
    val weekCount: Int,
    val timeZoneIdentifier: String,
)

@Serializable
data class PlanCourse(val id: String, val name: String)

@Serializable
data class PlanAssessment(
    val id: String,
    val courseID: String,
    val title: String,
    val kind: String,
    val dueDate: String,
    val dueTime: String? = null,
    val notes: String = "",
)

@Serializable
data class PlanPreparation(
    val id: String,
    val assessmentID: String,
    val title: String,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val estimatedWorkMinutes: Int,
    val notes: String = "",
)

/** Describes the invalid field, without including potentially private field contents. */
class PlanValidationException(message: String) : IllegalArgumentException(message)
