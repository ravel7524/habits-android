package ch.habits.android.data

import ch.habits.android.core.*
import ch.habits.android.semester.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class BoardImportRepositoryTest {
    private val semesterID = "a2000000-0000-0000-0000-000000000001"
    private val courseID = "a2000000-0000-0000-0000-000000000002"
    private val assessmentID = "a2000000-0000-0000-0000-000000000003"
    private val statusID = "a2000000-0000-0000-0000-000000000004"
    private val otherCourseID = "a2000000-0000-0000-0000-000000000005"
    private fun document() = BoardSemester(id = semesterID, title = "Autumn 2026", weekCount = 3,
        startDate = "2026-09-14", endDate = "2026-10-04", timeZoneIdentifier = "Europe/Zurich",
        statuses = listOf(BoardStatus(statusID, "Reviewed", "#37A269")), courses = listOf(
            BoardCourse(courseID, "Statistics", activeWeeks = listOf(1, 3), moduleCode = "STA101",
                scheduleNote = "Bring calculator", entries = mapOf(1 to BoardWeekEntry(statusID, "Exercises 1–3")),
                assessments = listOf(BoardAssessment(assessmentID, "Statistics exam", "exam", "2026-10-09", "09:30", "Calculator permitted")))))
    private fun raw(board: BoardSemester = document(), selected: Set<String>? = null) = BoardImportRequest.rawFile(board, selected)
    private suspend fun fixture(block: suspend (WorkspaceRepository, File) -> Unit) {
        val directory = Files.createTempDirectory("board-repository-test-").toFile()
        val file = File(directory, "workspace.json")
        try { block(WorkspaceRepository(file), file) } finally { directory.deleteRecursively() }
    }
    private suspend fun save(repository: WorkspaceRepository, board: BoardSemester = document()) = repository.applyBoard(repository.previewBoard(raw(board)))
    private fun assistant(repository: WorkspaceRepository, board: BoardSemester): BoardImportRequest = BoardImportRequest(
        BoardProposal(proposalID = UUID.randomUUID().toString(), source = "Assistant review", semester = board,
            expectedBoardDigest = SemesterBoardExport.digest(repository.state.value.snapshot, board.id)))
    private fun legacyBytes(snapshot: WorkspaceSnapshot): ByteArray {
        val root = WorkspaceRepository.workspaceJson.parseToJsonElement(WorkspaceRepository.workspaceJson.encodeToString(snapshot.copy(schemaVersion = 1))).jsonObject.toMutableMap()
        val fields = mapOf("semesters" to setOf("id", "title", "startDate", "weekCount", "timeZoneIdentifier", "archived"),
            "courses" to setOf("id", "semesterID", "name", "archived"),
            "assessments" to setOf("id", "semesterID", "courseID", "title", "kind", "dueDate", "dueTime", "notes", "completed", "completedAt", "archived"),
            "tasks" to setOf("id", "title", "kind", "notes", "dueDate", "dueTime", "sourceZone", "priority", "minutes", "completed", "completedAt", "archived", "sourceAssessmentID", "createdAt", "updatedAt"))
        fun trimmedRows(value: JsonElement, allowed: Set<String>) = JsonArray(value.jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key in allowed }) })
        fields.forEach { (key, allowed) -> root[key] = trimmedRows(root.getValue(key), allowed) }
        root["receipts"] = JsonArray(root.getValue("receipts").jsonArray.map { value ->
            JsonObject(value.jsonObject.toMutableMap().apply {
                fields.forEach { (key, allowed) -> val receiptKey = "created" + key.replaceFirstChar { it.uppercase() }; this[receiptKey]?.let { this[receiptKey] = trimmedRows(it, allowed) } }
            })
        })
        root.remove("boardReceipts")
        return JsonObject(root).toString().toByteArray()
    }

    @Test fun previewAndCancelLeaveBytesAndSourcesUntouched() = runBlocking { fixture { repository, file ->
        val preview = repository.previewBoard(raw())
        assertEquals(BoardImportCounts(1, 0, 1, 0, 1, 0), preview.counts)
        assertEquals(3, preview.changes.size)
        assertTrue(preview.changes.single { it.recordType == "course" }.fields.any { it.field == "entries" })
        assertEquals(WorkspaceSnapshot(), repository.state.value.snapshot)
        assertFalse(file.exists()); assertFalse(repository.backupFile.exists())
        assertTrue(file.parentFile.listFiles().orEmpty().isEmpty())
    } }

    @Test fun importAndReceiptCommitAtomicallyAndReload() = runBlocking { fixture { repository, file ->
        val receipt = save(repository)
        assertTrue(receipt.rawFile)
        assertTrue(receipt.payload.contains("habits.local-board-import"))
        assertEquals(1L, repository.state.value.snapshot.revision)
        val loaded = WorkspaceRepository(file).state.value
        assertFalse(loaded.recoveryRequired)
        assertEquals(repository.state.value.snapshot, loaded.snapshot)
        assertEquals(listOf(receipt), loaded.snapshot.boardReceipts)
        assertEquals(courseID, loaded.snapshot.courses.single().externalID)
        assertNotEquals(courseID, loaded.snapshot.courses.single().id)
    } }

    @Test fun scopedSourceIDsCanRepeatAndLegacyExamCanEqualCourseID() = runBlocking { fixture { repository, _ ->
        val first = document().courses.single().copy(assessments = listOf(BoardAssessment(courseID, "Legacy exam", "exam", "2026-10-09")))
        val second = first.copy(id = otherCourseID, name = "Economics")
        save(repository, document().copy(courses = listOf(first, second)))
        val snapshot = repository.state.value.snapshot
        assertEquals(2, snapshot.assessments.size)
        assertEquals(listOf(courseID, courseID), snapshot.assessments.map { it.externalID })
        assertEquals(2, snapshot.assessments.map { it.id }.distinct().size)
        save(repository, document().copy(id = UUID.randomUUID().toString(), title = "Other semester", courses = listOf(first)))
        assertEquals(3, repository.state.value.snapshot.courses.size)
    } }

    @Test fun omittedFieldsPreserveNotesStateAndUnselectedRecordsWhileExplicitNullClears() = runBlocking { fixture { repository, _ ->
        val second = document().courses.single().copy(id = otherCourseID, name = "Economics", assessments = emptyList())
        save(repository, document().copy(courses = document().courses + second))
        val oldAssessment = repository.state.value.snapshot.assessments.single()
        repository.setAssessmentState(oldAssessment, "dismissed")
        val source = document().courses.single().copy(name = "Advanced statistics", moduleCode = null,
            scheduleNote = null, entries = mapOf(1 to BoardWeekEntry(null, "", providedFields = setOf("statusID"))),
            assessments = listOf(document().courses.single().assessments.single().copy(notes = "", state = "pending",
                providedFields = BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS - setOf("notes", "state", "completedAt"))),
            providedFields = BoardCourse.FIELDS - "scheduleNote")
        val update = document().copy(courses = listOf(source, second.copy(name = "Must stay Economics")))
        val preview = repository.previewBoard(raw(update, setOf(courseID)))
        assertEquals(1, preview.counts.updatedCourses)
        repository.applyBoard(preview)
        val snapshot = repository.state.value.snapshot
        val course = snapshot.courses.single { it.externalID == courseID }
        assertNull(course.moduleCode)
        assertEquals("Bring calculator", course.scheduleNote)
        assertNull(course.entries.getValue(1).statusID)
        assertEquals("Exercises 1–3", course.entries.getValue(1).notes)
        assertEquals("Economics", snapshot.courses.single { it.externalID == otherCourseID }.name)
        assertEquals("dismissed", snapshot.assessments.single().recordedState)
        assertEquals("Calculator permitted", snapshot.assessments.single().notes)
    } }

    @Test fun unknownFieldsRecurrenceAndFoundationTimesRoundTripLocallyAndDoNotShare() = runBlocking { fixture { repository, _ ->
        val unknown = JsonObject(mapOf("legacyHelper" to JsonObject(mapOf("private" to JsonPrimitive("retained")))))
        val row = document().courses.single().assessments.single().copy(state = "completed", completedAt = 800000000.125,
            recurrence = JsonObject(mapOf("seriesID" to JsonPrimitive("a2000000-0000-0000-0000-000000000006"),
                "scheduledDate" to JsonPrimitive("2026-10-09"), "endDate" to JsonPrimitive("2026-10-09"),
                "intervalWeeks" to JsonPrimitive(1), "occurrenceIndex" to JsonPrimitive(0), "isException" to JsonPrimitive(false),
                "exceptions" to JsonArray(listOf(JsonPrimitive("2026-10-09"))))),
            reminderDays = listOf(7, 1), snoozedUntil = 800000010.25, unknownFields = unknown,
            providedFields = BoardAssessment.FIELDS)
        val board = document().copy(unknownFields = unknown,
            courses = listOf(document().courses.single().copy(sourceURL = "https://university.example/private", unknownFields = unknown, assessments = listOf(row))))
        save(repository, board)
        val snapshot = repository.state.value.snapshot
        val exported = SemesterBoardExport.document(snapshot, semesterID, includeCompatibilityFields = true)
        assertEquals(unknown, exported.unknownFields)
        val result = exported.courses.single().assessments.single()
        assertEquals(row.completedAt, result.completedAt)
        assertEquals(row.snoozedUntil, result.snoozedUntil)
        assertEquals(row.reminderDays, result.reminderDays)
        assertEquals(row.recurrence, result.recurrence)
        assertEquals(unknown, result.unknownFields)
        val shared = SemesterBoardExport.document(snapshot, semesterID, includeNotes = false, includeWeekProgress = false)
        val text = SemesterBoardCodec.encode(shared)
        assertFalse(text.contains("legacyHelper")); assertFalse(text.contains("private")); assertFalse(text.contains("reminderDays"))
        assertFalse(text.contains("snoozedUntil")); assertFalse(text.contains("recurrence")); assertFalse(text.contains("notes")); assertFalse(text.contains("scheduleNote"))
        assertTrue(shared.courses.single().entries.isEmpty())
    } }

    @Test fun rawIdentityIsStableForContentSelectionAndZone() {
        assertEquals(raw().proposal.proposalID, raw().proposal.proposalID)
        assertEquals(raw().proposal.proposalID, raw(document().copy(id = semesterID.uppercase(),
            providedFields = document().providedFields.reversed().toSet())).proposal.proposalID)
        assertNotEquals(raw().proposal.proposalID, raw(selected = setOf(courseID)).proposal.proposalID)
        assertNotEquals(raw().proposal.proposalID, BoardImportRequest.rawFile(document(), timeZoneIdentifier = "UTC").proposal.proposalID)
    }

    @Test fun rawAcademicNamesAndLongNotesStayUntrimmedWhileTasksKeepStrictLimits() = runBlocking { fixture { repository, _ ->
        val longNote = "Recorded source note. ".repeat(300)
        val course = document().courses.single().copy(name = "  " + "Long course name ".repeat(20) + "  ",
            scheduleNote = longNote, entries = mapOf(1 to BoardWeekEntry(statusID, longNote)),
            assessments = listOf(document().courses.single().assessments.single().copy(notes = longNote)))
        save(repository, document().copy(title = " Autumn 2026 ", courses = listOf(course)))
        assertEquals(course.name, repository.state.value.snapshot.courses.single().name)
        assertEquals(longNote, repository.state.value.snapshot.assessments.single().notes)
        assertFailsWith<WorkspaceException> { repository.saveTask(WorkTask(title = "Task", notes = longNote)) }
    } }

    @Test fun staleRawCompletionTimestampRemainsLocalAndDoesNotChangeRecordedState() = runBlocking { fixture { repository, _ ->
        val source = document().courses.single().assessments.single().copy(state = "pending", completedAt = 800000000.125)
        save(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(source)))))
        val snapshot = repository.state.value.snapshot
        assertTrue(snapshot.assessments.single().pending)
        assertNull(snapshot.assessments.single().completedAt)
        assertEquals(source.completedAt, SemesterBoardExport.document(snapshot, semesterID, includeCompatibilityFields = true)
            .courses.single().assessments.single().completedAt)
        assertNull(SemesterBoardExport.document(snapshot, semesterID).courses.single().assessments.single().completedAt)
    } }

    @Test fun stalePreviewConflictsWithoutWritingAndConsumedReplayKeepsLaterEdits() = runBlocking { fixture { repository, file ->
        val request = raw()
        val stale = repository.previewBoard(request)
        repository.saveTask(WorkTask(title = "Unrelated work"))
        val bytes = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.applyBoard(stale) }
        assertContentEquals(bytes, file.readBytes())
        val receipt = repository.applyBoard(repository.previewBoard(request))
        repository.setAssessmentState(repository.state.value.snapshot.assessments.single(), "dismissed")
        val after = file.readBytes()
        assertEquals(receipt, repository.applyBoard(repository.previewBoard(request)))
        assertContentEquals(after, file.readBytes())
        assertTrue(repository.state.value.snapshot.assessments.single().dismissed)
    } }

    @Test fun assistantRequiresCurrentOpaqueDigestAndMatchesDesktopFingerprint() = runBlocking { fixture { repository, file ->
        val first = BoardProposal(proposalID = UUID.randomUUID().toString(), source = "Selected timetable", semester = document())
        val firstReceipt = repository.applyBoard(repository.previewBoard(BoardImportRequest(first)))
        assertFalse(firstReceipt.rawFile)
        assertEquals(boardSha256(SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(first))), firstReceipt.digest)
        val stale = first.copy(proposalID = UUID.randomUUID().toString(), expectedBoardDigest = "0".repeat(64))
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.previewBoard(BoardImportRequest(stale)) }
        assertContentEquals(before, file.readBytes())
        val current = stale.copy(expectedBoardDigest = SemesterBoardExport.digest(repository.state.value.snapshot, semesterID))
        val preview = repository.previewBoard(BoardImportRequest(current))
        repository.setAssessmentState(repository.state.value.snapshot.assessments.single(), "completed")
        assertFailsWith<WorkspaceException> { repository.applyBoard(preview) }
    } }

    @Test fun digestIncludesPrivateAcademicValuesButExcludesPlannerAndProfile() = runBlocking { fixture { repository, _ ->
        save(repository)
        val before = repository.state.value.snapshot
        val digest = SemesterBoardExport.digest(before, semesterID)
        assertEquals(digest, SemesterBoardExport.digest(before.copy(tasks = listOf(WorkTask(title = "Private planner")),
            preferences = Preferences(userName = "Private profile")), semesterID))
        assertNotEquals(digest, SemesterBoardExport.digest(before.copy(courses = before.courses.map {
            it.copy(compatibilityFields = JsonObject(mapOf("retained" to JsonPrimitive("changed")))) }), semesterID))
        val large = before.copy(courses = before.courses.map { it.copy(compatibilityFields =
            JsonObject(mapOf("largeRetainedField" to JsonPrimitive("x".repeat(2 * 1024 * 1024 + 1))))) })
        assertEquals(64, SemesterBoardExport.digest(large, semesterID).length)
    } }

    @Test fun undoRestoresExistingValuesArchivesOnlyNewRecordsAndReplayStaysConsumed() = runBlocking { fixture { repository, _ ->
        save(repository)
        val original = repository.state.value.snapshot
        val changed = document().copy(title = "Changed semester", courses = listOf(
            document().courses.single().copy(moduleCode = "NEW"),
            BoardCourse(otherCourseID, "New course", assessments = listOf(BoardAssessment(assessmentID, "New exam", "exam", "2026-10-10")))))
        val receipt = save(repository, changed)
        val undone = repository.undoBoard(receipt.proposalID)
        assertEquals(ReceiptState.UNDONE, undone.state)
        val current = repository.state.value.snapshot
        assertEquals(original.semesters, current.semesters)
        assertEquals(original.courses.single(), current.courses.single { it.id == original.courses.single().id })
        assertTrue(current.courses.single { it.externalID == otherCourseID }.archived)
        assertTrue(current.assessments.single { it.courseID != original.courses.single().id }.archived)
        assertEquals(undone, repository.applyBoard(repository.previewBoard(raw(changed))))
    } }

    @Test fun laterWeekProgressOrPreparationBlocksUndoAndStatusChangesPreserveReadonlyNotes() = runBlocking { fixture { repository, file ->
        val receipt = save(repository)
        val snapshot = repository.state.value.snapshot
        val course = snapshot.courses.single()
        val semester = snapshot.semesters.single()
        repository.setWeekStatus(semester.id, course.id, 1, null, course.entries[1], semester.statuses)
        assertEquals("Exercises 1–3", repository.state.value.snapshot.courses.single().entries.getValue(1).notes)
        val bytes = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.setWeekStatus(semester.id, course.id, 1, statusID, course.entries[1], semester.statuses) }
        assertFailsWith<WorkspaceException> { repository.undoBoard(receipt.proposalID) }
        assertContentEquals(bytes, file.readBytes())
    } }

    @Test fun independentWeeklyPreparationBlocksNewCourseUndoWithoutChangingProgress() = runBlocking { fixture { repository, _ ->
        val receipt = save(repository)
        val course = repository.state.value.snapshot.courses.single()
        repository.saveTask(WorkTask(title = "Review week 2", minutes = 0, sourceCourseID = course.id, sourceWeek = 2))
        repository.completeTask(repository.state.value.snapshot.tasks.single().id)
        assertFalse(repository.state.value.snapshot.assessments.single().completed)
        assertEquals(document().courses.single().entries.getValue(1).statusID, repository.state.value.snapshot.courses.single().entries.getValue(1).statusID)
        assertFailsWith<WorkspaceException> { repository.undoBoard(receipt.proposalID) }
        assertFailsWith<WorkspaceException> { repository.saveTask(WorkTask(title = "Dangling", sourceCourseID = UUID.randomUUID().toString(), sourceWeek = 2)) }
    } }

    @Test fun dismissalAndPreparationCompletionAreIndependent() = runBlocking { fixture { repository, _ ->
        save(repository)
        val assessment = repository.state.value.snapshot.assessments.single()
        repository.saveTask(WorkTask(title = "Exam review", minutes = 0, sourceAssessmentID = assessment.id))
        repository.setAssessmentState(assessment, "dismissed")
        repository.completeTask(repository.state.value.snapshot.tasks.single().id)
        assertEquals("dismissed", repository.state.value.snapshot.assessments.single().recordedState)
        repository.setAssessmentState(repository.state.value.snapshot.assessments.single(), "pending")
        assertNull(repository.state.value.snapshot.assessments.single().completedAt)
    } }

    @Test fun fallbackChoicesPersistWithoutSeedingOtherWeeks() = runBlocking { fixture { repository, _ ->
        save(repository, document().copy(statuses = emptyList(), courses = listOf(document().courses.single().copy(entries = emptyMap()))))
        val semester = repository.state.value.snapshot.semesters.single()
        val course = repository.state.value.snapshot.courses.single()
        val status = SemesterBoardProjection.statuses(semester).first()
        repository.setWeekStatus(semester.id, course.id, 2, status.id, null, emptyList())
        assertEquals(4, repository.state.value.snapshot.semesters.single().statuses.size)
        assertEquals(setOf(2), repository.state.value.snapshot.courses.single().entries.keys)
    } }

    @Test fun importedDSTGapRetainsCivilTimeAndNoDateRangeIsAllowed() = runBlocking { fixture { repository, file ->
        val row = BoardAssessment(assessmentID, "Gap exam", "exam", "2026-03-29", "02:30")
        save(repository, document().copy(startDate = null, endDate = null,
            courses = listOf(document().courses.single().copy(assessments = listOf(row)))))
        assertEquals("02:30", repository.state.value.snapshot.assessments.single().dueTime)
        assertTrue(SemesterBoardProjection.clockNeedsReview(repository.state.value.snapshot.assessments.single(), repository.state.value.snapshot.semesters.single()))
        assertFalse(WorkspaceRepository(file).state.value.recoveryRequired)
    } }

    @Test fun malformedAndFutureStoresRemainPreservedAndV1MigrationDoesNotWriteUntilMutation() = runBlocking { fixture { _, file ->
        val legacy = WorkspaceSnapshot(schemaVersion = 1, tasks = listOf(WorkTask(title = "Existing task")))
        val legacyBytes = legacyBytes(legacy)
        file.writeBytes(legacyBytes)
        val repository = WorkspaceRepository(file)
        assertEquals(2, repository.state.value.snapshot.schemaVersion)
        assertFalse(repository.state.value.recoveryRequired)
        repository.previewBoard(raw())
        assertContentEquals(legacyBytes, file.readBytes())
        assertFalse(repository.backupFile.exists()); assertFalse(repository.schemaV1BackupFile.exists())
        repository.saveTask(WorkTask(title = "Deliberate edit"))
        assertContentEquals(legacyBytes, repository.backupFile.readBytes())
        assertContentEquals(legacyBytes, repository.schemaV1BackupFile.readBytes())
        repository.saveTask(WorkTask(title = "Another edit"))
        assertContentEquals(legacyBytes, repository.schemaV1BackupFile.readBytes())
        val future = WorkspaceRepository.workspaceJson.encodeToString(legacy.copy(schemaVersion = 8)).toByteArray()
        file.writeBytes(future)
        val recovery = WorkspaceRepository(file)
        assertTrue(recovery.state.value.recoveryRequired)
        assertFailsWith<WorkspaceException> { recovery.previewBoard(raw()) }
        assertContentEquals(future, recovery.exportRawData())
    } }

    @Test fun legacyReceiptAndSourceCoordinatesSurviveMigrationAndMetadataEnrichment() = runBlocking { fixture { repository, file ->
        val plan = AcademicPlan(proposalID = UUID.randomUUID().toString(), source = "Legacy timetable",
            semester = PlanSemester(semesterID, "Autumn 2026", "2026-09-14", 3, "Europe/Zurich"),
            courses = listOf(PlanCourse(courseID, "Statistics")),
            assessments = listOf(PlanAssessment(assessmentID, courseID, "Statistics exam", "exam", "2026-10-09", "09:30", "Calculator permitted")), preparationTasks = emptyList())
        val oldReceipt = repository.apply(repository.preview(plan))
        file.writeBytes(legacyBytes(repository.state.value.snapshot))
        val migrated = WorkspaceRepository(file)
        assertEquals(oldReceipt, migrated.apply(migrated.preview(plan)))
        save(migrated)
        assertEquals(courseID, migrated.state.value.snapshot.courses.single().id)
        assertEquals(assessmentID, migrated.state.value.snapshot.assessments.single().id)
        assertEquals(oldReceipt, migrated.apply(migrated.preview(plan)))
        assertFailsWith<WorkspaceException> { migrated.undo(plan.proposalID) }
    } }

    @Test fun untouchedLegacyReceiptUndoRemainsAvailableAfterMigration() = runBlocking { fixture { repository, file ->
        val plan = AcademicPlan(proposalID = UUID.randomUUID().toString(), source = "Legacy timetable",
            semester = PlanSemester(semesterID, "Autumn 2026", "2026-09-14", 3, "Europe/Zurich"), courses = listOf(PlanCourse(courseID, "Statistics")), assessments = emptyList(), preparationTasks = emptyList())
        repository.apply(repository.preview(plan))
        val legacyBytes = legacyBytes(repository.state.value.snapshot)
        file.writeBytes(legacyBytes)
        val migrated = WorkspaceRepository(file)
        assertEquals(ReceiptState.UNDONE, migrated.undo(plan.proposalID).state)
        assertContentEquals(legacyBytes, migrated.schemaV1BackupFile.readBytes())
        assertTrue(migrated.state.value.snapshot.semesters.single().archived)
    } }

    @Test fun newAssistantClocksUseInheritedZoneAndRejectBothDSTGapAndOverlap() = runBlocking {
        listOf("2026-03-29", "2026-10-25").forEach { date -> fixture { repository, file ->
            save(repository)
            val added = BoardAssessment(UUID.randomUUID().toString(), "Added exam", "exam", date, "02:30")
            val board = document().copy(timeZoneIdentifier = null, providedFields = BoardSemester.FIELDS - "timeZoneIdentifier",
                courses = listOf(document().courses.single().copy(assessments = listOf(added))))
            val before = file.readBytes()
            val error = assertFailsWith<WorkspaceException> { repository.previewBoard(assistant(repository, board)) }
            assertTrue(error.message.orEmpty().contains("effective semester timezone"))
            assertContentEquals(before, file.readBytes())
            assertEquals(1, repository.state.value.snapshot.assessments.size)
        } }
    }

    @Test fun changedAssistantClocksUseInheritedZoneAndRejectBothDSTGapAndOverlap() = runBlocking {
        listOf("2026-03-28" to "2026-03-29", "2026-10-24" to "2026-10-25").forEach { (original, changed) -> fixture { repository, file ->
            val row = document().courses.single().assessments.single().copy(dueDate = original, dueTime = "02:30")
            save(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(row)))))
            val board = document().copy(timeZoneIdentifier = null, providedFields = BoardSemester.FIELDS - "timeZoneIdentifier",
                courses = listOf(document().courses.single().copy(assessments = listOf(row.copy(dueDate = changed)))))
            val before = file.readBytes()
            assertFailsWith<WorkspaceException> { repository.previewBoard(assistant(repository, board)) }
            assertContentEquals(before, file.readBytes())
            assertEquals(original, repository.state.value.snapshot.assessments.single().dueDate)
        } }
    }

    @Test fun titleAndStateOnlyAssistantUpdatePreservesUnchangedRawAmbiguousClock() = runBlocking {
        listOf(true, false).forEach { includeZone -> fixture { repository, _ ->
            val row = document().courses.single().assessments.single().copy(dueDate = "2026-03-29", dueTime = "02:30")
            save(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(row)))))
            val board = document().copy(timeZoneIdentifier = if (includeZone) "Europe/Zurich" else null,
                providedFields = if (includeZone) BoardSemester.FIELDS else BoardSemester.FIELDS - "timeZoneIdentifier",
                courses = listOf(document().courses.single().copy(assessments = listOf(row.copy(title = "Retitled source exam", state = "dismissed")))))
            val receipt = repository.applyBoard(repository.previewBoard(assistant(repository, board)))
            assertEquals("02:30", repository.state.value.snapshot.assessments.single().dueTime)
            assertEquals("2026-03-29", repository.state.value.snapshot.assessments.single().dueDate)
            assertEquals("dismissed", repository.state.value.snapshot.assessments.single().recordedState)
            repository.undoBoard(receipt.proposalID)
            assertTrue(repository.state.value.snapshot.assessments.single().pending)
        } }
    }

    @Test fun assistantZoneChangeChecksAffectedClocksEvenForOmittedAssessmentRecords() = runBlocking { fixture { repository, file ->
        val row = document().courses.single().assessments.single().copy(dueDate = "2026-03-29", dueTime = "02:30")
        save(repository, document().copy(timeZoneIdentifier = "UTC",
            courses = listOf(document().courses.single().copy(assessments = listOf(row)))))
        val board = document().copy(timeZoneIdentifier = "Europe/Zurich", courses = emptyList())
        val before = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.previewBoard(assistant(repository, board)) }
        assertContentEquals(before, file.readBytes())
        assertEquals("UTC", repository.state.value.snapshot.semesters.single().timeZoneIdentifier)
        val valid = board.copy(timeZoneIdentifier = "Europe/London")
        repository.applyBoard(repository.previewBoard(assistant(repository, valid)))
        assertEquals("Europe/London", repository.state.value.snapshot.semesters.single().timeZoneIdentifier)
        assertEquals(row.dueDate, repository.state.value.snapshot.assessments.single().dueDate)
    } }

    @Test fun restoringMissingOrCorruptPrimaryKeepsExactSchemaOneBackupAfterLaterEdits() = runBlocking {
        listOf(false, true).forEach { corruptPrimary -> fixture { _, file ->
            val bytes = legacyBytes(WorkspaceSnapshot(tasks = listOf(WorkTask(title = "Validated legacy backup"))))
            val backup = File(file.absolutePath + ".backup")
            backup.writeBytes(bytes)
            if (corruptPrimary) file.writeText("Preserve corrupt primary")
            val repository = WorkspaceRepository(file)
            assertTrue(repository.state.value.recoveryRequired)
            assertTrue(repository.state.value.backupAvailable)
            repository.restoreBackup()
            assertFalse(repository.state.value.recoveryRequired)
            assertEquals(2, repository.state.value.snapshot.schemaVersion)
            assertContentEquals(bytes, repository.schemaV1BackupFile.readBytes())
            assertContentEquals(bytes, backup.readBytes())
            repository.saveTask(WorkTask(title = "First later edit"))
            repository.saveTask(WorkTask(title = "Second later edit"))
            assertContentEquals(bytes, repository.schemaV1BackupFile.readBytes())
            if (corruptPrimary) assertEquals("Preserve corrupt primary", file.parentFile.listFiles().orEmpty()
                .single { it.name.startsWith(file.name + ".recovery-") }.readText())
        } }
    }

    @Test fun assistantCompletionTimestampRequiresResolvedCompletedStateAndCanInheritIt() = runBlocking {
        listOf("pending", "dismissed", "completed").forEach { inheritedState -> fixture { repository, file ->
            val row = document().courses.single().assessments.single().copy(state = inheritedState,
                completedAt = if (inheritedState == "completed") 800000000.0 else null)
            save(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(row)))))
            val changed = row.copy(title = "Reviewed completion timestamp", state = "pending", completedAt = 800000001.125,
                providedFields = row.providedFields - "state")
            val request = assistant(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(changed)))))
            val before = file.readBytes()
            if (inheritedState == "completed") {
                repository.applyBoard(repository.previewBoard(request))
                assertEquals("completed", repository.state.value.snapshot.assessments.single().recordedState)
                assertEquals(SemesterBoardCodec.referenceDateToInstant(800000001.125), repository.state.value.snapshot.assessments.single().completedAt)
            } else {
                assertFailsWith<WorkspaceException> { repository.previewBoard(request) }
                assertContentEquals(before, file.readBytes())
                assertEquals(inheritedState, repository.state.value.snapshot.assessments.single().recordedState)
            }
        } }
    }

    @Test fun assistantDateMoveMarksOneMaterializedExceptionReviewsItAndSupportsGuardedUndo() = runBlocking { fixture { repository, file ->
        val seriesID = "a2000000-0000-0000-0000-000000000006"
        fun recurrence(date: String, index: Int) = JsonObject(mapOf("seriesID" to JsonPrimitive(seriesID),
            "scheduledDate" to JsonPrimitive(date), "endDate" to JsonPrimitive("2026-10-16"),
            "intervalWeeks" to JsonPrimitive(1), "occurrenceIndex" to JsonPrimitive(index), "isException" to JsonPrimitive(false)))
        val first = document().courses.single().assessments.single().copy(recurrence = recurrence("2026-10-09", 0),
            providedFields = BoardAssessment.FIELDS)
        val sibling = first.copy(id = otherCourseID, title = "Second occurrence", dueDate = "2026-10-16", recurrence = recurrence("2026-10-16", 1))
        save(repository, document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(first, sibling)))))
        val baseline = repository.state.value.snapshot
        val siblingLocal = baseline.assessments.single { it.externalID == otherCourseID }
        val moved = first.copy(dueDate = "2026-10-10", recurrence = null,
            providedFields = BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS - "recurrence")
        val proposal = document().copy(courses = listOf(document().courses.single().copy(assessments = listOf(moved))))
        val preview = repository.previewBoard(assistant(repository, proposal))
        assertTrue(preview.changes.single { it.recordType == "assessment" }.fields.any { it.field == "recurrence" && it.after.orEmpty().contains("true") })
        assertTrue(preview.notes.any { it.contains("materialized exceptions") })
        val receipt = repository.applyBoard(preview)
        val changed = repository.state.value.snapshot.assessments.single { it.externalID == assessmentID }
        assertEquals("2026-10-10", changed.dueDate)
        val changedRecurrence = assertNotNull(changed.recurrence).jsonObject
        assertEquals(JsonPrimitive(true), changedRecurrence["isException"])
        assertEquals(JsonPrimitive("2026-10-09"), changedRecurrence["scheduledDate"])
        assertEquals(JsonPrimitive(seriesID), changedRecurrence["seriesID"])
        assertEquals(siblingLocal, repository.state.value.snapshot.assessments.single { it.id == siblingLocal.id })
        repository.undoBoard(receipt.proposalID)
        assertEquals(baseline.assessments, repository.state.value.snapshot.assessments)
        val laterReceipt = repository.applyBoard(repository.previewBoard(assistant(repository, proposal)))
        repository.setAssessmentState(repository.state.value.snapshot.assessments.single { it.externalID == assessmentID }, "completed")
        val beforeUndo = file.readBytes()
        assertFailsWith<WorkspaceException> { repository.undoBoard(laterReceipt.proposalID) }
        assertContentEquals(beforeUndo, file.readBytes())
        assertEquals(siblingLocal, repository.state.value.snapshot.assessments.single { it.id == siblingLocal.id })
    } }

    @Test fun importedStatusOrderReplacesIncludedOrderKeepsOmittedStatusesAndUndoRestoresIt() = runBlocking { fixture { repository, _ ->
        val a = document().statuses.single()
        val b = BoardStatus(otherCourseID, "Revisit", "#EAAB46")
        val c = BoardStatus(assessmentID, "Working", "#4A86DE")
        save(repository, document().copy(statuses = listOf(a, b, c)))
        val preview = repository.previewBoard(raw(document().copy(statuses = listOf(b, a))))
        assertEquals(1, preview.counts.updatedSemesters)
        assertTrue(preview.changes.single { it.recordType == "semester" }.fields.any { it.field == "statuses" })
        val receipt = repository.applyBoard(preview)
        assertEquals(listOf(b.id, a.id, c.id), repository.state.value.snapshot.semesters.single().statuses.map { it.id })
        repository.undoBoard(receipt.proposalID)
        assertEquals(listOf(a.id, b.id, c.id), repository.state.value.snapshot.semesters.single().statuses.map { it.id })
    } }
}
