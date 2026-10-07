package ch.habits.android.semester

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val emptyBoardFields = JsonObject(emptyMap())

/** Portable imported board values. Field presence is source metadata, never a wire wrapper. */
@Serializable
data class BoardSemester(
    val schemaVersion: Int = 3,
    val id: String,
    val title: String,
    val weekCount: Int = 14,
    val statuses: List<BoardStatus> = emptyList(),
    val courses: List<BoardCourse> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val showExamWeeks: Boolean = false,
    val timeZoneIdentifier: String? = null,
    val unknownFields: JsonObject = emptyBoardFields,
    val providedFields: Set<String> = FIELDS,
) {
    companion object {
        val FIELDS = setOf("schemaVersion", "id", "title", "weekCount", "statuses", "courses", "startDate", "endDate", "showExamWeeks", "timeZoneIdentifier")
    }
}

@Serializable
data class BoardStatus(
    val id: String,
    val name: String,
    val colorHex: String = "#64748B",
    val unknownFields: JsonObject = emptyBoardFields,
    val providedFields: Set<String> = FIELDS,
) {
    companion object { val FIELDS = setOf("id", "name", "colorHex") }
}

@Serializable
data class BoardCourse(
    val id: String,
    val name: String,
    val activeWeeks: List<Int> = emptyList(),
    val entries: Map<Int, BoardWeekEntry> = emptyMap(),
    val moduleCode: String? = null,
    val sourceURL: String? = null,
    val scheduleNote: String? = null,
    val assessments: List<BoardAssessment> = emptyList(),
    val unknownFields: JsonObject = emptyBoardFields,
    val providedFields: Set<String> = FIELDS,
) {
    companion object {
        val FIELDS = setOf("id", "name", "activeWeeks", "entries", "moduleCode", "sourceURL", "scheduleNote", "assessments")
        val LEGACY_FIELDS = setOf("examDate", "examTime", "examNote")
    }
}

@Serializable
data class BoardWeekEntry(
    val statusID: String? = null,
    val notes: String = "",
    val unknownFields: JsonObject = emptyBoardFields,
    val providedFields: Set<String> = FIELDS,
) {
    companion object { val FIELDS = setOf("statusID", "notes") }
}

@Serializable
data class BoardAssessment(
    val id: String,
    val title: String,
    val kind: String = "deadline",
    val dueDate: String,
    val dueTime: String? = null,
    val notes: String = "",
    val state: String = "pending",
    val completedAt: Double? = null,
    val recurrence: JsonElement? = null,
    val reminderDays: List<Int> = emptyList(),
    val snoozedUntil: Double? = null,
    val unknownFields: JsonObject = emptyBoardFields,
    val providedFields: Set<String> = FIELDS - DEVICE_FIELDS - setOf("recurrence"),
) {
    companion object {
        val FIELDS = setOf("id", "title", "kind", "dueDate", "dueTime", "notes", "state", "completedAt", "recurrence", "reminderDays", "snoozedUntil")
        val DEVICE_FIELDS = setOf("reminderDays", "snoozedUntil")
    }
}

/** Explicit reviewed assistant handoff; this is a proposal, never permission to write. */
@Serializable
data class BoardProposal(
    val format: String = "habits.semester-board-proposal",
    val version: Int = 1,
    val proposalID: String,
    val source: String,
    val expectedBoardDigest: String? = null,
    val semester: BoardSemester,
)

class BoardValidationException(message: String) : IllegalArgumentException(message)
