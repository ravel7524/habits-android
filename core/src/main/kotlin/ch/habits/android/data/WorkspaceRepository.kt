package ch.habits.android.data

import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.core.AcademicPlanValidator
import ch.habits.android.core.AcademicRecordIds
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
        receipt.createdSemesters.forEach { requireWorkspace(snapshot.semesters.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.createdCourses.forEach { requireWorkspace(snapshot.courses.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.createdAssessments.forEach { requireWorkspace(snapshot.assessments.find { item -> item.id == it.id } == it, changedUndo) }
        receipt.createdTasks.forEach { requireWorkspace(snapshot.tasks.find { item -> item.id == it.id } == it, changedUndo) }
        val semesterIDs = receipt.createdSemesters.map { it.id }.toSet()
        val courseIDs = receipt.createdCourses.map { it.id }.toSet()
        val assessmentIDs = receipt.createdAssessments.map { it.id }.toSet()
        val taskIDs = receipt.createdTasks.map { it.id }.toSet()
        requireWorkspace(snapshot.courses.none { !it.archived && it.semesterID in semesterIDs && it.id !in courseIDs }, dependentUndo)
        requireWorkspace(snapshot.assessments.none { !it.archived && (it.semesterID in semesterIDs || it.courseID in courseIDs) && it.id !in assessmentIDs }, dependentUndo)
        requireWorkspace(snapshot.tasks.none { !it.archived && it.sourceAssessmentID in assessmentIDs && it.id !in taskIDs }, dependentUndo)
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
        if (assessment.completed == completed) snapshot else snapshot.copy(assessments = replacing(snapshot.assessments,
            assessment.copy(completed = completed, completedAt = if (completed) Instant.now().toString() else null)) { it.id })
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
        requireWorkspace(oldSemester == null || oldSemester == wantedSemester, "Existing semester metadata conflicts with the proposal.")
        requireWorkspace(snapshot.semesters.none { it.id != wantedSemester.id && it.copy(id = wantedSemester.id, archived = false) == wantedSemester },
            "A semester with this title, date range and timezone already exists, including retained history.")
        val newSemesters = if (oldSemester == null) listOf(wantedSemester) else emptyList()
        val newCourses = plan.courses.map { AcademicCourse(it.id, wantedSemester.id, it.name) }.filter { wanted ->
            val old = snapshot.courses.find { it.id == wanted.id }
            requireWorkspace(old == null || old == wanted, "Existing course metadata conflicts with the proposal.")
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
            requireWorkspace(old == null || (!old.archived && old.copy(completed = false, completedAt = null) == wanted),
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
            if (!preserveBackup) durableBytes?.let { atomicWrite(backupFile, it) }
            atomicWrite(file, bytes)
            durableBytes = bytes
            mutableState.value = WorkspaceState(snapshot, backupAvailable = validBackup())
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = "Changes could not be saved. Previously saved records were preserved.", backupAvailable = validBackup())
            throw WorkspaceException("Changes could not be saved. Previously saved records were preserved.").also { it.initCause(error) }
        }
    }

    private fun decode(bytes: ByteArray): WorkspaceSnapshot {
        requireWorkspace(bytes.size <= 32 * 1024 * 1024, "The workspace file is too large.")
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = workspaceJson.parseToJsonElement(text).jsonObject
        requireWorkspace(root.keys.containsAll(setOf("schemaVersion", "revision", "semesters", "courses", "assessments", "tasks", "appointments", "habits", "habitLogs", "receipts", "preferences")), "The workspace file is incomplete.")
        return workspaceJson.decodeFromString<WorkspaceSnapshot>(text).also(::validateWorkspace)
    }

    private fun validBackup(): Boolean = try { backupFile.isFile && decode(backupFile.readBytes()).schemaVersion == 1 } catch (_: Exception) { false }

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
        requireWorkspace(snapshot.assessments.any { it.id == canonical(id) && !it.archived }, "The linked assessment is unavailable.")
    }
    validateCompletion(task.completed, task.completedAt)
    Instant.parse(task.createdAt); Instant.parse(task.updatedAt)
}

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

private fun validateWorkspace(snapshot: WorkspaceSnapshot) {
    requireWorkspace(snapshot.schemaVersion == 1 && snapshot.revision >= 0, "The workspace version or revision is unsupported.")
    val ids = snapshot.semesters.map { it.id } + snapshot.courses.map { it.id } + snapshot.assessments.map { it.id } +
        snapshot.tasks.map { it.id } + snapshot.appointments.map { it.id } + snapshot.habits.map { it.id }
    requireWorkspace(ids.distinct().size == ids.size && ids.all { it == canonical(it) }, "The workspace contains invalid or duplicate IDs.")
    snapshot.semesters.forEach { item ->
        validTitle(item.title); validDate(item.startDate); ZoneId.of(item.timeZoneIdentifier)
        requireWorkspace(item.weekCount in 1..52, "The semester week count is invalid.")
    }
    snapshot.courses.forEach { item ->
        validTitle(item.name)
        requireWorkspace(snapshot.semesters.any { it.id == item.semesterID }, "The course semester is missing.")
    }
    snapshot.assessments.forEach { item ->
        validTitle(item.title); validNotes(item.notes); validDate(item.dueDate); item.dueTime?.let(::validTime)
        val semester = snapshot.semesters.find { it.id == item.semesterID } ?: throw WorkspaceException("The assessment semester is missing.")
        requireWorkspace(snapshot.courses.any { it.id == item.courseID && it.semesterID == item.semesterID }, "The assessment course is missing.")
        requireWorkspace(item.kind in setOf("exam", "presentation", "essay", "deadline"), "The assessment kind is invalid.")
        AcademicPlanValidator.resolveInstant(item.dueDate, item.dueTime, semester.timeZoneIdentifier)
        validateCompletion(item.completed, item.completedAt)
    }
    // Archived relationships remain valid history after Undo.
    val historicalSources = snapshot.copy(assessments = snapshot.assessments.map { it.copy(archived = false) })
    snapshot.tasks.forEach { validateTask(it, historicalSources) }
    snapshot.appointments.forEach(::validateAppointment)
    snapshot.habits.forEach(::validateHabit)
    requireWorkspace(snapshot.habitLogs.map { it.habitID to it.date }.distinct().size == snapshot.habitLogs.size, "Duplicate daily habit logs.")
    snapshot.habitLogs.forEach { validDate(it.date); requireWorkspace(snapshot.habits.any { habit -> habit.id == it.habitID }, "The habit log source is missing.") }
    validatePreferences(snapshot.preferences)
    requireWorkspace(snapshot.receipts.map { it.proposalID }.distinct().size == snapshot.receipts.size, "Duplicate proposal receipts.")
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
