package ch.habits.android.data

import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.core.AcademicPlanValidator
import ch.habits.android.core.AcademicRecordIds
import ch.habits.android.semester.SemesterBoardExport
import ch.habits.android.semester.SemesterBoardCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One atomic local store contains source records and the import ledger. No network calls. */
class WorkspaceRepository(private val file: File) {
    val backupFile: File get() = File(file.absolutePath + ".backup")
    /** Original schema 1 bytes survive subsequent rolling backups after the first deliberate edit. */
    val schemaV1BackupFile: File get() = File(file.absolutePath + ".schema-v1-backup")
    private val mutex = fileLocks.computeIfAbsent(file.canonicalPath) { Mutex() }
    private var durableBytes: ByteArray? = null
    private val mutableState = MutableStateFlow(loadInitial())
    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()

    /** Refresh device-owned projections after receiver/widget writes or process restart. */
    suspend fun refresh(): WorkspaceState = locked { state.value }

    suspend fun preview(plan: AcademicPlan): PlanPreview = locked {
        requireWritable()
        makePreview(plan, state.value.snapshot)
    }

    suspend fun previewBoard(request: BoardImportRequest): BoardImportPreview = locked {
        requireWritable()
        makeBoardPreview(request, state.value.snapshot)
    }

    suspend fun applyBoard(preview: BoardImportPreview): BoardImportReceipt = locked {
        requireWritable()
        val snapshot = state.value.snapshot
        val current = makeBoardPreview(preview.request, snapshot)
        if (current.alreadyHandled) return@locked current.receipt!!
        requireWorkspace(snapshot.revision == preview.baselineRevision,
            "The workspace changed after preview. Review the Semester Board again.")
        requireWorkspace(current == preview, "The reviewed Semester Board changed. Review it again.")
        val receipt = BoardImportReceipt(canonical(preview.request.proposal.proposalID), preview.digest,
            preview.request.proposal.source, preview.payload, preview.semesterID,
            preview.beforeSemester, preview.afterSemester, preview.beforeCourses, preview.afterCourses,
            preview.beforeAssessments, preview.afterAssessments, appliedAt = Instant.now().toString(), rawFile = preview.request.rawFile)
        commit(snapshot.copy(revision = nextRevision(snapshot),
            semesters = replaceRecords(snapshot.semesters, listOfNotNull(receipt.afterSemester)) { it.id },
            courses = replaceRecords(snapshot.courses, receipt.afterCourses) { it.id },
            assessments = replaceRecords(snapshot.assessments, receipt.afterAssessments) { it.id },
            boardReceipts = snapshot.boardReceipts + receipt))
        receipt
    }

    suspend fun undoBoard(proposalID: String): BoardImportReceipt = locked {
        requireWritable()
        val snapshot = state.value.snapshot
        val id = canonical(proposalID)
        val receipt = snapshot.boardReceipts.find { it.proposalID == id }
            ?: throw WorkspaceException("The saved Semester Board receipt is unavailable.")
        if (receipt.state == ReceiptState.UNDONE) return@locked receipt
        receipt.afterSemester?.let { requireWorkspace(snapshot.semesters.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.afterCourses.forEach { requireWorkspace(snapshot.courses.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.afterAssessments.forEach { requireWorkspace(snapshot.assessments.find { item -> item.id == it.id } == it, changedUndo) }
        val createdSemesterIDs = if (receipt.beforeSemester == null) listOfNotNull(receipt.afterSemester?.id).toSet() else emptySet()
        val createdCourseIDs = receipt.afterCourses.map { it.id }.toSet() - receipt.beforeCourses.map { it.id }.toSet()
        val createdAssessmentIDs = receipt.afterAssessments.map { it.id }.toSet() - receipt.beforeAssessments.map { it.id }.toSet()
        requireWorkspace(snapshot.courses.none { !it.archived && it.semesterID in createdSemesterIDs && it.id !in createdCourseIDs }, dependentUndo)
        requireWorkspace(snapshot.assessments.none { !it.archived && (it.semesterID in createdSemesterIDs || it.courseID in createdCourseIDs) && it.id !in createdAssessmentIDs }, dependentUndo)
        requireWorkspace(snapshot.tasks.none { !it.archived && (it.sourceCourseID in createdCourseIDs || it.sourceAssessmentID in createdAssessmentIDs) }, dependentUndo)
        val semesterValues = listOfNotNull(receipt.beforeSemester ?: receipt.afterSemester?.copy(archived = true))
        val courseValues = receipt.afterCourses.map { after -> receipt.beforeCourses.find { it.id == after.id } ?: after.copy(archived = true) }
        val assessmentValues = receipt.afterAssessments.map { after -> receipt.beforeAssessments.find { it.id == after.id } ?: after.copy(archived = true) }
        val restored = snapshot.copy(semesters = replaceRecords(snapshot.semesters, semesterValues) { it.id },
            courses = replaceRecords(snapshot.courses, courseValues) { it.id },
            assessments = replaceRecords(snapshot.assessments, assessmentValues) { it.id })
        // Later progress may reference a newly added status even when the parent import is otherwise untouched.
        validateWorkspace(restored)
        val undone = receipt.copy(state = ReceiptState.UNDONE, undoneAt = Instant.now().toString())
        commit(restored.copy(revision = nextRevision(snapshot),
            boardReceipts = snapshot.boardReceipts.map { if (it.proposalID == id) undone else it }))
        undone
    }

    suspend fun setWeekStatus(semesterID: String, courseID: String, week: Int, statusID: String?,
        expectedEntry: AcademicWeekEntry?, expectedStatuses: List<AcademicStatus>) = mutate { snapshot ->
        val semester = snapshot.semesters.find { it.id == canonical(semesterID) && !it.archived }
            ?: throw WorkspaceException("This semester is unavailable.")
        val course = snapshot.courses.find { it.id == canonical(courseID) && it.semesterID == semester.id && !it.archived }
            ?: throw WorkspaceException("This course is unavailable.")
        requireWorkspace(week in 1..52, "The source week is invalid.")
        requireWorkspace(course.entries[week] == expectedEntry && semester.statuses == expectedStatuses,
            "This week or its status choices changed. Reload before changing its status.")
        val selected = statusID?.let(::canonical)
        val fallback = ch.habits.android.semester.SemesterBoardProjection.statuses(semester)
        requireWorkspace(selected == null || fallback.any { it.id == selected }, "This status is unavailable.")
        // Choosing a fallback makes the choices durable without seeding any other week progress.
        val statuses = if (selected != null && semester.statuses.none { it.id == selected })
            fallback else semester.statuses
        if (expectedEntry == null && selected == null) return@mutate snapshot
        val entry = (expectedEntry ?: AcademicWeekEntry()).copy(statusID = selected)
        if (entry == expectedEntry && statuses == semester.statuses) snapshot else snapshot.copy(
            semesters = replaceRecords(snapshot.semesters, listOf(semester.copy(statuses = statuses))) { it.id },
            courses = replaceRecords(snapshot.courses, listOf(course.copy(entries = course.entries + (week to entry)))) { it.id })
    }

    suspend fun setAssessmentState(expected: AcademicAssessment, state: String) = mutate { snapshot ->
        requireWorkspace(state in setOf("pending", "completed", "dismissed"), "Choose a valid assessment state.")
        val current = snapshot.assessments.find { it.id == expected.id && !it.archived }
            ?: throw WorkspaceException("This assessment is unavailable.")
        requireWorkspace(current == expected, "This assessment changed. Reload before changing its state.")
        if (current.recordedState == state) snapshot else snapshot.copy(assessments = replaceRecords(snapshot.assessments,
            listOf(current.copy(completed = state == "completed", dismissed = state == "dismissed",
                completedAt = if (state == "completed") Instant.now().toString() else null))) { it.id })
    }

    suspend fun apply(preview: PlanPreview): PlanReceipt = locked {
        requireWritable()
        val snapshot = state.value.snapshot
        val plan = canonicalPlan(preview.plan)
        val digest = planDigest(plan)
        val previous = snapshot.receipts.find { it.proposalID == plan.proposalID }
        if (previous != null) {
            requireWorkspace(previous.digest == digest, "This proposal ID was used for different content. Request a new proposal ID.")
            return@locked previous
        }
        requireWorkspace(snapshot.revision == preview.baselineRevision, "The workspace changed after preview. Review the proposal again.")
        requireWorkspace(digest == preview.digest, "The proposal changed after preview.")
        val reviewed = makePreview(plan, snapshot)
        requireWorkspace(reviewed.semesters == preview.semesters && reviewed.courses == preview.courses &&
            reviewed.assessments == preview.assessments && sameTaskContent(reviewed.tasks, preview.tasks), "The proposal changed after preview.")
        val receipt = PlanReceipt(plan.proposalID, digest, plan.source, plan,
            preview.semesters, preview.courses, preview.assessments, preview.tasks,
            appliedAt = Instant.now().toString())
        commit(snapshot.copy(revision = nextRevision(snapshot),
            semesters = snapshot.semesters + receipt.createdSemesters,
            courses = snapshot.courses + receipt.createdCourses,
            assessments = snapshot.assessments + receipt.createdAssessments,
            tasks = snapshot.tasks + receipt.createdTasks,
            receipts = snapshot.receipts + receipt))
        receipt
    }

    suspend fun undo(proposalID: String): PlanReceipt = locked {
        requireWritable()
        val snapshot = state.value.snapshot
        val id = canonical(proposalID)
        val receipt = snapshot.receipts.find { it.proposalID == id }
            ?: throw WorkspaceException("The saved import receipt is unavailable.")
        if (receipt.state == ReceiptState.UNDONE) return@locked receipt
        receipt.createdSemesters.forEach { expected -> requireWorkspace(snapshot.semesters.find { item -> item.id == expected.id }?.let { sameV1Semester(it, expected) && !hasBoardSemesterMetadata(it) } == true, changedUndo) }
        receipt.createdCourses.forEach { expected -> requireWorkspace(snapshot.courses.find { item -> item.id == expected.id }?.let { sameV1Course(it, expected) && !hasBoardCourseMetadata(it) } == true, changedUndo) }
        receipt.createdAssessments.forEach { requireWorkspace(snapshot.assessments.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.createdTasks.forEach { requireWorkspace(snapshot.tasks.find { item -> item.id == it.id } == it, changedUndo) }
        val semesterIDs = receipt.createdSemesters.map { it.id }.toSet()
        val courseIDs = receipt.createdCourses.map { it.id }.toSet()
        val assessmentIDs = receipt.createdAssessments.map { it.id }.toSet()
        val taskIDs = receipt.createdTasks.map { it.id }.toSet()
        requireWorkspace(snapshot.courses.none { !it.archived && it.semesterID in semesterIDs && it.id !in courseIDs }, dependentUndo)
        requireWorkspace(snapshot.assessments.none { !it.archived && (it.semesterID in semesterIDs || it.courseID in courseIDs) && it.id !in assessmentIDs }, dependentUndo)
        requireWorkspace(snapshot.tasks.none { !it.archived && (it.sourceAssessmentID in assessmentIDs || it.sourceCourseID in courseIDs) && it.id !in taskIDs }, dependentUndo)
        val undone = receipt.copy(state = ReceiptState.UNDONE, undoneAt = Instant.now().toString())
        commit(snapshot.copy(revision = nextRevision(snapshot),
            semesters = snapshot.semesters.map { if (it.id in semesterIDs) it.copy(archived = true) else it },
            courses = snapshot.courses.map { if (it.id in courseIDs) it.copy(archived = true) else it },
            assessments = snapshot.assessments.map { if (it.id in assessmentIDs) it.copy(archived = true) else it },
            tasks = snapshot.tasks.map { if (it.id in taskIDs) it.copy(archived = true) else it },
            receipts = snapshot.receipts.map { if (it.proposalID == id) undone else it }))
        undone
    }

    suspend fun saveTask(task: WorkTask) = mutate { snapshot ->
        val value = task.copy(id = canonical(task.id), sourceAssessmentID = task.sourceAssessmentID?.let(::canonical),
            sourceCourseID = task.sourceCourseID?.let(::canonical),
            sourceZone = task.sourceZone ?: if (task.dueTime != null) ZoneId.systemDefault().id else null)
        validateTask(value, snapshot)
        val old = snapshot.tasks.find { it.id == value.id }
        requireWorkspace(old != null || value.id !in entityIDs(snapshot), "This ID belongs to another item.")
        requireWorkspace(old == null || old.updatedAt == value.updatedAt, "This task changed while editing. Reload it before saving.")
        if (old != null && value.copy(createdAt = old.createdAt, updatedAt = old.updatedAt) == old) return@mutate snapshot
        val saved = if (old == null) value else value.copy(createdAt = old.createdAt, updatedAt = Instant.now().toString())
        snapshot.copy(tasks = replacing(snapshot.tasks, saved) { it.id })
    }

    suspend fun completeTask(id: String, completed: Boolean = true) = mutate { snapshot ->
        val task = snapshot.tasks.find { it.id == canonical(id) && !it.archived } ?: throw WorkspaceException("This task is unavailable.")
        if (task.completed == completed) snapshot else snapshot.copy(tasks = replacing(snapshot.tasks,
            task.copy(completed = completed, completedAt = if (completed) Instant.now().toString() else null,
                updatedAt = Instant.now().toString())) { it.id })
    }

    suspend fun archiveTask(id: String) = mutate { snapshot ->
        val task = snapshot.tasks.find { it.id == canonical(id) } ?: throw WorkspaceException("This task is unavailable.")
        if (task.archived) snapshot else snapshot.copy(tasks = replacing(snapshot.tasks,
            task.copy(archived = true, updatedAt = Instant.now().toString())) { it.id })
    }

    suspend fun completeAssessment(id: String, completed: Boolean = true) = mutate { snapshot ->
        val assessment = snapshot.assessments.find { it.id == canonical(id) && !it.archived }
            ?: throw WorkspaceException("This assessment is unavailable.")
        if (assessment.completed == completed && !assessment.dismissed) snapshot else snapshot.copy(assessments = replacing(snapshot.assessments,
            assessment.copy(completed = completed, dismissed = false, completedAt = if (completed) Instant.now().toString() else null)) { it.id })
    }

    suspend fun saveHabit(habit: Habit) = mutate { snapshot ->
        val value = habit.copy(id = canonical(habit.id), weekdays = habit.weekdays.distinct().sorted())
        validateHabit(value)
        requireWorkspace(snapshot.habits.any { it.id == value.id } || value.id !in entityIDs(snapshot), "This ID belongs to another item.")
        snapshot.copy(habits = replacing(snapshot.habits, value) { it.id })
    }

    suspend fun archiveHabit(id: String) = mutate { snapshot ->
        val habit = snapshot.habits.find { it.id == canonical(id) } ?: throw WorkspaceException("This habit is unavailable.")
        snapshot.copy(habits = replacing(snapshot.habits, habit.copy(archived = true)) { it.id })
    }

    suspend fun logHabit(id: String, date: String, completed: Boolean = true,
        expectedRevision: Long? = null, expectedCompleted: Boolean? = null, scheduledTodayOnly: Boolean = false) = mutate { snapshot ->
        val habitID = canonical(id)
        val habit = snapshot.habits.firstOrNull { it.id == habitID && !it.archived }
            ?: throw WorkspaceException("This habit is unavailable.")
        validDate(date)
        if (expectedRevision != null) requireWorkspace(snapshot.revision == expectedRevision, "The widget is stale; refresh before changing this habit.")
        if (scheduledTodayOnly) {
            val today = LocalDate.now()
            requireWorkspace(date == today.toString() && today.dayOfWeek.value in habit.weekdays,
                "This widget no longer refers to a habit scheduled today.")
        }
        if (expectedCompleted != null) {
            val actual = snapshot.habitLogs.any { it.habitID == habitID && it.date == date && it.completed }
            requireWorkspace(actual == expectedCompleted && completed != expectedCompleted, "This habit log changed; refresh the widget.")
        }
        val log = HabitLog(habitID, date, completed)
        if (snapshot.habitLogs.find { it.habitID == habitID && it.date == date } == log) snapshot
        else snapshot.copy(habitLogs = snapshot.habitLogs.filterNot { it.habitID == habitID && it.date == date } + log)
    }

    suspend fun saveAppointment(appointment: Appointment) = mutate { snapshot ->
        val value = appointment.copy(id = canonical(appointment.id))
        validateAppointment(value)
        requireWorkspace(snapshot.appointments.any { it.id == value.id } || value.id !in entityIDs(snapshot), "This ID belongs to another item.")
        snapshot.copy(appointments = replacing(snapshot.appointments, value) { it.id })
    }

    suspend fun archiveAppointment(id: String) = mutate { snapshot ->
        val appointment = snapshot.appointments.find { it.id == canonical(id) } ?: throw WorkspaceException("This appointment is unavailable.")
        snapshot.copy(appointments = replacing(snapshot.appointments, appointment.copy(archived = true)) { it.id })
    }

    suspend fun savePreferences(preferences: Preferences) = mutate { snapshot ->
        validatePreferences(preferences)
        snapshot.copy(preferences = preferences)
    }

    /** A deliberate recovery preserves the original primary and keeps its valid backup. */
    suspend fun restoreBackup() = locked {
        val bytes = try { backupFile.readBytes() } catch (_: Exception) { throw WorkspaceException("No readable backup is available.") }
        val backup = try { decode(bytes) } catch (_: Exception) { throw WorkspaceException("No valid backup is available.") }
        try { preserveSchemaV1Bytes(bytes) } catch (failure: Exception) {
            throw WorkspaceException("The legacy backup could not be preserved. Original files were kept; no recovery was applied.").also { it.initCause(failure) }
        }
        if (file.exists()) {
            val preserved = File(file.absolutePath + ".recovery-${UUID.randomUUID()}")
            atomicWrite(preserved, file.readBytes())
        }
        commit(backup.copy(revision = nextRevision(backup)), preserveBackup = true)
    }

    /** Recovery export returns the preserved primary bytes rather than inventing an empty store. */
    suspend fun exportRawData(): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (file.exists()) file.readBytes()
            else if (state.value.recoveryRequired) {
                if (backupFile.isFile) backupFile.readBytes() else throw WorkspaceException("No source workspace file is available; recovery is required.")
            } else workspaceJson.encodeToString(state.value.snapshot).toByteArray(Charsets.UTF_8)
        }
    }

    private suspend fun mutate(operation: (WorkspaceSnapshot) -> WorkspaceSnapshot) = locked {
        requireWritable()
        val snapshot = state.value.snapshot
        val updated = operation(snapshot)
        if (updated != snapshot) commit(updated.copy(revision = nextRevision(snapshot)))
    }

    private suspend fun <T> locked(operation: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshExternalChanges()
            operation()
        }
    }

    private fun makePreview(input: AcademicPlan, snapshot: WorkspaceSnapshot): PlanPreview {
        val plan = canonicalPlan(input)
        requireWorkspace(snapshot.boardReceipts.none { it.proposalID == plan.proposalID }, "This proposal ID belongs to a Semester Board import.")
        val digest = planDigest(plan)
        val previous = snapshot.receipts.find { it.proposalID == plan.proposalID }
        if (previous != null) {
            requireWorkspace(previous.digest == digest, "This proposal ID was used for different content. Request a new proposal ID.")
            return PlanPreview(plan, snapshot.revision, PlanCounts(0, 0, 0, 0), true, previous, digest,
                emptyList(), emptyList(), emptyList(), emptyList())
        }
        val wantedSemester = AcademicSemester(plan.semester.id, plan.semester.title, plan.semester.startDate,
            plan.semester.weekCount, plan.semester.timeZoneIdentifier)
        val oldSemester = snapshot.semesters.find { it.id == wantedSemester.id }
        requireWorkspace(oldSemester == null || sameV1Semester(oldSemester, wantedSemester), "Existing semester metadata conflicts with the proposal.")
        requireWorkspace(snapshot.semesters.none { it.id != wantedSemester.id && it.copy(id = wantedSemester.id, archived = false) == wantedSemester },
            "A semester with this title, date range and timezone already exists, including retained history.")
        val newSemesters = if (oldSemester == null) listOf(wantedSemester) else emptyList()
        val newCourses = plan.courses.map { AcademicCourse(it.id, wantedSemester.id, it.name) }.filter { wanted ->
            val old = snapshot.courses.find { it.id == wanted.id }
            requireWorkspace(old == null || sameV1Course(old, wanted), "Existing course metadata conflicts with the proposal.")
            requireWorkspace(snapshot.courses.none { it.id != wanted.id && it.semesterID == wanted.semesterID &&
                Normalizer.normalize(it.name, Normalizer.Form.NFC) == Normalizer.normalize(wanted.name, Normalizer.Form.NFC) },
                "A course with this name already exists in the semester, including retained history.")
            old == null
        }
        val courseCount = snapshot.courses.count { it.semesterID == wantedSemester.id && !it.archived } + newCourses.size
        requireWorkspace(courseCount <= 10, "A semester can contain at most ten active courses.")
        val newAssessments = plan.assessments.map {
            AcademicAssessment(it.id, wantedSemester.id, it.courseID, it.title, it.kind, it.dueDate, it.dueTime, it.notes)
        }.filter { wanted ->
            val old = snapshot.assessments.find { it.id == wanted.id }
            requireWorkspace(old == null || (!old.archived && sameV1Assessment(old, wanted)),
                "Existing assessment content conflicts with the proposal.")
            requireWorkspace(snapshot.assessments.none { it.id != wanted.id && assessmentKey(it) == assessmentKey(wanted) },
                "An assessment with this course, title, kind and deadline already exists, including retained history.")
            old == null
        }
        val now = Instant.now().toString()
        val tasks = plan.preparationTasks.map { item ->
            WorkTask(id = item.id, title = item.title, notes = item.notes, dueDate = item.dueDate, dueTime = item.dueTime,
                sourceZone = plan.semester.timeZoneIdentifier, minutes = item.estimatedWorkMinutes,
                sourceAssessmentID = item.assessmentID, createdAt = now, updatedAt = now)
        }
        tasks.forEach { wanted ->
            requireWorkspace(snapshot.tasks.none { it.id == wanted.id || preparationKey(it) == preparationKey(wanted) },
                "A preparation task with this identity or content already exists, including retained history.")
        }
        val additions = newSemesters.map { it.id } + newCourses.map { it.id } + newAssessments.map { it.id } + tasks.map { it.id }
        requireWorkspace(additions.none { it in entityIDs(snapshot) }, "A proposal ID belongs to another existing item.")
        return PlanPreview(plan, snapshot.revision,
            PlanCounts(newSemesters.size, newCourses.size, newAssessments.size, tasks.size), false, null,
            digest, newSemesters, newCourses, newAssessments, tasks)
    }

    private fun loadInitial(): WorkspaceState {
        if (!file.exists()) return WorkspaceState(recoveryRequired = backupFile.exists(),
            error = if (backupFile.exists()) "The primary workspace is missing. Its backup was preserved; choose recovery." else null,
            backupAvailable = validBackup())
        return try {
            val bytes = file.readBytes()
            val snapshot = decode(bytes)
            durableBytes = bytes
            WorkspaceState(snapshot = snapshot, backupAvailable = validBackup())
        } catch (_: Exception) {
            WorkspaceState(recoveryRequired = true, error = "The workspace could not be opened. Original files were preserved; editing is disabled.",
                backupAvailable = validBackup())
        }
    }

    private fun refreshExternalChanges() {
        if (state.value.recoveryRequired) return
        if (!file.exists()) {
            if (durableBytes != null || backupFile.exists()) mutableState.value = state.value.copy(recoveryRequired = true,
                error = "The primary workspace is missing. Original backups were preserved; editing is disabled.", backupAvailable = validBackup())
            return
        }
        try {
            val bytes = file.readBytes()
            if (durableBytes?.contentEquals(bytes) == true) return
            val snapshot = decode(bytes)
            durableBytes = bytes
            mutableState.value = WorkspaceState(snapshot, backupAvailable = validBackup())
        } catch (_: Exception) {
            mutableState.value = state.value.copy(recoveryRequired = true,
                error = "The workspace changed to unreadable data. Original files were preserved; editing is disabled.", backupAvailable = validBackup())
        }
    }

    private fun requireWritable() = requireWorkspace(!state.value.recoveryRequired, "The workspace needs recovery. Original files were preserved.")

    private fun commit(snapshot: WorkspaceSnapshot, preserveBackup: Boolean = false) {
        validateWorkspace(snapshot)
        val bytes = workspaceJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        requireWorkspace(bytes.size <= 32 * 1024 * 1024, "The workspace file size limit was reached. Previously saved records were preserved.")
        try {
            durableBytes?.let(::preserveSchemaV1Bytes)
            if (!preserveBackup) durableBytes?.let { atomicWrite(backupFile, it) }
            atomicWrite(file, bytes)
            durableBytes = bytes
            mutableState.value = WorkspaceState(snapshot, backupAvailable = validBackup())
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = "Changes could not be saved. Previously saved records were preserved.", backupAvailable = validBackup())
            throw WorkspaceException("Changes could not be saved. Previously saved records were preserved.").also { it.initCause(error) }
        }
    }

    /** The caller validates bytes first; recovery must preserve its backup even without a readable primary. */
    private fun preserveSchemaV1Bytes(bytes: ByteArray) {
        val version = workspaceJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["schemaVersion"]
        if (version?.toString() == "1" && !schemaV1BackupFile.exists()) atomicWrite(schemaV1BackupFile, bytes)
    }

    private fun decode(bytes: ByteArray): WorkspaceSnapshot {
        requireWorkspace(bytes.size <= 32 * 1024 * 1024, "The workspace file is too large.")
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = workspaceJson.parseToJsonElement(text).jsonObject
        requireWorkspace(root.keys.containsAll(setOf("schemaVersion", "revision", "semesters", "courses", "assessments", "tasks", "appointments", "habits", "habitLogs", "receipts", "preferences")), "The workspace file is incomplete.")
        val stored = workspaceJson.decodeFromString<WorkspaceSnapshot>(text)
        requireWorkspace(stored.schemaVersion in 1..2, "The workspace version is unsupported.")
        return stored.copy(schemaVersion = 2).also(::validateWorkspace)
    }

    private fun validBackup(): Boolean = try { backupFile.isFile && decode(backupFile.readBytes()).schemaVersion == 2 } catch (_: Exception) { false }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val parent = destination.absoluteFile.parentFile ?: throw WorkspaceException("The workspace directory is unavailable.")
        requireWorkspace(parent.isDirectory || parent.mkdirs(), "The workspace directory could not be created.")
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Android/Linux rename within one directory is atomic. A failed rename preserves the primary.
                requireWorkspace(temporary.renameTo(destination), "The workspace file could not be replaced.")
            }
            try { FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) } } catch (_: Exception) {
                // Some Android filesystems do not expose directory fsync through Java; file bytes were synced before rename.
            }
        } finally { if (temporary.exists()) temporary.delete() }
    }

    private fun nextRevision(snapshot: WorkspaceSnapshot): Long {
        requireWorkspace(snapshot.revision < Long.MAX_VALUE, "The workspace revision limit was reached.")
        return snapshot.revision + 1
    }

    companion object {
        private val fileLocks = ConcurrentHashMap<String, Mutex>()
        internal val workspaceJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
        private const val changedUndo = "An imported item changed. Undo would remove your edits, so no changes were made."
        private const val dependentUndo = "Later work depends on an imported item. Undo would hide that work, so no changes were made."
    }
}

internal fun requireWorkspace(condition: Boolean, message: String) { if (!condition) throw WorkspaceException(message) }
internal fun canonical(id: String): String = AcademicRecordIds.canonicalUuid(id)

internal fun canonicalPlan(plan: AcademicPlan): AcademicPlan {
    AcademicPlanValidator.validate(plan)
    return plan.copy(proposalID = canonical(plan.proposalID), semester = plan.semester.copy(id = canonical(plan.semester.id)),
        courses = plan.courses.map { it.copy(id = canonical(it.id)) },
        assessments = plan.assessments.map { it.copy(id = canonical(it.id), courseID = canonical(it.courseID)) },
        preparationTasks = plan.preparationTasks.map { it.copy(id = canonical(it.id), assessmentID = canonical(it.assessmentID)) })
}

internal fun planDigest(plan: AcademicPlan): String = MessageDigest.getInstance("SHA-256")
    .digest(AcademicPlanCodec.encode(canonicalPlan(plan)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun entityIDs(snapshot: WorkspaceSnapshot): Set<String> = (snapshot.semesters.map { it.id } + snapshot.courses.map { it.id } +
    snapshot.assessments.map { it.id } + snapshot.tasks.map { it.id } + snapshot.appointments.map { it.id } + snapshot.habits.map { it.id }).toSet()

private fun <T> replacing(items: List<T>, value: T, identity: (T) -> String): List<T> =
    if (items.any { identity(it) == identity(value) }) items.map { if (identity(it) == identity(value)) value else it } else items + value

private fun assessmentKey(value: AcademicAssessment): List<String?> = listOf(value.courseID,
    Normalizer.normalize(value.title, Normalizer.Form.NFC), value.kind, value.dueDate, value.dueTime)

private fun preparationKey(value: WorkTask): List<String?> = listOf(value.sourceAssessmentID,
    Normalizer.normalize(value.title, Normalizer.Form.NFC), value.notes, value.dueDate, value.dueTime, value.sourceZone)

private fun sameTaskContent(a: List<WorkTask>, b: List<WorkTask>): Boolean =
    a.map { it.copy(createdAt = "", updatedAt = "") } == b.map { it.copy(createdAt = "", updatedAt = "") }

private fun validDate(value: String) {
    requireWorkspace(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value), "Use a valid Gregorian YYYY-MM-DD date.")
    val date = LocalDate.parse(value)
    requireWorkspace(date.year in 1..9999, "The date is outside supported years.")
}

private fun validTime(value: String) {
    requireWorkspace(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value), "Use a 24-hour HH:mm time.")
    LocalTime.parse(value)
}

private fun validTitle(value: String) = requireWorkspace(value.isNotBlank() && value == value.trim() && value.length <= 200 &&
    value.none { it.isISOControl() }, "Use a title without surrounding whitespace or control characters, up to 200 characters.")

private fun validNotes(value: String) = requireWorkspace(value.length <= 4000 && value.none { it.isISOControl() && it !in "\n\r\t" }, "Notes are too long or contain unsupported control characters.")

// iOS raw academic imports retain recorded text. Strict v1 proposals and user-owned task editing keep their own limits.
private fun validAcademicName(value: String) = requireWorkspace(value.isNotBlank(), "An academic name cannot be blank.")

private fun validateTask(task: WorkTask, snapshot: WorkspaceSnapshot) {
    canonical(task.id); validTitle(task.title); validNotes(task.notes)
    requireWorkspace(task.kind in setOf("task", "assignment") && task.priority in setOf("low", "medium", "high") && task.minutes in 0..1440,
        "Task kind, priority or estimate is invalid.")
    requireWorkspace(task.dueTime == null || task.dueDate != null, "Choose a due date before a time.")
    task.dueDate?.let(::validDate); task.dueTime?.let(::validTime)
    task.sourceZone?.let { ZoneId.of(it) }
    if (task.dueDate != null && task.dueTime != null) {
        AcademicPlanValidator.resolveInstant(task.dueDate, task.dueTime, task.sourceZone ?: "UTC")
    }
    task.sourceAssessmentID?.let { id ->
        val assessment = snapshot.assessments.find { it.id == canonical(id) && !it.archived }
            ?: throw WorkspaceException("The linked assessment is unavailable.")
        requireWorkspace(snapshot.courses.any { it.id == assessment.courseID && !it.archived } &&
            snapshot.semesters.any { it.id == assessment.semesterID && !it.archived }, "The linked assessment parent is unavailable.")
    }
    requireWorkspace((task.sourceCourseID == null) == (task.sourceWeek == null), "A weekly preparation needs both its course and source week.")
    requireWorkspace(task.sourceAssessmentID == null || task.sourceCourseID == null, "Preparation can link to an assessment or a source week, not both.")
    task.sourceCourseID?.let { id ->
        val course = snapshot.courses.find { it.id == canonical(id) && !it.archived }
            ?: throw WorkspaceException("The linked course is unavailable.")
        requireWorkspace(task.sourceWeek in 1..52 && snapshot.semesters.any { it.id == course.semesterID && !it.archived },
            "The linked source week is unavailable.")
    }
    validateCompletion(task.completed, task.completedAt)
    Instant.parse(task.createdAt); Instant.parse(task.updatedAt)
}

private fun sameV1Semester(actual: AcademicSemester, expected: AcademicSemester): Boolean =
    actual.id == expected.id && actual.title == expected.title && actual.startDate == expected.startDate &&
        actual.weekCount == expected.weekCount && actual.timeZoneIdentifier == expected.timeZoneIdentifier && actual.archived == expected.archived
private fun sameV1Course(actual: AcademicCourse, expected: AcademicCourse): Boolean =
    actual.id == expected.id && actual.semesterID == expected.semesterID && actual.name == expected.name && actual.archived == expected.archived
private fun sameV1Assessment(actual: AcademicAssessment, expected: AcademicAssessment): Boolean =
    actual.id == expected.id && actual.semesterID == expected.semesterID && actual.courseID == expected.courseID &&
        actual.title == expected.title && actual.kind == expected.kind && actual.dueDate == expected.dueDate &&
        actual.dueTime == expected.dueTime && actual.notes == expected.notes && actual.archived == expected.archived
private fun hasBoardSemesterMetadata(value: AcademicSemester): Boolean = value.endDate != null || value.showExamWeeks ||
    value.statuses.isNotEmpty() || value.compatibilityFields.isNotEmpty()
private fun hasBoardCourseMetadata(value: AcademicCourse): Boolean = value.moduleCode != null || value.sourceURL != null ||
    value.scheduleNote != null || value.activeWeeks != null || value.entries.isNotEmpty() || value.externalID != null ||
    value.boardOrder != null || value.compatibilityFields.isNotEmpty()

private fun validateHabit(habit: Habit) {
    canonical(habit.id); validTitle(habit.title); validNotes(habit.notes)
    requireWorkspace(habit.weekdays.isNotEmpty() && habit.weekdays.all { it in 1..7 } && habit.weekdays.distinct().size == habit.weekdays.size,
        "Choose distinct weekdays from Monday through Sunday.")
}

private fun validateAppointment(item: Appointment) {
    canonical(item.id); validTitle(item.title); validNotes(item.notes)
    val start = AcademicPlanValidator.resolveInstant(item.startDate, item.startTime, item.timeZoneIdentifier)
    val end = AcademicPlanValidator.resolveInstant(item.endDate, item.endTime, item.timeZoneIdentifier)
    requireWorkspace(end > start, "An appointment must end after it starts.")
}

private fun validatePreferences(value: Preferences) {
    requireWorkspace(value.language in setOf("system", "en", "de", "es") && value.theme in setOf("system", "light", "dark"), "Unsupported language or theme.")
    requireWorkspace(value.userName.length <= 200 && value.userName.none { it.isISOControl() }, "Profile name is too long or invalid.")
}

private fun validateCompletion(completed: Boolean, completedAt: String?) {
    requireWorkspace(completed || completedAt == null, "An unfinished item cannot have a completion timestamp.")
    completedAt?.let { Instant.parse(it) }
}

internal fun validateWorkspace(snapshot: WorkspaceSnapshot) {
    requireWorkspace(snapshot.schemaVersion == 2 && snapshot.revision >= 0, "The workspace version or revision is unsupported.")
    val ids = snapshot.semesters.map { it.id } + snapshot.courses.map { it.id } + snapshot.assessments.map { it.id } +
        snapshot.tasks.map { it.id } + snapshot.appointments.map { it.id } + snapshot.habits.map { it.id }
    requireWorkspace(ids.distinct().size == ids.size && ids.all { it == canonical(it) }, "The workspace contains invalid or duplicate IDs.")
    snapshot.semesters.forEach { item ->
        validAcademicName(item.title); item.startDate?.let(::validDate); item.endDate?.let(::validDate); ZoneId.of(item.timeZoneIdentifier)
        requireWorkspace(item.endDate == null || item.startDate != null, "An end date needs a start date.")
        if (item.startDate != null && item.endDate != null) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(item.startDate), LocalDate.parse(item.endDate)) + 1
            requireWorkspace(days in 1..364 && (days + 6) / 7 == item.weekCount.toLong(), "The date range and teaching week count disagree.")
        }
        requireWorkspace(item.statuses.map { it.id }.distinct().size == item.statuses.size && item.statuses.size <= 20, "Invalid or duplicate semester statuses.")
        item.statuses.forEach { status -> canonical(status.id); validAcademicName(status.name); requireWorkspace(Regex("#[0-9a-fA-F]{6}").matches(status.colorHex), "A status color is invalid.") }
        requireWorkspace(item.weekCount in 1..52, "The semester week count is invalid.")
    }
    snapshot.semesters.forEach { semester -> requireWorkspace(snapshot.courses.count { it.semesterID == semester.id && !it.archived } <= 10, "A semester can contain at most ten active courses.") }
    requireWorkspace(snapshot.courses.map { it.semesterID to (it.externalID ?: it.id) }.distinct().size == snapshot.courses.size, "Duplicate scoped course identities.")
    requireWorkspace(snapshot.assessments.map { it.courseID to (it.externalID ?: it.id) }.distinct().size == snapshot.assessments.size, "Duplicate scoped assessment identities.")
    snapshot.courses.forEach { item ->
        validAcademicName(item.name)
        val semester = snapshot.semesters.find { it.id == item.semesterID } ?: throw WorkspaceException("The course semester is missing.")
        item.externalID?.let(::canonical)
        requireWorkspace(item.boardOrder == null || item.boardOrder >= 0, "Course order is invalid.")
        requireWorkspace(item.activeWeeks == null || (item.activeWeeks.size <= 52 && item.activeWeeks.distinct().size == item.activeWeeks.size && item.activeWeeks.all { it in 1..52 }), "Active source weeks are invalid.")
        requireWorkspace(item.entries.size <= 52 && item.entries.keys.all { it in 1..52 }, "Source week entries are invalid.")
        item.entries.values.forEach { entry -> entry.statusID?.let { id -> requireWorkspace(item.archived || semester.archived || semester.statuses.any { it.id == canonical(id) }, "A source week references a missing status.") } }
    }
    snapshot.assessments.forEach { item ->
        validAcademicName(item.title); validDate(item.dueDate); item.dueTime?.let(::validTime)
        val semester = snapshot.semesters.find { it.id == item.semesterID } ?: throw WorkspaceException("The assessment semester is missing.")
        requireWorkspace(snapshot.courses.any { it.id == item.courseID && it.semesterID == item.semesterID }, "The assessment course is missing.")
        requireWorkspace(item.kind in setOf("exam", "presentation", "essay", "deadline"), "The assessment kind is invalid.")
        // Raw imported civil clocks can fall in a DST gap/overlap. Projection skips them rather than altering source facts.
        item.externalID?.let(::canonical)
        requireWorkspace(item.boardOrder == null || item.boardOrder >= 0, "Assessment order is invalid.")
        requireWorkspace(!(item.completed && item.dismissed), "An assessment cannot be completed and dismissed.")
        validateCompletion(item.completed, item.completedAt)
    }
    snapshot.semesters.filterNot { it.archived }.forEach { semester ->
        // Raw semantic checks support unknown legacy schedules and retain valid DST civil clocks.
        SemesterBoardCodec.validate(SemesterBoardExport.document(snapshot, semester.id, includeCompatibilityFields = true))
    }
    // Archived relationships remain valid history after Undo.
    val historicalSources = snapshot.copy(assessments = snapshot.assessments.map { it.copy(archived = false) }, courses = snapshot.courses.map { it.copy(archived = false) }, semesters = snapshot.semesters.map { it.copy(archived = false) })
    snapshot.tasks.forEach { validateTask(it, historicalSources) }
    snapshot.appointments.forEach(::validateAppointment)
    snapshot.habits.forEach(::validateHabit)
    requireWorkspace(snapshot.habitLogs.map { it.habitID to it.date }.distinct().size == snapshot.habitLogs.size, "Duplicate daily habit logs.")
    snapshot.habitLogs.forEach { validDate(it.date); requireWorkspace(snapshot.habits.any { habit -> habit.id == it.habitID }, "The habit log source is missing.") }
    validatePreferences(snapshot.preferences)
    requireWorkspace(snapshot.receipts.map { it.proposalID }.distinct().size == snapshot.receipts.size, "Duplicate proposal receipts.")
    requireWorkspace((snapshot.receipts.map { it.proposalID } + snapshot.boardReceipts.map { it.proposalID }).distinct().size == snapshot.receipts.size + snapshot.boardReceipts.size, "Duplicate proposal receipts.")
    snapshot.boardReceipts.forEach { receipt -> validateBoardReceipt(receipt, snapshot) }
    snapshot.receipts.forEach { receipt ->
        requireWorkspace(receipt.proposalID == canonical(receipt.proposalID) && receipt.proposalID == canonical(receipt.plan.proposalID) &&
            receipt.digest == planDigest(receipt.plan), "The proposal receipt is invalid.")
        validateReceiptSources(receipt, snapshot)
        Instant.parse(receipt.appliedAt); receipt.undoneAt?.let { Instant.parse(it) }
        requireWorkspace((receipt.state == ReceiptState.UNDONE) == (receipt.undoneAt != null), "The receipt state is inconsistent.")
    }
}

private fun validateReceiptSources(receipt: PlanReceipt, snapshot: WorkspaceSnapshot) {
    val plan = canonicalPlan(receipt.plan)
    requireWorkspace(receipt.source == plan.source, "The receipt source is inconsistent.")
    val semester = AcademicSemester(plan.semester.id, plan.semester.title, plan.semester.startDate,
        plan.semester.weekCount, plan.semester.timeZoneIdentifier)
    val courses = plan.courses.map { AcademicCourse(it.id, semester.id, it.name) }
    val assessments = plan.assessments.map { AcademicAssessment(it.id, semester.id, it.courseID, it.title,
        it.kind, it.dueDate, it.dueTime, it.notes) }
    requireWorkspace(receipt.createdSemesters.distinct().size == receipt.createdSemesters.size &&
        receipt.createdSemesters.all { it == semester && snapshot.semesters.any { item -> item.id == it.id } }, "The receipt semester is invalid.")
    requireWorkspace(receipt.createdCourses.map { it.id }.distinct().size == receipt.createdCourses.size &&
        receipt.createdCourses.all { it in courses && snapshot.courses.any { item -> item.id == it.id } }, "The receipt courses are invalid.")
    requireWorkspace(receipt.createdAssessments.map { it.id }.distinct().size == receipt.createdAssessments.size &&
        receipt.createdAssessments.all { it in assessments && snapshot.assessments.any { item -> item.id == it.id } }, "The receipt assessments are invalid.")
    requireWorkspace(receipt.createdTasks.map { it.id }.toSet() == plan.preparationTasks.map { it.id }.toSet() &&
        receipt.createdTasks.size == plan.preparationTasks.size, "The receipt preparation identities are invalid.")
    receipt.createdTasks.forEach { expected ->
        val proposed = plan.preparationTasks.single { it.id == expected.id }
        val value = WorkTask(id = proposed.id, title = proposed.title, notes = proposed.notes,
            dueDate = proposed.dueDate, dueTime = proposed.dueTime, sourceZone = plan.semester.timeZoneIdentifier,
            minutes = proposed.estimatedWorkMinutes, sourceAssessmentID = proposed.assessmentID,
            createdAt = expected.createdAt, updatedAt = expected.createdAt)
        requireWorkspace(expected == value && snapshot.tasks.any { it.id == expected.id }, "The receipt preparation values are invalid.")
    }
}
