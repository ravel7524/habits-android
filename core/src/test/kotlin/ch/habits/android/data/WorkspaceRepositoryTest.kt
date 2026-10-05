package ch.habits.android.data

import ch.habits.android.core.AcademicPlan
import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.core.PlanAssessment
import ch.habits.android.core.PlanCourse
import ch.habits.android.core.PlanPreparation
import ch.habits.android.core.PlanSemester
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceRepositoryTest {
    private val proposalID = "a1000000-0000-0000-0000-000000000001"
    private val semesterID = "a1000000-0000-0000-0000-000000000002"
    private val courseID = "a1000000-0000-0000-0000-000000000003"
    private val assessmentID = "a1000000-0000-0000-0000-000000000004"
    private val preparationID = "a1000000-0000-0000-0000-000000000005"

    private fun plan() = AcademicPlan(proposalID = proposalID, source = "University schedule",
        semester = PlanSemester(semesterID, "Autumn 2026", "2026-09-14", 14, "Europe/Zurich"),
        courses = listOf(PlanCourse(courseID, "Statistics")),
        assessments = listOf(PlanAssessment(assessmentID, courseID, "Statistics exam", "exam", "2026-10-09", "09:30", "Calculator permitted")),
        preparationTasks = listOf(PlanPreparation(preparationID, assessmentID, "Review probability", "2026-10-08", "18:00", 45, "Assigned exercises")))

    private suspend fun fixture(block: suspend (WorkspaceRepository, File) -> Unit) {
        val directory = Files.createTempDirectory("habits-workspace-test-").toFile()
        val file = File(directory, "workspace.json")
        try { block(WorkspaceRepository(file), file) } finally { directory.deleteRecursively() }
    }

    @Test fun previewDoesNotWriteFilesOrSourceRecords() = runBlocking { fixture { repository, file ->
        val before = repository.state.value
        val preview = repository.preview(plan())
        assertEquals(PlanCounts(1, 1, 1, 1), preview.counts)
        assertEquals(before, repository.state.value)
        assertFalse(file.exists())
        assertFalse(repository.backupFile.exists())
        assertTrue(file.parentFile.listFiles().orEmpty().isEmpty())
    } }

    @Test fun applyCommitsSourcesAndLedgerTogetherAndReloads() = runBlocking { fixture { repository, file ->
        val receipt = repository.apply(repository.preview(plan()))
        assertEquals(ReceiptState.APPLIED, receipt.state)
        val snapshot = repository.state.value.snapshot
        assertEquals(1L, snapshot.revision)
        assertEquals(listOf(semesterID), snapshot.semesters.map { it.id })
        assertEquals(listOf(assessmentID), snapshot.assessments.map { it.id })
        assertEquals(listOf(preparationID), snapshot.tasks.map { it.id })
        assertEquals(assessmentID, snapshot.tasks.single().sourceAssessmentID)
        assertEquals("2026-10-08", snapshot.tasks.single().dueDate)
        assertEquals("18:00", snapshot.tasks.single().dueTime)
        assertEquals("Europe/Zurich", snapshot.tasks.single().sourceZone)
        assertEquals(45, snapshot.tasks.single().minutes)
        assertFalse(snapshot.assessments.single().completed)
        assertFalse(snapshot.tasks.single().completed)
        assertEquals(listOf(receipt), snapshot.receipts)
        val restarted = WorkspaceRepository(file)
        assertFalse(restarted.state.value.recoveryRequired)
        assertEquals(snapshot, restarted.state.value.snapshot)
    } }

    @Test fun replayPreservesLaterEditsAndExplicitCompletions() = runBlocking { fixture { repository, file ->
        val original = repository.apply(repository.preview(plan()))
        repository.saveTask(repository.state.value.snapshot.tasks.single().copy(title = "My preparation"))
        repository.completeTask(preparationID)
        repository.completeAssessment(assessmentID)
        val before = file.readBytes()
        val preview = repository.preview(plan())
        assertTrue(preview.alreadyHandled)
        assertEquals(PlanCounts(0, 0, 0, 0), preview.counts)
        assertEquals(original, repository.apply(preview))
        assertContentEquals(before, file.readBytes())
        assertEquals("My preparation", repository.state.value.snapshot.tasks.single().title)
        assertTrue(repository.state.value.snapshot.tasks.single().completed)
        assertTrue(repository.state.value.snapshot.assessments.single().completed)
    } }

    @Test fun uuidCaseOnlyReplayIsSemanticallyIdentical() = runBlocking { fixture { repository, file ->
        val original = plan()
        repository.apply(repository.preview(original))
        val uppercase = original.copy(proposalID = original.proposalID.uppercase(),
            semester = original.semester.copy(id = original.semester.id.uppercase()),
            courses = original.courses.map { it.copy(id = it.id.uppercase()) },
            assessments = original.assessments.map { it.copy(id = it.id.uppercase(), courseID = it.courseID.uppercase()) },
            preparationTasks = original.preparationTasks.map { it.copy(id = it.id.uppercase(), assessmentID = it.assessmentID.uppercase()) })
        val before = file.readBytes()
        assertTrue(repository.preview(uppercase).alreadyHandled)
        repository.apply(repository.preview(uppercase))
        assertContentEquals(before, file.readBytes())
        assertEquals(1, repository.state.value.snapshot.receipts.size)
    } }

    @Test fun sameProposalIdWithDifferentContentIsRejected() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.preview(plan().copy(source = "Different source")) }
        assertContentEquals(before, file.readBytes())
        assertEquals(1, repository.state.value.snapshot.tasks.size)
    } }

    @Test fun stalePreviewDoesNotOverwriteSavedPersonalWork() = runBlocking { fixture { repository, file ->
        val preview = repository.preview(plan())
        val personal = WorkTask(title = "Personal task", kind = "assignment")
        repository.saveTask(personal)
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.apply(preview) }
        assertContentEquals(before, file.readBytes())
        val snapshot = repository.state.value.snapshot
        assertTrue(snapshot.semesters.isEmpty())
        assertTrue(snapshot.receipts.isEmpty())
        assertEquals(listOf(personal.id), snapshot.tasks.map { it.id })
    } }

    @Test fun concurrentRepositoryInstancesPreserveIndependentWrites() = runBlocking { fixture { first, file ->
        val second = WorkspaceRepository(file)
        coroutineScope {
            (1..20).map { index -> async {
                val repository = if (index % 2 == 0) first else second
                repository.saveTask(WorkTask(title = "Task $index"))
            } }.awaitAll()
        }
        val loaded = WorkspaceRepository(file).state.value.snapshot
        assertEquals(20, loaded.tasks.size)
        assertEquals(20, loaded.tasks.map { it.id }.distinct().size)
        assertEquals(20L, loaded.revision)
    } }

    @Test fun staleSecondInstancePreviewFailsAfterFirstWrites() = runBlocking { fixture { first, file ->
        val second = WorkspaceRepository(file)
        val preview = second.preview(plan())
        first.saveTask(WorkTask(title = "First client's saved work"))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { second.apply(preview) }
        assertContentEquals(before, file.readBytes())
        assertEquals(1, second.state.value.snapshot.tasks.size)
    } }

    @Test fun staleTaskEditorCannotOverwriteLaterCompletion() = runBlocking { fixture { repository, file ->
        val task = WorkTask(title = "Task before editor opens")
        repository.saveTask(task)
        val draft = repository.state.value.snapshot.tasks.single()
        repository.completeTask(task.id)
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.saveTask(draft.copy(title = "Stale title")) }
        assertContentEquals(before, file.readBytes())
        assertTrue(repository.state.value.snapshot.tasks.single().completed)
    } }

    @Test fun unchangedTaskSaveDoesNotInvalidateImportUndo() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        val before = file.readBytes()
        repository.saveTask(repository.state.value.snapshot.tasks.single())
        assertContentEquals(before, file.readBytes())
        assertEquals(ReceiptState.UNDONE, repository.undo(proposalID).state)
    } }

    @Test fun backupWriteFailurePreservesPrimaryAndPublishedState() = runBlocking { fixture { repository, file ->
        repository.saveTask(WorkTask(title = "Previously saved"))
        val before = file.readBytes()
        val snapshot = repository.state.value.snapshot
        assertTrue(repository.backupFile.mkdir())
        val blocker = File(repository.backupFile, "keep.txt")
        blocker.writeText("Retained blocker")
        assertFailsWith<WorkspaceException> { repository.saveTask(WorkTask(title = "Cannot persist")) }
        assertContentEquals(before, file.readBytes())
        assertEquals(snapshot, repository.state.value.snapshot)
        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.recoveryRequired)
        assertEquals("Retained blocker", blocker.readText())
        assertEquals(snapshot, WorkspaceRepository(file).state.value.snapshot)
    } }

    @Test fun corruptPrimaryRequiresExplicitRecoveryAndPreservesBytes() = runBlocking { fixture { repository, file ->
        repository.saveTask(WorkTask(title = "In the backup"))
        val backupSnapshot = repository.state.value.snapshot
        repository.saveTask(WorkTask(title = "Latest record"))
        val broken = "{interrupted external edit".toByteArray()
        file.writeBytes(broken)
        val restarted = WorkspaceRepository(file)
        assertTrue(restarted.state.value.recoveryRequired)
        assertTrue(restarted.state.value.backupAvailable)
        assertFailsWith<WorkspaceException> { restarted.saveTask(WorkTask(title = "Must not reset data")) }
        assertContentEquals(broken, file.readBytes())
        assertContentEquals(broken, restarted.exportRawData())
        restarted.restoreBackup()
        assertFalse(restarted.state.value.recoveryRequired)
        assertEquals(backupSnapshot.tasks, restarted.state.value.snapshot.tasks)
        val preserved = file.parentFile.listFiles().orEmpty().single { it.name.startsWith(file.name + ".recovery-") }
        assertContentEquals(broken, preserved.readBytes())
        assertTrue(restarted.backupFile.isFile)
    } }

    @Test fun corruptPrimaryAndBackupCannotBeResetByRestore() = runBlocking { fixture { repository, file ->
        repository.saveTask(WorkTask(title = "First"))
        repository.saveTask(WorkTask(title = "Second"))
        val primary = "invalid primary".toByteArray()
        val backup = "invalid backup".toByteArray()
        file.writeBytes(primary)
        repository.backupFile.writeBytes(backup)
        val restarted = WorkspaceRepository(file)
        assertTrue(restarted.state.value.recoveryRequired)
        assertFalse(restarted.state.value.backupAvailable)
        assertFailsWith<WorkspaceException> { restarted.restoreBackup() }
        assertContentEquals(primary, file.readBytes())
        assertContentEquals(backup, repository.backupFile.readBytes())
    } }

    @Test fun missingPrimaryWithBackupRequiresRecoveryAndNeverExportsInventedEmptyData() = runBlocking { fixture { repository, file ->
        repository.saveTask(WorkTask(title = "Backup task"))
        repository.saveTask(WorkTask(title = "Latest task"))
        val backup = repository.backupFile.readBytes()
        assertTrue(file.delete())
        val restarted = WorkspaceRepository(file)
        assertTrue(restarted.state.value.recoveryRequired)
        assertTrue(restarted.state.value.backupAvailable)
        assertFailsWith<WorkspaceException> { restarted.saveTask(WorkTask(title = "Forbidden reset")) }
        assertFalse(file.exists())
        assertContentEquals(backup, restarted.exportRawData())
        restarted.restoreBackup()
        assertEquals(listOf("Backup task"), restarted.state.value.snapshot.tasks.map { it.title })
    } }

    @Test fun unsupportedSchemaAndIncompleteJsonArePreserved() = runBlocking { fixture { _, file ->
        val unsupported = WorkspaceRepository.workspaceJson.encodeToString(WorkspaceSnapshot(schemaVersion = 2)).toByteArray()
        file.writeBytes(unsupported)
        val future = WorkspaceRepository(file)
        assertTrue(future.state.value.recoveryRequired)
        assertFailsWith<WorkspaceException> { future.preview(plan()) }
        assertContentEquals(unsupported, file.readBytes())
        file.writeText("{}")
        val incomplete = WorkspaceRepository(file)
        assertTrue(incomplete.state.value.recoveryRequired)
        assertEquals("{}", file.readText())
    } }

    @Test fun undoArchivesOnlyCreatedAdditionsAndReplayRemainsConsumed() = runBlocking { fixture { repository, file ->
        val parents = plan().copy(assessments = emptyList(), preparationTasks = emptyList())
        repository.apply(repository.preview(parents))
        val additions = plan().copy(proposalID = UUID.randomUUID().toString())
        val preview = repository.preview(additions)
        assertEquals(PlanCounts(0, 0, 1, 1), preview.counts)
        repository.apply(preview)
        val personal = WorkTask(title = "Keep personal work")
        repository.saveTask(personal)
        val undone = repository.undo(additions.proposalID)
        assertEquals(ReceiptState.UNDONE, undone.state)
        val snapshot = repository.state.value.snapshot
        assertFalse(snapshot.semesters.single().archived)
        assertFalse(snapshot.courses.single().archived)
        assertTrue(snapshot.assessments.single().archived)
        assertTrue(snapshot.tasks.single { it.id == preparationID }.archived)
        assertFalse(snapshot.tasks.single { it.id == personal.id }.archived)
        val before = file.readBytes()
        assertEquals(undone, repository.undo(additions.proposalID))
        assertEquals(undone, repository.apply(repository.preview(additions)))
        assertContentEquals(before, file.readBytes())
        assertEquals(snapshot, WorkspaceRepository(file).state.value.snapshot)
    } }

    @Test fun undoRejectsEditedPreparationBeforeAnyArchive() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        repository.saveTask(repository.state.value.snapshot.tasks.single().copy(notes = "My later note"))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.undo(proposalID) }
        assertContentEquals(before, file.readBytes())
        assertTrue(repository.state.value.snapshot.semesters.none { it.archived })
        assertFalse(repository.state.value.snapshot.tasks.single().archived)
    } }

    @Test fun undoRejectsCompletedAssessmentAndKeepsPreparation() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        repository.completeAssessment(assessmentID)
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.undo(proposalID) }
        assertContentEquals(before, file.readBytes())
        assertFalse(repository.state.value.snapshot.tasks.single().archived)
        assertTrue(repository.state.value.snapshot.assessments.single().completed)
    } }

    @Test fun undoProtectsLaterLinkedPreparation() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        repository.saveTask(WorkTask(title = "Additional revision", sourceAssessmentID = assessmentID))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.undo(proposalID) }
        assertContentEquals(before, file.readBytes())
        assertEquals(2, repository.state.value.snapshot.tasks.count { !it.archived })
    } }

    @Test fun undoProtectsNewAssessmentUnderCreatedCourse() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        val later = plan().copy(proposalID = UUID.randomUUID().toString(), preparationTasks = emptyList(),
            assessments = listOf(PlanAssessment(UUID.randomUUID().toString(), courseID, "User-added essay", "essay", "2026-10-12")))
        repository.apply(repository.preview(later))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.undo(proposalID) }
        assertContentEquals(before, file.readBytes())
        assertEquals(2, repository.state.value.snapshot.assessments.count { !it.archived })
    } }

    @Test fun archivedSourceCannotBeResurrectedByFreshProposal() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        repository.undo(proposalID)
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.preview(plan().copy(proposalID = UUID.randomUUID().toString())) }
        assertContentEquals(before, file.readBytes())
        assertTrue(repository.state.value.snapshot.assessments.single().archived)
    } }

    @Test fun newUuidCannotDuplicateExistingAssessmentContent() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        val duplicate = plan().copy(proposalID = UUID.randomUUID().toString(), preparationTasks = emptyList(),
            assessments = plan().assessments.map { it.copy(id = UUID.randomUUID().toString()) })
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.preview(duplicate) }
        assertContentEquals(before, file.readBytes())
    } }

    @Test fun preparationCompletionIsNotExamCompletionOrStudiedMinutes() = runBlocking { fixture { repository, _ ->
        repository.apply(repository.preview(plan()))
        repository.completeTask(preparationID)
        val snapshot = repository.state.value.snapshot
        assertTrue(snapshot.tasks.single().completed)
        assertNotNull(snapshot.tasks.single().completedAt)
        assertFalse(snapshot.assessments.single().completed)
        assertNull(snapshot.assessments.single().completedAt)
        val today = LocalDate.now(ZoneId.of("UTC"))
        val report = WorkspaceFacts.report(snapshot, today, today.plusDays(1), ZoneId.of("UTC"))
        assertEquals(1, report.completedTasks.size)
        assertTrue(report.completedAssessments.isEmpty())
        assertTrue(report.coverage.any { it.contains("not time studied") })
    } }

    @Test fun reportExcludesUntimedHistoricalCompletionWithCoverageDisclosure() {
        val snapshot = WorkspaceSnapshot(tasks = listOf(WorkTask(title = "Historical completion", completed = true)))
        val report = WorkspaceFacts.report(snapshot, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-08"))
        assertTrue(report.completedTasks.isEmpty())
        assertTrue(report.coverage.any { it.contains("1 completed items have no completion timestamp") })
        assertTrue(report.habitLogs.isEmpty())
    }

    @Test fun dailyHabitLogIsIdempotentAndArchiveKeepsHistory() = runBlocking { fixture { repository, file ->
        val habit = Habit(title = "Read", weekdays = listOf(1, 3, 5))
        repository.saveHabit(habit)
        repository.logHabit(habit.id, "2026-10-05")
        repository.logHabit(habit.id, "2026-10-05", completed = false)
        repository.logHabit(habit.id, "2026-10-05")
        assertEquals(listOf(HabitLog(habit.id, "2026-10-05")), repository.state.value.snapshot.habitLogs)
        repository.archiveHabit(habit.id)
        val snapshot = WorkspaceRepository(file).state.value.snapshot
        assertTrue(snapshot.habits.single().archived)
        assertEquals(1, snapshot.habitLogs.size)
        val report = WorkspaceFacts.report(snapshot, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-08"))
        assertEquals(1, report.habitLogs.size)
        assertTrue(report.coverage.any { it.contains("Missing logs are not evidence") })
    } }

    @Test fun appointmentRejectsInvalidRangeWithoutWrites() = runBlocking { fixture { repository, file ->
        val appointment = Appointment(title = "Office hour", startDate = "2026-10-08", startTime = "10:00",
            endDate = "2026-10-08", endTime = "09:00", timeZoneIdentifier = "Europe/Zurich")
        assertFailsWith<WorkspaceException> { repository.saveAppointment(appointment) }
        assertFalse(file.exists())
        assertTrue(repository.state.value.snapshot.appointments.isEmpty())
    } }

    @Test fun dateOnlyAndExplicitMidnightArePreservedAsDifferentTypes() = runBlocking { fixture { repository, file ->
        val task = plan().preparationTasks.single().copy(dueDate = "2026-10-08", dueTime = null)
        val dateOnly = plan().copy(preparationTasks = listOf(task))
        repository.apply(repository.preview(dateOnly))
        assertNull(repository.state.value.snapshot.tasks.single().dueTime)
        val personal = WorkTask(title = "Explicit midnight", dueDate = "2026-10-08", dueTime = "00:00", sourceZone = "Europe/Zurich")
        repository.saveTask(personal)
        val snapshot = WorkspaceRepository(file).state.value.snapshot
        assertNull(snapshot.tasks.single { it.id == preparationID }.dueTime)
        assertEquals("00:00", snapshot.tasks.single { it.id == personal.id }.dueTime)
    } }

    @Test fun contextExportOmitsNotesAndUnrelatedPersonalDataByDefault() = runBlocking { fixture { repository, _ ->
        repository.apply(repository.preview(plan()))
        repository.saveTask(WorkTask(title = "Private personal task", notes = "Unrelated secret"))
        val snapshot = repository.state.value.snapshot
        val safe = AssistantContextExporter.build(snapshot, semesterID)
        assertTrue(safe.contains("Statistics exam"))
        assertFalse(safe.contains("Calculator permitted"))
        assertFalse(safe.contains("Assigned exercises"))
        assertFalse(safe.contains("Private personal task"))
        assertFalse(safe.contains("Unrelated secret"))
        val explicit = AssistantContextExporter.build(snapshot, semesterID, includeNotes = true)
        assertTrue(explicit.contains("Calculator permitted"))
        assertTrue(explicit.contains("Assigned exercises"))
        assertFalse(explicit.contains("Unrelated secret"))
    } }

    @Test fun sharedContextExportsRecordedAssessmentStateWithoutTaskOrHabitHistory() = runBlocking { fixture { repository, file ->
        repository.apply(repository.preview(plan()))
        repository.saveTask(WorkTask(title = "Private personal task", notes = "Private task history"))
        val habit = Habit(title = "Private habit", notes = "Private habit history")
        repository.saveHabit(habit)
        repository.logHabit(habit.id, "2026-10-05")
        repository.completeTask(preparationID)
        val before = file.readBytes()
        val pending = AssistantContextExporter.sharedAcademicContext(repository.state.value.snapshot, semesterID)
        val pendingRoot = Json.parseToJsonElement(pending).jsonObject
        assertEquals(setOf("format", "version", "capturedAt", "semesters"), pendingRoot.keys)
        val sharedSemester = pendingRoot.getValue("semesters").jsonArray.single().jsonObject
        assertEquals(setOf("id", "title", "startDate", "weekCount", "timeZoneIdentifier", "courses", "existingAssessments"), sharedSemester.keys)
        assertEquals("pending", sharedSemester.getValue("existingAssessments").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content,
            "Preparation completion must not turn the linked exam into a completed assessment")
        assertFalse(pending.contains("Calculator permitted"))
        assertFalse(pending.contains("Review probability"))
        assertFalse(pending.contains("Private personal task"))
        assertFalse(pending.contains("Private task history"))
        assertFalse(pending.contains("Private habit"))
        assertFalse(pending.contains("habitLogs"))
        assertFalse(pending.contains("createdTasks"))
        assertFalse(pending.contains("preferences"))
        assertContentEquals(before, file.readBytes())

        repository.completeAssessment(assessmentID)
        val snapshot = repository.state.value.snapshot
        val archived = snapshot.assessments.single().copy(id = UUID.randomUUID().toString(), title = "Archived private exam", archived = true)
        val completed = AssistantContextExporter.sharedAcademicContext(snapshot.copy(assessments = snapshot.assessments + archived), semesterID, includeNotes = true)
        val item = Json.parseToJsonElement(completed).jsonObject.getValue("semesters").jsonArray.single().jsonObject
            .getValue("existingAssessments").jsonArray.single().jsonObject
        assertEquals("completed", item.getValue("state").jsonPrimitive.content)
        assertEquals("Calculator permitted", item.getValue("notes").jsonPrimitive.content)
        assertFalse(item.containsKey("completedAt"))
        assertFalse(completed.contains("Archived private exam"))
        assertFalse(completed.contains("Private personal task"))
        assertFalse(completed.contains("Private habit"))
    } }

    @Test fun suppliedCrossPlatformJsonFixtureImportsAndProjectsExamOnce() = runBlocking { fixture { repository, _ ->
        val json = requireNotNull(javaClass.getResourceAsStream("/assistant/two-course-exams.json")) {
            "The portable academic proposal test resource is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val plan = AcademicPlanCodec.decode(json)
        val receipt = repository.apply(repository.preview(plan))
        val snapshot = repository.state.value.snapshot
        assertEquals(plan.assessments.size, snapshot.assessments.size)
        assertEquals(plan.preparationTasks.size, snapshot.tasks.size)
        assertEquals(snapshot.assessments.size, snapshot.assessments.map { it.id }.distinct().size)
        assertEquals(ReceiptState.APPLIED, receipt.state)
        assertTrue(snapshot.tasks.all { it.sourceAssessmentID in snapshot.assessments.map { assessment -> assessment.id } })
        assertTrue(snapshot.assessments.none { it.completed })
    } }
}
