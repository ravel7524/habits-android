package ch.habits.android.desktop

import ch.habits.android.core.AcademicPlanCodec
import ch.habits.android.data.BoardImportReceipt
import ch.habits.android.data.SourceClockResolution
import ch.habits.android.semester.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class DesktopBoardConnectionTest {
    private val deviceID = "99999999-9999-4999-8999-999999999999"
    private val serverDigest = "b".repeat(64)
    private val capturedAt = "2026-10-06T12:00:00Z"

    private fun board() = BoardProposal(proposalID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", source = "Synthetic reviewed timetable",
        semester = BoardSemester(id = "22222222-2222-4222-8222-222222222222", title = "Synthetic board", startDate = "2026-09-14",
            endDate = "2026-12-18", timeZoneIdentifier = "Europe/Zurich",
            statuses = listOf(BoardStatus("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Recorded done", "#28A35A")),
            courses = listOf(BoardCourse(id = "33333333-3333-4333-8333-333333333333", name = "Synthetic statistics", activeWeeks = listOf(1, 14, 52),
                entries = mapOf(1 to BoardWeekEntry("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Recorded week note")),
                scheduleNote = "Recorded schedule note", assessments = listOf(BoardAssessment(id = "44444444-4444-4444-8444-444444444444",
                    title = "Synthetic exam", kind = "exam", dueDate = "2026-12-16", notes = "Recorded assessment note",
                    providedFields = setOf("id", "title", "kind", "dueDate", "notes", "state")))))))

    private fun academic() = AcademicPlanCodec.decode(requireNotNull(javaClass.getResourceAsStream("/assistant/two-course-exams.json")).bufferedReader().use { it.readText() })
    private fun credential(supported: Boolean = false, delivery: DesktopDelivery? = null) = DesktopCredential(baseURL = "https://localhost:8766",
        certificateSHA256 = "a".repeat(64), deviceID = deviceID, deviceSecret = "0".repeat(64),
        deliveries = listOfNotNull(delivery), boardProposalsSupported = supported)
    private fun delivery(proposal: BoardProposal, reported: String? = null) = DesktopDelivery(proposal.proposalID, serverDigest,
        DesktopSigning.boardFingerprint(proposal), reportedState = reported, proposalFormat = "habits.semester-board-proposal")
    private fun localReceipt(proposal: BoardProposal) = BoardImportReceipt(proposalID = proposal.proposalID,
        digest = DesktopSigning.boardFingerprint(proposal), source = proposal.source, payload = SemesterBoardCodec.encodeProposal(proposal),
        semesterID = proposal.semester.id, appliedAt = capturedAt)
    private fun emptyQueue() = JsonObject(mapOf("proposals" to JsonArray(emptyList())))
    private fun queue(proposal: JsonElement) = JsonObject(mapOf("proposals" to JsonArray(listOf(JsonObject(mapOf(
        "proposal" to proposal, "proposalDigest" to JsonPrimitive(serverDigest), "status" to JsonPrimitive("pending")))))))
    private fun capabilities(body: String, key: DesktopCredential) = JsonObject(mapOf("status" to JsonPrimitive("capabilities_registered"),
        "deviceID" to JsonPrimitive(key.deviceID), "platform" to JsonPrimitive("android"),
        "proposalFormats" to Json.parseToJsonElement(body).jsonObject.getValue("proposalFormats")))
    private fun receipt(body: String, key: DesktopCredential, status: String? = null): JsonObject {
        val request = Json.parseToJsonElement(body).jsonObject
        return JsonObject(mapOf("proposalID" to request.getValue("proposalID"), "proposalDigest" to request.getValue("proposalDigest"),
            "targetDeviceID" to JsonPrimitive(key.deviceID), "status" to (status?.let(::JsonPrimitive) ?: request.getValue("state"))))
    }
    private fun contextReceipt(body: String, key: DesktopCredential) = JsonObject(mapOf("status" to JsonPrimitive("context_shared"),
        "deviceID" to JsonPrimitive(key.deviceID), "capturedAt" to Json.parseToJsonElement(body).jsonObject.getValue("capturedAt")))
    private fun academicContext(semesterID: String = board().semester.id) = JsonObject(mapOf("format" to JsonPrimitive("habits.academic-context"),
        "version" to JsonPrimitive(1), "capturedAt" to JsonPrimitive(capturedAt), "semesters" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive(semesterID), "title" to JsonPrimitive("Synthetic board"), "weekCount" to JsonPrimitive(14),
            "courses" to JsonArray(emptyList()), "existingAssessments" to JsonArray(emptyList()))))))).toString()
    private fun boardContext() = DesktopBoardContext.encode(board().semester, "c".repeat(64), capturedAt = capturedAt)
    private fun legacyContextFor(board: BoardSemester): String = buildJsonObject {
        put("format", "habits.academic-context"); put("version", 1); put("capturedAt", capturedAt)
        put("semesters", JsonArray(listOf(buildJsonObject {
            put("id", board.id); put("title", board.title); put("weekCount", board.weekCount)
            board.startDate?.let { put("startDate", it) }; board.timeZoneIdentifier?.let { put("timeZoneIdentifier", it) }
            put("courses", JsonArray(board.courses.map { buildJsonObject { put("id", it.id); put("name", it.name) } }))
            put("existingAssessments", JsonArray(board.courses.flatMap { course -> course.assessments.map { item -> buildJsonObject {
                put("id", item.id); put("courseID", course.id); put("title", item.title); put("kind", item.kind); put("dueDate", item.dueDate)
                item.dueTime?.let { put("dueTime", it) }; put("notes", item.notes); put("state", item.state)
            } } }))
        })))
    }.toString()

    private class Store(initial: DesktopCredential? = null) : DesktopCredentialStore {
        var bytes = initial?.let { Json.encodeToString(it) }
        var failSave = false
        override fun read(): DesktopCredential? = bytes?.let { Json.decodeFromString<DesktopCredential>(it) }
        override fun save(value: DesktopCredential) {
            if (failSave) throw IOException("Synthetic credential persistence failure")
            bytes = Json.encodeToString(value)
        }
        override fun clear() { bytes = null }
    }

    @Test fun pairingRemainsUnchangedAndRegistersOnlyCapabilitiesWithoutSharingRecords() = runTest {
        val store = Store(credential(delivery = delivery(board())))
        val paths = mutableListOf<String>()
        val connection = DesktopConnection(store) { _, _, method, path, body, key ->
            paths += path
            assertEquals("POST", method)
            val request = Json.parseToJsonElement(body).jsonObject
            when (path) {
                "/v1/assistant/pair" -> {
                    assertNull(key)
                    assertEquals(setOf("pairingCode", "deviceID", "deviceName"), request.keys)
                    JsonObject(mapOf("deviceID" to request.getValue("deviceID"), "deviceSecret" to JsonPrimitive("1".repeat(64))))
                }
                "/v1/assistant/capabilities" -> {
                    assertNotNull(key)
                    assertEquals("habits.desktop-capabilities", request.getValue("format").jsonPrimitive.content)
                    assertEquals(JsonPrimitive(1), request["version"])
                    assertEquals("android", request.getValue("platform").jsonPrimitive.content)
                    assertEquals(setOf("habits.academic-plan", "habits.semester-board-proposal"),
                        request.getValue("proposalFormats").jsonArray.map { it.jsonObject.getValue("format").jsonPrimitive.content }.toSet())
                    capabilities(body, key)
                }
                else -> error("Pairing must not read proposals or share context")
            }
        }
        connection.pair(DesktopInvite("https://localhost:8766", "a".repeat(64), "x".repeat(32)))
        assertEquals(listOf("/v1/assistant/pair", "/v1/assistant/capabilities"), paths)
        assertTrue(connection.state.value.boardProposalsSupported)
        assertEquals("1".repeat(64), store.read()!!.deviceSecret)
        assertNotNull(connection.rememberedProposal(board()), "Credential rotation on the same desktop preserves receipt mappings")
    }

    @Test fun authenticatedLegacy404PreservesPairingAndSkipsBoardRoutesOnRefreshAndSharing() = runTest {
        val store = Store(credential())
        val paths = mutableListOf<String>()
        val connection = DesktopConnection(store) { _, _, _, path, body, key ->
            paths += path
            when (path) {
                "/v1/assistant/pair" -> JsonObject(mapOf("deviceID" to JsonPrimitive(deviceID), "deviceSecret" to JsonPrimitive("0".repeat(64))))
                "/v1/assistant/capabilities" -> { assertNotNull(key); throw DesktopHTTPException(404) }
                "/v1/assistant/proposals" -> queue(Json.parseToJsonElement(AcademicPlanCodec.encode(academic())))
                "/v1/assistant/context" -> contextReceipt(body, key!!)
                else -> error("A legacy desktop must never receive a Board route")
            }
        }
        connection.pair(DesktopInvite("https://localhost:8766", "a".repeat(64), "x".repeat(32)))
        assertTrue(connection.state.value.paired)
        assertNull(connection.state.value.error)
        assertFalse(connection.state.value.boardProposalsSupported)
        connection.refresh()
        assertEquals(1, connection.state.value.proposals.size)
        connection.publishSelectedContext(academicContext(), null)
        assertFalse(paths.any { "board-" in it })
        assertEquals(1, paths.count { it == "/v1/assistant/capabilities" }, "Ordinary legacy refresh does not silently retry capability upgrades")
    }

    @Test fun explicitRefreshUpgradesSeparateQueuesAndPersistsOpaqueDigestBeforeReview() = runTest {
        val proposal = board()
        val store = Store(credential())
        val connection = DesktopConnection(store) { _, _, _, path, body, key ->
            when (path) {
                "/v1/assistant/capabilities" -> capabilities(body, key!!)
                "/v1/assistant/proposals" -> queue(Json.parseToJsonElement(AcademicPlanCodec.encode(academic())))
                "/v1/assistant/board-proposals" -> queue(Json.parseToJsonElement(SemesterBoardCodec.encodeProposal(proposal)))
                else -> error("unexpected route")
            }
        }
        connection.refresh(registerCapabilities = true)
        assertEquals(1, connection.state.value.proposals.size)
        assertEquals(1, connection.state.value.boardProposals.size)
        assertEquals(2, store.read()!!.deliveries.size)
        assertEquals(serverDigest, connection.rememberedProposal(proposal)?.proposalDigest)
        val restarted = DesktopConnection(store) { _, _, _, _, _, _ -> emptyQueue() }
        restarted.refresh()
        assertTrue(restarted.state.value.proposals.isEmpty() && restarted.state.value.boardProposals.isEmpty())
        assertEquals(serverDigest, restarted.rememberedProposal(localReceipt(proposal))?.proposalDigest)
        assertTrue(restarted.needsAcknowledgement(localReceipt(proposal), "applied"))
    }

    @Test fun capabilityFailureIsUnconfirmedAndNeverBlocksLegacyDelivery() = runTest {
        for (failure in listOf(DesktopHTTPException(403), IOException("Synthetic offline discovery"))) {
            val store = Store(credential())
            val connection = DesktopConnection(store) { _, _, _, path, _, _ ->
                when (path) {
                    "/v1/assistant/capabilities" -> throw failure
                    "/v1/assistant/proposals" -> queue(Json.parseToJsonElement(AcademicPlanCodec.encode(academic())))
                    else -> error("Unconfirmed support must not enable a Board route")
                }
            }
            connection.refresh(registerCapabilities = true)
            assertEquals(1, connection.state.value.proposals.size)
            assertFalse(connection.state.value.boardProposalsSupported)
            assertNotNull(connection.state.value.error)
        }
    }

    @Test fun unexpectedCapabilityReceiptLeavesPairingUsableWithoutEnablingBoardDelivery() = runTest {
        val store = Store(credential())
        val connection = DesktopConnection(store) { _, _, _, path, body, key ->
            when (path) {
                "/v1/assistant/pair" -> JsonObject(mapOf("deviceID" to JsonPrimitive(deviceID), "deviceSecret" to JsonPrimitive("0".repeat(64))))
                "/v1/assistant/capabilities" -> JsonObject(capabilities(body, key!!) + ("platform" to JsonPrimitive("ios")))
                "/v1/assistant/proposals" -> emptyQueue()
                else -> error("An invalid capability acknowledgement must not enable a Board route")
            }
        }
        connection.pair(DesktopInvite("https://localhost:8766", "a".repeat(64), "x".repeat(32)))
        assertTrue(connection.state.value.paired)
        assertFalse(connection.state.value.boardProposalsSupported)
        assertNotNull(connection.state.value.error)
        connection.refresh()
        assertTrue(connection.state.value.proposals.isEmpty())
    }

    @Test fun unavailableBoardRouteDowngradesSupportWhileKeepingLegacyDeliveryAndReceipts() = runTest {
        val store = Store(credential(supported = true, delivery = delivery(board())))
        var boardRequests = 0
        val connection = DesktopConnection(store) { _, _, _, path, _, _ ->
            when (path) {
                "/v1/assistant/proposals" -> queue(Json.parseToJsonElement(AcademicPlanCodec.encode(academic())))
                "/v1/assistant/board-proposals" -> { boardRequests++; throw DesktopHTTPException(404) }
                else -> error("unexpected route")
            }
        }
        connection.refresh()
        assertEquals(1, connection.state.value.proposals.size)
        assertFalse(connection.state.value.boardProposalsSupported)
        assertFalse(store.read()!!.boardProposalsSupported)
        assertNotNull(connection.rememberedProposal(localReceipt(board())))
        connection.refresh()
        assertEquals(1, boardRequests, "An unavailable Board route is not retried without explicit support discovery")
    }

    @Test fun boardFetchFailureKeepsPreviousBoardInboxAndStillRefreshesAcademicPlans() = runTest {
        val store = Store(credential(supported = true))
        var offline = false
        val connection = DesktopConnection(store) { _, _, _, path, _, _ ->
            when (path) {
                "/v1/assistant/proposals" -> if (offline) queue(Json.parseToJsonElement(AcademicPlanCodec.encode(academic()))) else emptyQueue()
                "/v1/assistant/board-proposals" -> if (offline) throw IOException("Synthetic Board outage") else queue(Json.parseToJsonElement(SemesterBoardCodec.encodeProposal(board())))
                else -> error("unexpected route")
            }
        }
        connection.refresh()
        offline = true
        connection.refresh()
        assertEquals(1, connection.state.value.proposals.size)
        assertEquals(1, connection.state.value.boardProposals.size)
        assertNotNull(connection.state.value.error)
        assertTrue(connection.state.value.error!!.contains("Previous Board proposals"))
    }

    @Test fun boardReceiptSurvivesOfflineRestartAndUndoCannotRegressToApplied() = runTest {
        val proposal = board()
        val local = localReceipt(proposal)
        val store = Store(credential(supported = true, delivery = delivery(proposal)))
        val offline = DesktopConnection(store) { _, _, _, _, _, _ -> throw IOException("Synthetic offline desktop") }
        assertFailsWith<IOException> { offline.acknowledge(local, "applied") }
        assertTrue(DesktopConnection(store).needsAcknowledgement(local, "applied"))
        var requests = 0
        val transport: (String, String, String, String, String, DesktopCredential?) -> JsonObject = { _, _, _, path, body, key ->
            assertEquals("/v1/assistant/receipt", path)
            assertEquals(serverDigest, Json.parseToJsonElement(body).jsonObject.getValue("proposalDigest").jsonPrimitive.content)
            requests++
            receipt(body, key!!)
        }
        val restarted = DesktopConnection(store, transport)
        restarted.acknowledge(local, "applied")
        restarted.acknowledge(local, "applied")
        assertEquals(1, requests)
        val afterSave = DesktopConnection(store, transport)
        assertFalse(afterSave.needsAcknowledgement(local, "applied"))
        assertTrue(afterSave.needsAcknowledgement(local, "undone"))
        afterSave.acknowledge(local, "undone")
        val afterUndo = DesktopConnection(store, transport)
        assertFalse(afterUndo.needsAcknowledgement(local, "applied"))
        afterUndo.acknowledge(local, "applied")
        assertEquals(2, requests)
        assertEquals("undone", store.read()!!.deliveries.single().reportedState)
    }

    @Test fun alteredLocalReceiptOrImmutableBoardContentIsRejectedWithoutTransportOrLedgerLoss() = runTest {
        val proposal = board()
        val local = localReceipt(proposal)
        val store = Store(credential(supported = true, delivery = delivery(proposal)))
        var requests = 0
        val connection = DesktopConnection(store) { _, _, _, path, _, _ ->
            requests++
            when (path) {
                "/v1/assistant/proposals" -> emptyQueue()
                "/v1/assistant/board-proposals" -> queue(Json.parseToJsonElement(SemesterBoardCodec.encodeProposal(proposal.copy(source = "Changed immutable source"))))
                else -> error("Altered local receipts must never reach transport")
            }
        }
        val before = store.bytes
        assertNull(connection.rememberedProposal(local.copy(digest = "d".repeat(64))))
        assertNull(connection.rememberedProposal(local.copy(rawFile = true)), "Local raw imports never report remote application")
        assertFalse(connection.needsAcknowledgement(local.copy(rawFile = true), "applied"))
        assertNull(connection.rememberedProposal(local.copy(payload = SemesterBoardCodec.encodeProposal(proposal.copy(source = "Changed local source")))))
        assertFails { connection.acknowledge(local.copy(digest = "d".repeat(64)), "applied") }
        assertEquals(0, requests)
        assertFailsWith<IllegalArgumentException> { connection.refresh() }
        assertEquals(before, store.bytes)
        assertNotNull(connection.rememberedProposal(local))
    }

    @Test fun failedReceiptPersistenceRetainsRetryableBoardTransitionAndServerUndoIsTerminal() = runTest {
        val proposal = board()
        val store = Store(credential(supported = true, delivery = delivery(proposal)))
        var requests = 0
        val connection = DesktopConnection(store) { _, _, _, _, body, key -> requests++; receipt(body, key!!, "undone") }
        store.failSave = true
        assertFailsWith<IOException> { connection.acknowledge(localReceipt(proposal), "applied") }
        assertTrue(connection.needsAcknowledgement(localReceipt(proposal), "applied"))
        store.failSave = false
        connection.acknowledge(localReceipt(proposal), "applied")
        assertFalse(DesktopConnection(store).needsAcknowledgement(localReceipt(proposal), "applied"))
        assertFalse(DesktopConnection(store).needsAcknowledgement(localReceipt(proposal), "undone"))
        assertEquals(2, requests)
    }

    @Test fun partialSharingReportsConfirmedAcademicContextAndUnconfirmedBoardContext() = runTest {
        val store = Store(credential(supported = true))
        val paths = mutableListOf<String>()
        val connection = DesktopConnection(store) { _, _, _, path, body, key ->
            paths += path
            if (path == "/v1/assistant/board-context") throw IOException("Synthetic lost Board response")
            contextReceipt(body, key!!)
        }
        assertFailsWith<IOException> { connection.publishSelectedContext(academicContext(), boardContext()) }
        assertEquals(listOf("/v1/assistant/context", "/v1/assistant/board-context"), paths)
        assertTrue(connection.state.value.message!!.contains("may have reached"))
        assertTrue(connection.state.value.error!!.contains("Academic context sharing was confirmed"))
        assertFalse(connection.state.value.busy)
    }

    @Test fun mixedSemestersAndPrivateFieldsAreRejectedBeforeEitherNetworkWrite() = runTest {
        var requests = 0
        val connection = DesktopConnection(Store(credential(supported = true))) { _, _, _, _, _, _ -> requests++; error("unexpected request") }
        assertFailsWith<IllegalArgumentException> { connection.publishSelectedContext(academicContext("88888888-8888-4888-8888-888888888888"), boardContext()) }
        val root = Json.parseToJsonElement(boardContext()).jsonObject
        val semester = root.getValue("semester").jsonObject
        val tampered = JsonObject(root + ("semester" to JsonObject(semester + ("deviceSecret" to JsonPrimitive("must stay private"))))).toString()
        assertFailsWith<IllegalArgumentException> { connection.publishSelectedContext(academicContext(), tampered) }
        assertEquals(0, requests)
    }

    @Test fun contextWhitelistsEveryNestedObjectAndRespectsNotesAndProgressExclusions() {
        val privateFields = JsonObject(mapOf("privateCompatibility" to JsonPrimitive("Private retained history")))
        val original = board().semester
        val privateBoard = original.copy(unknownFields = privateFields, statuses = original.statuses.map { it.copy(unknownFields = privateFields) },
            courses = original.courses.map { course -> course.copy(sourceURL = "https://private.invalid/source", unknownFields = privateFields,
                entries = course.entries.mapValues { it.value.copy(unknownFields = privateFields) },
                assessments = course.assessments.map { it.copy(unknownFields = privateFields, reminderDays = listOf(2), snoozedUntil = 123.0,
                    recurrence = JsonObject(mapOf("private" to JsonPrimitive("private recurrence"))), providedFields = BoardAssessment.FIELDS) }) })
        val text = DesktopBoardContext.encode(privateBoard, "c".repeat(64), capturedAt = capturedAt)
        for (excluded in listOf("privateCompatibility", "private.invalid", "sourceURL", "unknownFields", "providedFields", "reminderDays", "snoozedUntil", "recurrence")) assertFalse(excluded in text, excluded)
        assertTrue("Recorded week note" in text)
        val restricted = Json.parseToJsonElement(DesktopBoardContext.encode(privateBoard, "c".repeat(64), notesIncluded = false,
            weekProgressIncluded = false, capturedAt = capturedAt)).jsonObject.getValue("semester").jsonObject.getValue("courses").jsonArray.single().jsonObject
        assertTrue(restricted.getValue("entries").jsonObject.isEmpty())
        assertFalse("scheduleNote" in restricted)
        assertFalse("notes" in restricted.getValue("assessments").jsonArray.single().jsonObject)
        assertEquals(listOf(1, 14, 52), restricted.getValue("activeWeeks").jsonArray.map { it.jsonPrimitive.int }, "Retained teaching-week coverage is not inferred progress")
    }

    @Test fun sharedContextPreservesUnknownWeeksAndExplicitEmptyCoverageSeparately() {
        val original = board().semester
        val course = original.courses.single()
        fun courseValue(board: BoardSemester) = Json.parseToJsonElement(DesktopBoardContext.encode(board, "c".repeat(64), capturedAt = capturedAt))
            .jsonObject.getValue("semester").jsonObject.getValue("courses").jsonArray.single().jsonObject
        val unknown = original.copy(courses = listOf(course.copy(activeWeeks = emptyList(), providedFields = course.providedFields - "activeWeeks")))
        assertFalse("activeWeeks" in courseValue(unknown), "Missing schedule coverage remains unknown")
        assertTrue(courseValue(original.copy(courses = listOf(course.copy(activeWeeks = emptyList())))).getValue("activeWeeks").jsonArray.isEmpty())
    }

    @Test fun recordedContextSharesDstGapAndFoldClocksWithoutResolvingOrMutatingSources() {
        val original = board().semester
        val course = original.courses.single()
        val assessment = course.assessments.single()
        val clocks = listOf("2026-03-29", "2026-10-25").mapIndexed { index, date ->
            assessment.copy(id = if (index == 0) assessment.id else "55555555-5555-4555-8555-555555555555",
                dueDate = date, dueTime = "02:30", providedFields = assessment.providedFields + "dueTime")
        }
        val recorded = original.copy(courses = listOf(course.copy(assessments = clocks)))
        assertTrue(clocks.all { SourceClockResolution.instant(it.dueDate, it.dueTime!!, recorded.timeZoneIdentifier!!) == null })
        val context = DesktopBoardContext.encode(recorded, "c".repeat(64), capturedAt = capturedAt)
        DesktopBoardContext.validate(context)
        val sharedSemester = Json.parseToJsonElement(context).jsonObject.getValue("semester")
        val shared = SemesterBoardCodec.decodeContextSemester(sharedSemester.toString()).courses.single().assessments
        assertEquals(listOf("2026-03-29", "2026-10-25"), shared.map { it.dueDate })
        assertEquals(listOf("02:30", "02:30"), shared.map { it.dueTime })
        assertEquals(clocks, recorded.courses.single().assessments, "Context serialization must not adjust original recorded source clocks")
    }

    @Test fun combinedSharingUsesOnlyBoardRouteForRecordedGapAndFoldClocks() = runTest {
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val original = board().semester
            val course = original.courses.single()
            val assessment = course.assessments.single().copy(dueDate = date, dueTime = "02:30",
                providedFields = course.assessments.single().providedFields + "dueTime")
            val recorded = original.copy(courses = listOf(course.copy(assessments = listOf(assessment))))
            val paths = mutableListOf<String>()
            val connection = DesktopConnection(Store(credential(supported = true))) { _, _, _, path, body, key ->
                paths += path
                assertEquals("/v1/assistant/board-context", path, "Legacy clock resolution must never block the read-only Board upload")
                val sent = Json.parseToJsonElement(body).jsonObject.getValue("semester").jsonObject.getValue("courses").jsonArray.single()
                    .jsonObject.getValue("assessments").jsonArray.single().jsonObject
                assertEquals(date, sent.getValue("dueDate").jsonPrimitive.content)
                assertEquals("02:30", sent.getValue("dueTime").jsonPrimitive.content)
                contextReceipt(body, key!!)
            }
            connection.publishSelectedContext(legacyContextFor(recorded), DesktopBoardContext.encode(recorded, "c".repeat(64), capturedAt = capturedAt))
            assertEquals(listOf("/v1/assistant/board-context"), paths)
            assertTrue(connection.state.value.message!!.contains("Legacy exam context was not shared"))
            assertTrue(connection.state.value.message!!.contains("Use the Semester Board tools"))
            assertEquals(assessment, recorded.courses.single().assessments.single())
        }
    }

    @Test fun unresolvedLegacyClocksFailBeforeNetworkWhenBoardSupportIsUnavailable() = runTest {
        val original = board().semester
        val course = original.courses.single()
        val recorded = original.copy(courses = listOf(course.copy(assessments = course.assessments.map {
            it.copy(dueDate = "2026-10-25", dueTime = "02:30", providedFields = it.providedFields + "dueTime")
        })))
        var requests = 0
        val connection = DesktopConnection(Store(credential())) { _, _, _, _, _, _ -> requests++; error("Must not upload incompatible legacy source clocks") }
        for (boardPayload in listOf(null, DesktopBoardContext.encode(recorded, "c".repeat(64), capturedAt = capturedAt))) {
            val failure = assertFailsWith<IllegalArgumentException> { connection.publishSelectedContext(legacyContextFor(recorded), boardPayload) }
            assertTrue(failure.message!!.contains("Update Habits MCP"))
            assertTrue(failure.message!!.contains("offline Semester Board export"))
        }
        assertEquals(0, requests)
    }

    @Test fun combinedSharingMatchesLegacySkippedDayAndStartNoonGuardsWithoutInventingTimes() = runTest {
        val original = board().semester
        val course = original.courses.single()
        val base = original.copy(startDate = "2011-12-29", endDate = "2012-01-04", weekCount = 1, timeZoneIdentifier = "Pacific/Apia")
        val skippedDeadline = base.copy(courses = listOf(course.copy(assessments = course.assessments.map {
            it.copy(dueDate = "2011-12-30", dueTime = null, providedFields = it.providedFields - "dueTime")
        })))
        val skippedStart = base.copy(startDate = "2011-12-30", endDate = "2012-01-05",
            courses = listOf(course.copy(assessments = course.assessments.map { it.copy(dueDate = "2012-01-03") })))
        for (recorded in listOf(skippedDeadline, skippedStart)) {
            val paths = mutableListOf<String>()
            val connection = DesktopConnection(Store(credential(supported = true))) { _, _, _, path, body, key ->
                paths += path; contextReceipt(body, key!!)
            }
            val boardPayload = DesktopBoardContext.encode(recorded, "c".repeat(64), capturedAt = capturedAt)
            connection.publishSelectedContext(legacyContextFor(recorded), boardPayload)
            assertEquals(listOf("/v1/assistant/board-context"), paths)
            assertTrue(recorded.courses.single().assessments.all { it.dueTime == null }, "Date-only legacy comparison times are never inserted into recorded facts")
        }
    }

    @Test fun representableClocksKeepLegacyThenBoardOrderIncludingDateOnlyMidnightGaps() = runTest {
        val original = board().semester
        val course = original.courses.single()
        val timed = original.copy(courses = listOf(course.copy(assessments = course.assessments.map {
            it.copy(dueDate = "2026-10-25", dueTime = "04:30", providedFields = it.providedFields + "dueTime")
        })))
        val dateOnly = original.copy(startDate = "2018-10-29", endDate = "2018-11-04", weekCount = 1,
            timeZoneIdentifier = "America/Sao_Paulo", courses = listOf(course.copy(assessments = course.assessments.map {
                it.copy(dueDate = "2018-11-04", dueTime = null, providedFields = it.providedFields - "dueTime")
            })))
        assertNull(SourceClockResolution.instant("2018-11-04", "00:00", "America/Sao_Paulo"), "This source date has a midnight gap, but the legacy deadline compares at 23:59:59")
        for (recorded in listOf(timed, dateOnly)) {
            val paths = mutableListOf<String>()
            val connection = DesktopConnection(Store(credential(supported = true))) { _, _, _, path, body, key ->
                paths += path; contextReceipt(body, key!!)
            }
            connection.publishSelectedContext(legacyContextFor(recorded), DesktopBoardContext.encode(recorded, "c".repeat(64), capturedAt = capturedAt))
            assertEquals(listOf("/v1/assistant/context", "/v1/assistant/board-context"), paths)
            assertTrue(connection.state.value.message!!.contains("academic and Semester Board context shared"))
        }
    }
}
