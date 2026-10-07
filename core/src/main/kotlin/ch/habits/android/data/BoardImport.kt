package ch.habits.android.data

import ch.habits.android.core.AcademicRecordIds
import ch.habits.android.semester.*
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class BoardImportRequest(val proposal: BoardProposal, val rawFile: Boolean = false,
    val selectedCourseIDs: Set<String>? = null, val timeZoneIdentifier: String? = null) {
    companion object {
        /** Content + selection + explicit source zone identify a local handoff; no remote proposal mapping. */
        fun rawFile(document: BoardSemester, selectedCourseIDs: Set<String>? = null,
            timeZoneIdentifier: String? = null): BoardImportRequest {
            val selected = selectedCourseIDs?.map(::canonical)?.sorted()
            val identity = sortedBoardJson(JsonObject(mapOf("semester" to rawDocumentJson(document),
                "selectedCourseIDs" to (selected?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull),
                "timeZoneIdentifier" to (timeZoneIdentifier?.let(::JsonPrimitive) ?: JsonNull)))).toString()
            val id = UUID.nameUUIDFromBytes(("habits/local-board/" + identity).toByteArray(Charsets.UTF_8)).toString()
            return BoardImportRequest(BoardProposal(proposalID = id, source = "Imported Semester Board file", semester = document),
                rawFile = true, selectedCourseIDs = selected?.toSet(), timeZoneIdentifier = timeZoneIdentifier)
        }
    }
}

data class BoardImportCounts(val createdSemesters: Int = 0, val updatedSemesters: Int = 0,
    val createdCourses: Int = 0, val updatedCourses: Int = 0,
    val createdAssessments: Int = 0, val updatedAssessments: Int = 0)
data class BoardFieldChange(val field: String, val before: String?, val after: String?)
data class BoardImportChange(val recordType: String, val title: String, val action: String, val fields: List<BoardFieldChange>)

/** Review values are immutable; apply recomputes the merged state under the store lock. */
data class BoardImportPreview internal constructor(val request: BoardImportRequest, val baselineRevision: Long,
    val alreadyHandled: Boolean, val receipt: BoardImportReceipt?, val semesterID: String,
    val counts: BoardImportCounts, val changes: List<BoardImportChange>, val notes: List<String>,
    internal val payload: String, internal val digest: String,
    internal val beforeSemester: AcademicSemester?, internal val afterSemester: AcademicSemester?,
    internal val beforeCourses: List<AcademicCourse>, internal val afterCourses: List<AcademicCourse>,
    internal val beforeAssessments: List<AcademicAssessment>, internal val afterAssessments: List<AcademicAssessment>)

internal fun boardPayload(request: BoardImportRequest): String {
    if (!request.rawFile) {
        requireWorkspace(request.selectedCourseIDs == null && request.timeZoneIdentifier == null,
            "Assistant proposals must be reviewed in full with their supplied source timezone.")
        return SemesterBoardCodec.encodeProposal(request.proposal)
    }
    return sortedBoardJson(JsonObject(mapOf("format" to JsonPrimitive("habits.local-board-import"),
        "version" to JsonPrimitive(1), "proposalID" to JsonPrimitive(canonical(request.proposal.proposalID)),
        "source" to JsonPrimitive(request.proposal.source),
        "semester" to rawDocumentJson(request.proposal.semester),
        "selectedCourseIDs" to (request.selectedCourseIDs?.map(::canonical)?.sorted()?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull),
        "timeZoneIdentifier" to (request.timeZoneIdentifier?.let(::JsonPrimitive) ?: JsonNull)))).toString()
}
internal fun boardPayloadDigest(payload: String, rawFile: Boolean): String = boardSha256(
    if (rawFile) sortedBoardJson(Json.parseToJsonElement(payload)).toString() else SemesterBoardCodec.canonicalPayload(payload))

/** Field-presence sets have no ordering; source course/status/assessment arrays do. */
private fun rawDocumentJson(board: BoardSemester): JsonElement {
    val value = board.copy(id = canonical(board.id), providedFields = board.providedFields.toSortedSet(),
        statuses = board.statuses.map { it.copy(id = canonical(it.id), providedFields = it.providedFields.toSortedSet()) },
        courses = board.courses.map { course -> course.copy(id = canonical(course.id), providedFields = course.providedFields.toSortedSet(),
            entries = course.entries.mapValues { (_, entry) -> entry.copy(statusID = entry.statusID?.let(::canonical), providedFields = entry.providedFields.toSortedSet()) },
            assessments = course.assessments.map { it.copy(id = canonical(it.id), providedFields = it.providedFields.toSortedSet()) }) })
    return WorkspaceRepository.workspaceJson.parseToJsonElement(WorkspaceRepository.workspaceJson.encodeToString(value))
}

internal fun makeBoardPreview(request: BoardImportRequest, snapshot: WorkspaceSnapshot): BoardImportPreview {
    val payload = boardPayload(request)
    // Encoding/decoding closes assistant fields and validates source values without normalizing raw civil clocks.
    val proposal = if (request.rawFile) request.proposal else SemesterBoardCodec.decodeProposal(payload)
    if (request.rawFile) SemesterBoardCodec.validate(proposal.semester)
    val id = canonical(proposal.proposalID)
    val semesterID = canonical(proposal.semester.id)
    val digest = boardPayloadDigest(payload, request.rawFile)
    requireWorkspace(snapshot.receipts.none { it.proposalID == id }, "This proposal ID belongs to an older academic import.")
    val previous = snapshot.boardReceipts.find { it.proposalID == id }
    if (previous != null) {
        requireWorkspace(previous.digest == digest && previous.rawFile == request.rawFile,
            "This proposal ID was used for different content. Request a new proposal ID.")
        return BoardImportPreview(request, snapshot.revision, true, previous, semesterID, BoardImportCounts(), emptyList(),
            listOf("This import was already handled. Your later edits will be kept."), payload, digest,
            null, null, emptyList(), emptyList(), emptyList(), emptyList())
    }
    val document = proposal.semester
    val oldSemester = snapshot.semesters.find { it.id == semesterID }
    requireWorkspace(oldSemester == null || !oldSemester.archived, "This semester is archived. Restore it explicitly before importing changes.")
    if (!request.rawFile) {
        if (oldSemester == null) requireWorkspace(proposal.expectedBoardDigest == null, "A new semester must not carry an existing Board digest.")
        else requireWorkspace(proposal.expectedBoardDigest != null &&
            proposal.expectedBoardDigest == SemesterBoardExport.digest(snapshot, semesterID),
            "The Semester Board changed since the shared context. Share it again and review a new proposal.")
    }
    val zone = request.timeZoneIdentifier ?: document.timeZoneIdentifier ?: oldSemester?.timeZoneIdentifier
        ?: throw WorkspaceException("Choose the source semester timezone before importing this file.")
    ZoneId.of(zone)
    val semester = AcademicSemester(semesterID, document.title,
        if ("startDate" in document.providedFields) document.startDate else oldSemester?.startDate,
        if ("weekCount" in document.providedFields) document.weekCount else oldSemester?.weekCount ?: document.weekCount,
        if (request.timeZoneIdentifier != null || "timeZoneIdentifier" in document.providedFields) zone else oldSemester?.timeZoneIdentifier ?: zone,
        endDate = if ("endDate" in document.providedFields) document.endDate else oldSemester?.endDate,
        showExamWeeks = if ("showExamWeeks" in document.providedFields) document.showExamWeeks else oldSemester?.showExamWeeks ?: false,
        statuses = mergeStatuses(oldSemester?.statuses.orEmpty(), document.statuses),
        compatibilityFields = mergeFields(oldSemester?.compatibilityFields, document.unknownFields, request.rawFile))
    requireWorkspace(oldSemester != null || semesterID !in allRecordIDs(snapshot), "The semester ID belongs to another record.")
    val selection = request.selectedCourseIDs?.map(::canonical)?.toSet()
    requireWorkspace(selection == null || selection.all { wanted -> document.courses.any { canonical(it.id) == wanted } },
        "The selected course is absent from this file.")
    val beforeCourses = mutableListOf<AcademicCourse>()
    val afterCourses = mutableListOf<AcademicCourse>()
    val beforeAssessments = mutableListOf<AcademicAssessment>()
    val afterAssessments = mutableListOf<AcademicAssessment>()
    document.courses.forEachIndexed { order, source ->
        val external = canonical(source.id)
        if (selection != null && external !in selection) return@forEachIndexed
        val old = snapshot.courses.singleOrNull { it.semesterID == semesterID && (it.externalID?.let(::canonical) ?: it.id) == external }
        requireWorkspace(old == null || !old.archived, "An included course is archived. Restore it explicitly before importing changes.")
        val localID = old?.id ?: scopedID(AcademicRecordIds.course(semesterID, external))
        requireWorkspace(old != null || localID !in allRecordIDs(snapshot), "A derived course ID belongs to another record.")
        val entries = old?.entries.orEmpty().toMutableMap()
        source.entries.forEach { (week, entry) ->
            val existing = entries[week]
            entries[week] = AcademicWeekEntry(if ("statusID" in entry.providedFields) entry.statusID?.let(::canonical) else existing?.statusID,
                if ("notes" in entry.providedFields) entry.notes else existing?.notes ?: "",
                mergeFields(existing?.compatibilityFields, entry.unknownFields, request.rawFile))
        }
        val course = AcademicCourse(localID, semesterID, source.name,
            moduleCode = if ("moduleCode" in source.providedFields) source.moduleCode else old?.moduleCode,
            sourceURL = if ("sourceURL" in source.providedFields) source.sourceURL else old?.sourceURL,
            scheduleNote = if ("scheduleNote" in source.providedFields) source.scheduleNote else old?.scheduleNote,
            activeWeeks = if ("activeWeeks" in source.providedFields) source.activeWeeks else old?.activeWeeks,
            entries = entries, externalID = old?.externalID ?: external, boardOrder = order,
            compatibilityFields = mergeFields(old?.compatibilityFields, source.unknownFields, request.rawFile))
        if (course != old) { old?.let(beforeCourses::add); afterCourses += course }
        source.assessments.forEachIndexed { assessmentOrder, row ->
            val assessmentExternal = canonical(row.id)
            val existing = snapshot.assessments.singleOrNull { it.courseID == localID &&
                (it.externalID?.let(::canonical) ?: it.id) == assessmentExternal }
            requireWorkspace(existing == null || !existing.archived, "An included assessment is archived. Restore it explicitly before importing changes.")
            val assessmentID = existing?.id ?: scopedID(AcademicRecordIds.assessment(semesterID, external, assessmentExternal))
            requireWorkspace(existing != null || assessmentID !in allRecordIDs(snapshot), "A derived assessment ID belongs to another record.")
            val state = if ("state" in row.providedFields) row.state else existing?.recordedState ?: "pending"
            if (!request.rawFile && "completedAt" in row.providedFields && row.completedAt != null)
                requireWorkspace(state == "completed", "A supplied completion timestamp needs a completed assessment state. No changes were made.")
            val completedAt = if (state != "completed") null else if ("completedAt" in row.providedFields)
                row.completedAt?.let(SemesterBoardCodec::referenceDateToInstant) else existing?.completedAt
            val metadata = row.unknownFields.toMutableMap()
            if (request.rawFile) {
                if ("reminderDays" in row.providedFields) metadata["reminderDays"] = JsonArray(row.reminderDays.map(::JsonPrimitive))
                if ("snoozedUntil" in row.providedFields) metadata["snoozedUntil"] = row.snoozedUntil?.let(::JsonPrimitive) ?: JsonNull
                // Legacy files can retain a stale completion stamp alongside a pending/dismissed state.
                // Preserve that source fact locally without treating it as a completion or sharing it.
                if ("completedAt" in row.providedFields && state != "completed" && row.completedAt != null)
                    metadata["completedAt"] = JsonPrimitive(row.completedAt)
                else if ("completedAt" in row.providedFields && existing?.compatibilityFields?.containsKey("completedAt") == true)
                    metadata["completedAt"] = JsonNull
            }
            val assessment = AcademicAssessment(assessmentID, semesterID, localID, row.title,
                if ("kind" in row.providedFields) row.kind else existing?.kind ?: row.kind, row.dueDate,
                if ("dueTime" in row.providedFields) row.dueTime else existing?.dueTime,
                if ("notes" in row.providedFields) row.notes else existing?.notes ?: "",
                completed = state == "completed", completedAt = completedAt, dismissed = state == "dismissed",
                externalID = existing?.externalID ?: assessmentExternal, boardOrder = assessmentOrder,
                recurrence = reviewedRecurrence(request, row, existing),
                compatibilityFields = mergeFields(existing?.compatibilityFields, JsonObject(metadata), request.rawFile))
            if (assessment != existing) { existing?.let(beforeAssessments::add); afterAssessments += assessment }
        }
    }
    val merged = snapshot.copy(semesters = replaceRecords(snapshot.semesters, listOf(semester)) { it.id },
        courses = replaceRecords(snapshot.courses, afterCourses) { it.id },
        assessments = replaceRecords(snapshot.assessments, afterAssessments) { it.id })
    if (!request.rawFile) validateAssistantClocks(snapshot, merged, semester)
    validateWorkspace(merged)
    val semesterChanged = oldSemester != semester
    val changes = mutableListOf<BoardImportChange>()
    if (semesterChanged) changes += boardChange("semester", semester.title, oldSemester, semester)
    afterCourses.forEach { changes += boardChange("course", it.name, beforeCourses.find { old -> old.id == it.id }, it) }
    afterAssessments.forEach { changes += boardChange("assessment", it.title, beforeAssessments.find { old -> old.id == it.id }, it) }
    val notes = mutableListOf("Omitted records and fields keep their saved values. Notification preferences stay unchanged; device reminder settings are not imported. No synchronization is enabled.")
    if (request.rawFile && (document.unknownFields.isNotEmpty() || document.courses.any { it.unknownFields.isNotEmpty() || it.assessments.any { row -> row.unknownFields.isNotEmpty() || row.reminderDays.isNotEmpty() || row.snoozedUntil != null } }))
        notes += "Compatibility and device reminder metadata will be retained locally; imported reminders are not scheduled."
    if (request.rawFile && document.courses.any { course -> course.assessments.any { it.completedAt != null && it.state != "completed" } })
        notes += "A legacy completion timestamp conflicts with a pending/dismissed source state. The timestamp stays in local compatibility data; the recorded state is kept and shared without that timestamp."
    if (!request.rawFile && afterAssessments.any { current -> beforeAssessments.any { old -> old.id == current.id && old.recurrence != null && old.dueDate != current.dueDate } })
        notes += "Moved recurring assessments remain separate materialized exceptions. Their original series and scheduled date stay recorded; sibling occurrences are unchanged."
    if (semester.startDate != null && semester.endDate == null) notes += "This legacy semester records a start and teaching week count. Its displayed/exported end is calculated from those values, not a separately recorded university date."
    if (semester.startDate == null) notes += "This semester has no date range. Source weeks are available without current-week dates."
    return BoardImportPreview(request, snapshot.revision, false, null, semesterID,
        BoardImportCounts(if (oldSemester == null) 1 else 0, if (oldSemester != null && semesterChanged) 1 else 0,
            afterCourses.size - beforeCourses.size, beforeCourses.size, afterAssessments.size - beforeAssessments.size, beforeAssessments.size),
        changes, notes, payload, digest, oldSemester.takeIf { semesterChanged }, semester.takeIf { semesterChanged },
        beforeCourses, afterCourses, beforeAssessments, afterAssessments)
}

private fun mergeStatuses(old: List<AcademicStatus>, incoming: List<BoardStatus>): List<AcademicStatus> {
    val values = incoming.map { row ->
        val id = canonical(row.id)
        val existing = old.find { it.id == id }
        AcademicStatus(id, row.name, if ("colorHex" in row.providedFields) row.colorHex else existing?.colorHex ?: row.colorHex,
            JsonObject(existing?.compatibilityFields.orEmpty() + row.unknownFields))
    }
    val importedIDs = values.map { it.id }.toSet()
    return values + old.filter { it.id !in importedIDs }
}

/** Changing one materialized date records an exception; it never regenerates a recurrence series. */
private fun reviewedRecurrence(request: BoardImportRequest, row: BoardAssessment, existing: AcademicAssessment?): JsonElement? {
    if (request.rawFile && "recurrence" in row.providedFields) return row.recurrence
    val recurrence = existing?.recurrence ?: return null
    return if (!request.rawFile && row.dueDate != existing.dueDate)
        JsonObject(recurrence.jsonObject + ("isException" to JsonPrimitive(true))) else recurrence
}

/** Unchanged raw clocks remain source facts. Assistant-created or affected timing must resolve exactly once. */
private fun validateAssistantClocks(before: WorkspaceSnapshot, merged: WorkspaceSnapshot, semester: AcademicSemester) {
    val previousSemester = before.semesters.find { it.id == semester.id }
    val zoneChanged = previousSemester != null && previousSemester.timeZoneIdentifier != semester.timeZoneIdentifier
    val zone = ZoneId.of(semester.timeZoneIdentifier)
    merged.assessments.filter { it.semesterID == semester.id && !it.archived && it.dueTime != null }.forEach { item ->
        val old = before.assessments.find { it.id == item.id }
        if (old == null || old.dueDate != item.dueDate || old.dueTime != item.dueTime || zoneChanged) {
            val clock = LocalDateTime.of(LocalDate.parse(item.dueDate), LocalTime.parse(item.dueTime))
            requireWorkspace(zone.rules.getValidOffsets(clock).size == 1,
                "A new or changed assessment clock is skipped or repeated in the effective semester timezone. Review its date, time and source timezone.")
        }
    }
}
private fun mergeFields(old: JsonObject?, incoming: JsonObject, raw: Boolean): JsonObject = JsonObject(old.orEmpty() + if (raw) incoming else emptyMap())
internal fun scopedID(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
internal fun allRecordIDs(snapshot: WorkspaceSnapshot): Set<String> = (snapshot.semesters.map { it.id } + snapshot.courses.map { it.id } +
    snapshot.assessments.map { it.id } + snapshot.tasks.map { it.id } + snapshot.appointments.map { it.id } + snapshot.habits.map { it.id }).toSet()
internal fun <T> replaceRecords(old: List<T>, values: List<T>, identity: (T) -> String): List<T> {
    val replacements = values.associateBy(identity)
    val identities = old.map(identity).toSet()
    return old.map { replacements[identity(it)] ?: it } + values.filter { identity(it) !in identities }
}
private inline fun <reified T> boardChange(kind: String, title: String, before: T?, after: T): BoardImportChange {
    val old = before?.let { WorkspaceRepository.workspaceJson.parseToJsonElement(WorkspaceRepository.workspaceJson.encodeToString(it)).jsonObject }
    val current = WorkspaceRepository.workspaceJson.parseToJsonElement(WorkspaceRepository.workspaceJson.encodeToString(after)).jsonObject
    val fields = current.keys.filter { it !in setOf("id", "semesterID", "courseID", "externalID") && old?.get(it) != current[it] }
        .map { BoardFieldChange(it, old?.get(it)?.toString(), current[it]?.toString()) }
    return BoardImportChange(kind, title, if (before == null) "create" else "update", fields)
}

internal fun validateBoardReceipt(receipt: BoardImportReceipt, snapshot: WorkspaceSnapshot) {
    requireWorkspace(receipt.proposalID == canonical(receipt.proposalID) &&
        receipt.digest == boardPayloadDigest(receipt.payload, receipt.rawFile), "The Semester Board receipt fingerprint is invalid.")
    val payload = Json.parseToJsonElement(receipt.payload).jsonObject
    if (receipt.rawFile) {
        requireWorkspace(payload["format"] == JsonPrimitive("habits.local-board-import") && payload["version"] == JsonPrimitive(1),
            "The local board receipt origin is invalid.")
        requireWorkspace(payload["proposalID"]?.jsonPrimitive?.content == receipt.proposalID &&
            payload["source"]?.jsonPrimitive?.content == receipt.source &&
            canonical(payload.getValue("semester").jsonObject.getValue("id").jsonPrimitive.content) == receipt.semesterID,
            "The local board receipt identity is invalid.")
    } else {
        val proposal = SemesterBoardCodec.decodeProposal(receipt.payload)
        requireWorkspace(canonical(proposal.proposalID) == receipt.proposalID && proposal.source == receipt.source &&
            canonical(proposal.semester.id) == receipt.semesterID, "The assistant board receipt identity is invalid.")
    }
    requireWorkspace(snapshot.semesters.any { it.id == receipt.semesterID }, "The Semester Board receipt source is missing.")
    requireWorkspace(receipt.beforeSemester == null || (receipt.afterSemester != null && receipt.beforeSemester.id == receipt.afterSemester.id),
        "The Semester Board receipt semester changes are inconsistent.")
    listOfNotNull(receipt.beforeSemester, receipt.afterSemester).forEach {
        requireWorkspace(it.id == receipt.semesterID, "The Semester Board receipt semester scope is invalid.")
    }
    requireWorkspace(receipt.beforeCourses.map { it.id }.distinct().size == receipt.beforeCourses.size &&
        receipt.afterCourses.map { it.id }.distinct().size == receipt.afterCourses.size &&
        receipt.beforeCourses.all { before -> receipt.afterCourses.any { it.id == before.id } }, "The board course changes are invalid.")
    (receipt.beforeCourses + receipt.afterCourses).forEach {
        requireWorkspace(it.semesterID == receipt.semesterID && snapshot.courses.any { current -> current.id == it.id && current.semesterID == it.semesterID },
            "The board course receipt source is missing.")
    }
    requireWorkspace(receipt.beforeAssessments.map { it.id }.distinct().size == receipt.beforeAssessments.size &&
        receipt.afterAssessments.map { it.id }.distinct().size == receipt.afterAssessments.size &&
        receipt.beforeAssessments.all { before -> receipt.afterAssessments.any { it.id == before.id } }, "The board assessment changes are invalid.")
    (receipt.beforeAssessments + receipt.afterAssessments).forEach {
        requireWorkspace(it.semesterID == receipt.semesterID && snapshot.assessments.any { current -> current.id == it.id && current.courseID == it.courseID && current.semesterID == it.semesterID },
            "The board assessment receipt source is missing.")
    }
    Instant.parse(receipt.appliedAt); receipt.undoneAt?.let(Instant::parse)
    requireWorkspace((receipt.state == ReceiptState.UNDONE) == (receipt.undoneAt != null), "The board receipt state is inconsistent.")
}
