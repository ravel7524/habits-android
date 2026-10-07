package ch.habits.android.semester

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SemesterBoardCodecTest {
    private val semesterID = "22222222-2222-4222-8222-222222222222"
    private val courseID = "33333333-3333-4333-8333-333333333333"
    private val statusID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val proposalID = "11111111-1111-4111-8111-111111111119"
    private fun board() = BoardSemester(id = semesterID, title = "Synthetic semester", weekCount = 14,
        startDate = "2026-09-14", endDate = "2026-12-18", timeZoneIdentifier = "Europe/Zurich", showExamWeeks = true,
        statuses = listOf(BoardStatus(statusID, "Read", "#0066CC")), courses = listOf(BoardCourse(courseID, "Statistics",
            activeWeeks = listOf(2, 1, 14, 52), entries = mapOf(1 to BoardWeekEntry(statusID, "Read examples")),
            assessments = listOf(BoardAssessment(courseID, "Exam", "exam", "2026-12-16", "09:00")))))
    private fun proposal(b: BoardSemester = board(), expected: String? = null) = BoardProposal(proposalID = proposalID, source = "Synthetic timetable", semester = b, expectedBoardDigest = expected)
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun changed(root: JsonObject, key: String, value: JsonElement?) = JsonObject(root.toMutableMap().apply { if (value == null) remove(key) else put(key, value) })
    private fun courseChanged(text: String, change: (JsonObject) -> JsonObject): String {
        val root = json(text); val courses = root["courses"] as JsonArray
        return changed(root, "courses", JsonArray(listOf(change(courses[0] as JsonObject)) + courses.drop(1))).toString()
    }
    private fun fixture(): String = listOf(File("docs/contracts/semester-board-example.json"), File("../docs/contracts/semester-board-example.json"))
        .firstOrNull { it.isFile }?.readText() ?: error("The standalone synthetic board fixture is missing")
    private fun invalid(block: () -> Unit) { assertFailsWith<BoardValidationException>(block = block) }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun standaloneFixtureRetainsCompatibilityDataAndNeverInventsMissingFields() {
        val b = SemesterBoardCodec.decode(fixture())
        assertEquals(listOf(1, 2, 14, 52), b.courses.single().activeWeeks)
        assertEquals(b.courses.single().id, b.courses.single().assessments.first().id)
        assertEquals(listOf(7, 1), b.courses.single().assessments.first().reminderDays)
        assertTrue(b.unknownFields.containsKey("futureSemesterFlag"))
        assertTrue(b.courses.single().entries.getValue(1).unknownFields.containsKey("futureWeekFlag"))
        val encoded = SemesterBoardCodec.encode(b)
        assertFalse(encoded.contains("\"providedFields\"")); assertFalse(encoded.contains("\"unknownFields\""))
        assertEquals(b, SemesterBoardCodec.decode(encoded))
    }

    @Test fun omittedAndExplicitNullOptionalFieldsRemainDistinct() {
        val raw = SemesterBoardCodec.encode(board())
        val absent = SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "moduleCode", null) })
        val cleared = SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "moduleCode", JsonNull) })
        assertNull(absent.courses[0].moduleCode); assertNull(cleared.courses[0].moduleCode)
        assertFalse("moduleCode" in absent.courses[0].providedFields); assertTrue("moduleCode" in cleared.courses[0].providedFields)
        assertFalse(json(SemesterBoardCodec.encode(absent))["courses"].toString().contains("moduleCode"))
        assertTrue(json(SemesterBoardCodec.encode(cleared))["courses"].toString().contains("\"moduleCode\":null"))
    }

    @Test fun absentAssessmentNotesAndStateDoNotBecomeExplicitEmptyOrPendingChanges() {
        val raw = courseChanged(SemesterBoardCodec.encode(board())) { course ->
            val item = ((course["assessments"] as JsonArray)[0] as JsonObject)
            changed(course, "assessments", JsonArray(listOf(changed(changed(item, "notes", null), "state", null))))
        }
        val b = SemesterBoardCodec.decode(raw)
        val item = b.courses.single().assessments.single()
        assertEquals("pending", item.state); assertEquals("", item.notes)
        assertFalse("state" in item.providedFields); assertFalse("notes" in item.providedFields)
        val encoded = json(SemesterBoardCodec.encode(b))["courses"].toString()
        assertFalse(encoded.contains("\"state\"")); assertFalse(encoded.contains("\"notes\":\"\""))
    }

    @Test fun rawSchemasOneAndTwoMigrateLegacyExamWithoutReplacingItsIdentity() {
        for (version in 1..2) {
            val root = changed(json(SemesterBoardCodec.encode(board())), "schemaVersion", JsonPrimitive(version))
            val raw = courseChanged(root.toString()) { c -> changed(changed(changed(changed(c, "assessments", null), "examDate", JsonPrimitive("2026-12-16")),
                "examTime", JsonPrimitive("09:00")), "examNote", JsonPrimitive("Bring ID")) }
            val decoded = SemesterBoardCodec.decode(raw)
            assertEquals(3, decoded.schemaVersion)
            val exam = decoded.courses.single().assessments.single()
            assertEquals(courseID, exam.id); assertEquals("Exam", exam.title); assertEquals("Bring ID", exam.notes)
            assertFalse("state" in exam.providedFields)
            val output = SemesterBoardCodec.encode(decoded)
            assertFalse(output.contains("examDate")); assertEquals(decoded, SemesterBoardCodec.decode(output))
        }
    }

    @Test fun legacyExamCollisionAndOrphanTimeOrNoteAreRejected() {
        val raw = SemesterBoardCodec.encode(board())
        invalid { SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "examDate", JsonPrimitive("2026-12-16")) }) }
        invalid { SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "examTime", JsonPrimitive("09:00")) }) }
        invalid { SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "examNote", JsonPrimitive("No date supplied")) }) }
    }

    @Test fun scopedUUIDsAllowCourseAndAssessmentReuseButRejectSameScopeDuplicates() {
        val base = board()
        SemesterBoardCodec.validate(base)
        val second = base.courses.single().copy(id = "44444444-4444-4444-8444-444444444444")
        SemesterBoardCodec.validate(base.copy(courses = base.courses + second))
        invalid { SemesterBoardCodec.validate(base.copy(courses = base.courses + base.courses.single().copy(id = courseID.uppercase()))) }
        invalid { SemesterBoardCodec.validate(base.copy(statuses = base.statuses + base.statuses.single().copy(id = statusID.uppercase()))) }
        invalid { SemesterBoardCodec.validate(base.copy(courses = listOf(base.courses[0].copy(assessments = base.courses[0].assessments + base.courses[0].assessments[0])))) }
    }

    @Test fun civilRangesCanBeAbsentButCannotBeHalfSpecifiedOrDisagreeWithWeeks() {
        val b = board()
        val undated = b.copy(startDate = null, endDate = null, providedFields = b.providedFields - setOf("startDate", "endDate"))
        assertNull(SemesterBoardCodec.decode(SemesterBoardCodec.encode(undated)).startDate)
        invalid { SemesterBoardCodec.validate(b.copy(endDate = null)) }
        invalid { SemesterBoardCodec.validate(b.copy(endDate = "2026-09-14")) }
        invalid { SemesterBoardCodec.validate(b.copy(startDate = "2025-02-29")) }
        invalid { SemesterBoardCodec.validate(b.copy(startDate = "0000-01-01")) }
        invalid { SemesterBoardCodec.decode(changed(json(SemesterBoardCodec.encode(b)), "schemaVersion", JsonPrimitive(4)).toString()) }
    }

    @Test fun progressWeekFiftyTwoSurvivesEvenOutsideTeachingWeeks() {
        val b = board().copy(courses = listOf(board().courses.single().copy(entries = mapOf(52 to BoardWeekEntry(null, "Hidden retained note")))))
        assertEquals("Hidden retained note", SemesterBoardCodec.decode(SemesterBoardCodec.encode(b)).courses[0].entries.getValue(52).notes)
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(b.courses[0].copy(activeWeeks = listOf(53))))) }
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(b.courses[0].copy(entries = mapOf(0 to BoardWeekEntry()))))) }
        invalid { SemesterBoardCodec.validate(b.copy(statuses = listOf(BoardStatus(statusID, "Read", "#123")))) }
        invalid { SemesterBoardCodec.validate(b.copy(statuses = emptyList(), courses = listOf(board().courses[0]))) }
    }

    @Test fun rawActiveWeeksUseSetSemanticsAndAmbiguousWeekKeysFailClosed() {
        val raw = SemesterBoardCodec.encode(board())
        val deduplicated = SemesterBoardCodec.decode(courseChanged(raw) { changed(it, "activeWeeks", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(2), JsonPrimitive(1)))) })
        assertEquals(listOf(2, 1), deduplicated.courses[0].activeWeeks)
        invalid { SemesterBoardCodec.decode(courseChanged(raw) { c ->
            val entries = c["entries"] as JsonObject
            changed(c, "entries", changed(entries, "01", entries.getValue("1")))
        }) }
    }

    @Test fun materializedRecurrenceExceptionsAndUnknownMetadataRoundTripWithoutExpansion() {
        val recurrence = json("""{"seriesID":"55555555-5555-4555-8555-555555555555","scheduledDate":"2026-12-16","endDate":"2027-01-13","intervalWeeks":1,"occurrenceIndex":0,"isException":false,"futureSeries":"keep"}""")
        val course = board().courses[0]
        val item = course.assessments[0].copy(recurrence = recurrence, providedFields = course.assessments[0].providedFields + "recurrence")
        val b = board().copy(courses = listOf(course.copy(assessments = listOf(item))))
        val result = SemesterBoardCodec.decode(SemesterBoardCodec.encode(b))
        assertEquals(1, result.courses[0].assessments.size); assertEquals(recurrence, result.courses[0].assessments[0].recurrence)
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(course.copy(assessments = listOf(item, item.copy(id = proposalID)))))) }
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(course.copy(assessments = listOf(item.copy(dueDate = "2026-12-17")))))) }
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(course.copy(assessments = listOf(item.copy(recurrence = changed(recurrence, "endDate", JsonPrimitive("2029-01-01")))))))) }
        SemesterBoardCodec.validate(b.copy(courses = listOf(course.copy(assessments = listOf(item.copy(dueDate = "2026-12-17", recurrence = changed(recurrence, "isException", JsonPrimitive(true))))))))
    }

    @Test fun rawDeviceMetadataIsRetainedButInvalidOffsetsAreRejected() {
        val b = SemesterBoardCodec.decode(fixture())
        assertEquals(812160000.25, b.courses[0].assessments[0].snoozedUntil)
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(b.courses[0].copy(assessments = listOf(b.courses[0].assessments[0].copy(reminderDays = listOf(1, 1))))))) }
        invalid { SemesterBoardCodec.validate(b.copy(courses = listOf(b.courses[0].copy(assessments = listOf(b.courses[0].assessments[0].copy(reminderDays = listOf(366))))))) }
    }

    @Test fun closedProposalRoundTripsWithoutDTOOrCompatibilityWrappers() {
        val p = proposal()
        val encoded = SemesterBoardCodec.encodeProposal(p)
        assertEquals(p, SemesterBoardCodec.decodeProposal(encoded))
        assertFalse(encoded.contains("providedFields")); assertFalse(encoded.contains("unknownFields")); assertFalse(encoded.contains("reminderDays"))
        assertTrue(encoded.contains("\"statusID\""))
    }

    @Test fun proposalsRejectUnknownLegacyRecurrenceAndDeviceFieldsEvenWhenNull() {
        val p = proposal(); val raw = json(SemesterBoardCodec.encodeProposal(p))
        invalid { SemesterBoardCodec.decodeProposal(changed(raw, "uiCode", JsonPrimitive("not a command")).toString()) }
        for (key in listOf("reminderDays", "snoozedUntil", "recurrence")) {
            val c = p.semester.courses[0]; val a = c.assessments[0]
            invalid { SemesterBoardCodec.encodeProposal(p.copy(semester = p.semester.copy(courses = listOf(c.copy(assessments = listOf(a.copy(providedFields = a.providedFields + key))))))) }
        }
        invalid { SemesterBoardCodec.encodeProposal(p.copy(semester = p.semester.copy(unknownFields = json("""{"privateCompatibility":"not shared"}""")))) }
        invalid { SemesterBoardCodec.encodeProposal(p.copy(semester = p.semester.copy(courses = listOf(p.semester.courses[0].copy(providedFields = p.semester.courses[0].providedFields + "examDate"))))) }
        invalid { SemesterBoardCodec.decodeProposal(changed(raw, "version", JsonPrimitive(2)).toString()) }
        invalid { SemesterBoardCodec.decodeProposal(changed(raw, "expectedBoardDigest", JsonNull).toString()) }
        invalid { SemesterBoardCodec.encodeProposal(p.copy(expectedBoardDigest = "A".repeat(64))) }
    }

    @Test fun closedUnknownScheduleAndRedactedNotesRemainOmitted() {
        val c = board().courses[0].copy(activeWeeks = emptyList(), providedFields = BoardCourse.FIELDS - setOf("activeWeeks", "sourceURL"),
            entries = mapOf(1 to BoardWeekEntry(statusID, "Private note", providedFields = setOf("statusID"))))
        val context = board().copy(courses = listOf(c))
        val encoded = SemesterBoardCodec.encodeContextSemester(context)
        assertFalse(encoded.contains("activeWeeks")); assertFalse(encoded.contains("Private note")); assertFalse(encoded.contains("sourceURL"))
        val decoded = SemesterBoardCodec.decodeContextSemester(encoded)
        assertFalse("activeWeeks" in decoded.courses[0].providedFields); assertFalse("notes" in decoded.courses[0].entries.getValue(1).providedFields)
        invalid { SemesterBoardCodec.encode(decoded) }
        invalid { SemesterBoardCodec.decode(encoded) }
        invalid { SemesterBoardCodec.encodeContextSemester(board()) }
        val p = proposal(context)
        assertFalse(SemesterBoardCodec.encodeProposal(p).contains("activeWeeks"))
        invalid { SemesterBoardCodec.decodeProposal(SemesterBoardCodec.encodeProposal(p).replace("\"entries\":", "\"activeWeeks\":null,\"entries\":")) }
    }

    @Test fun existingReferencesNeedAnExpectedDigestAndAreNotInventedByRawValidation() {
        val b = board().copy(statuses = emptyList())
        invalid { SemesterBoardCodec.encodeProposal(proposal(b)) }
        invalid { SemesterBoardCodec.validate(b) }
        val p = proposal(b, "a".repeat(64))
        assertEquals(p, SemesterBoardCodec.decodeProposal(SemesterBoardCodec.encodeProposal(p)))
    }

    @Test fun closedDSTRulesRejectSkippedAndRepeatedClocksWhileRawSourceIsPreserved() {
        for (day in listOf("2026-03-29", "2026-10-25")) {
            val c = board().courses[0]; val item = c.assessments[0].copy(dueDate = day, dueTime = "02:30")
            val b = board().copy(courses = listOf(c.copy(assessments = listOf(item))))
            assertEquals("02:30", SemesterBoardCodec.decode(SemesterBoardCodec.encode(b)).courses[0].assessments[0].dueTime)
            invalid { SemesterBoardCodec.encodeProposal(proposal(b)) }
        }
        val b = board().copy(timeZoneIdentifier = null)
        invalid { SemesterBoardCodec.encodeProposal(proposal(b)) }
        SemesterBoardCodec.encodeProposal(proposal(b, "a".repeat(64)))
        invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(timeZoneIdentifier = "CET"))) }
    }

    @Test fun existingDigestDefersDSTToMergeAwareWriterWithoutRelaxingLexicalTiming() {
        for (day in listOf("2026-03-29", "2026-10-25")) {
            val course = board().courses.single()
            val assessment = course.assessments.single().copy(title = "Retitled recorded exam", state = "dismissed", dueDate = day, dueTime = "02:30")
            val recorded = board().copy(courses = listOf(course.copy(assessments = listOf(assessment))))
            val existing = proposal(recorded, "a".repeat(64))
            val decoded = SemesterBoardCodec.decodeProposal(SemesterBoardCodec.encodeProposal(existing))
            assertEquals(day, decoded.semester.courses.single().assessments.single().dueDate)
            assertEquals("02:30", decoded.semester.courses.single().assessments.single().dueTime)
            invalid { SemesterBoardCodec.encodeProposal(existing.copy(expectedBoardDigest = null)) }
            val invalidClock = recorded.copy(courses = listOf(course.copy(assessments = listOf(assessment.copy(dueTime = "25:00")))))
            invalid { SemesterBoardCodec.encodeProposal(proposal(invalidClock, "a".repeat(64))) }
            val invalidDay = recorded.copy(courses = listOf(course.copy(assessments = listOf(assessment.copy(dueDate = "2026-02-30")))))
            invalid { SemesterBoardCodec.encodeProposal(proposal(invalidDay, "a".repeat(64))) }
            invalid { SemesterBoardCodec.encodeProposal(existing.copy(semester = recorded.copy(timeZoneIdentifier = "Europe/NotAZone"))) }
        }
    }

    @Test fun recordedContextPreservesExplicitZoneDSTGapAndFoldWithoutInventingAnInstant() {
        for (day in listOf("2026-03-29", "2026-10-25")) {
            val course = board().courses.single()
            val assessment = course.assessments.single().copy(dueDate = day, dueTime = "02:30")
            val recorded = board().copy(courses = listOf(course.copy(assessments = listOf(assessment), providedFields = course.providedFields - "sourceURL")))
            val decoded = SemesterBoardCodec.decodeContextSemester(SemesterBoardCodec.encodeContextSemester(recorded))
            assertEquals("Europe/Zurich", decoded.timeZoneIdentifier)
            assertEquals(day, decoded.courses.single().assessments.single().dueDate)
            assertEquals("02:30", decoded.courses.single().assessments.single().dueTime)
            assertNull(decoded.courses.single().assessments.single().completedAt)
        }
    }

    @Test fun onlyRecordedCompletedStateMayCarryAProposalCompletionTimestamp() {
        val c = board().courses[0]; val a = c.assessments[0].copy(completedAt = 0.0)
        invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(c.copy(assessments = listOf(a)))))) }
        val completed = a.copy(state = "completed")
        SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(c.copy(assessments = listOf(completed))))))
        val omittedState = a.copy(providedFields = a.providedFields - "state")
        SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(c.copy(assessments = listOf(omittedState)))), "a".repeat(64)))
    }

    @Test fun sourceURLsAndAssistantTextHaveBoundedTruthfulTypes() {
        for (url in listOf("file:///private/path", "https://user:password@example.invalid", "not a URL")) {
            invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(board().courses[0].copy(sourceURL = url))))) }
        }
        SemesterBoardCodec.encodeProposal(proposal(board().copy(title = "😀".repeat(200))))
        invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(title = "😀".repeat(201)))) }
        invalid { SemesterBoardCodec.encodeProposal(proposal().copy(source = " Source ")) }
        invalid { SemesterBoardCodec.encodeProposal(proposal().copy(source = "x".repeat(301))) }
        invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(board().courses[0].copy(entries = mapOf(1 to BoardWeekEntry(null, "x".repeat(4001)))))))) }
        invalid { SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(board().courses[0].copy(entries = mapOf(1 to BoardWeekEntry(null, "Hidden\u200Bcontrol"))))))) }
        SemesterBoardCodec.encodeProposal(proposal(board().copy(courses = listOf(board().courses[0].copy(entries = mapOf(1 to BoardWeekEntry(null, "Tab\tLine\nReturn\r")))))))
        val longRaw = board().copy(title = "Source title ".repeat(100), courses = listOf(board().courses[0].copy(entries = mapOf(1 to BoardWeekEntry(null, "x".repeat(10_000))))))
        assertEquals(longRaw, SemesterBoardCodec.decode(SemesterBoardCodec.encode(longRaw)))
    }

    @Test fun countsAreBoundedAndMalformedUnicodeDuplicateKeysAndDepthFailClosed() {
        invalid { SemesterBoardCodec.validate(board().copy(statuses = (0..20).map { BoardStatus("aaaaaaaa-aaaa-4aaa-8aaa-${it.toString().padStart(12, '0')}", "Status", "#0066CC") })) }
        invalid { SemesterBoardCodec.validate(board().copy(courses = (0..10).map { board().courses[0].copy(id = "aaaaaaaa-aaaa-4aaa-8aaa-${it.toString().padStart(12, '0')}") })) }
        invalid { SemesterBoardCodec.validate(board().copy(courses = listOf(board().courses[0].copy(assessments = (0..200).map { board().courses[0].assessments[0].copy(id = "aaaaaaaa-aaaa-4aaa-8aaa-${it.toString().padStart(12, '0')}") })))) }
        val raw = SemesterBoardCodec.encode(board())
        invalid { SemesterBoardCodec.decode(raw.replace("\"title\":\"Synthetic semester\"", "\"title\":\"Synthetic semester\",\"\\u0074itle\":\"Duplicate\"")) }
        invalid { SemesterBoardCodec.decode(raw.replace("Synthetic semester", "\uD800")) }
        invalid { SemesterBoardCodec.decode("[".repeat(70) + "0" + "]".repeat(70)) }
        invalid { SemesterBoardCodec.decode(changed(json(raw), "oversize", JsonPrimitive("x".repeat(SemesterBoardCodec.MAX_DATA_BYTES))).toString()) }
    }

    @Test fun foundationTimestampConversionKeepsTheEpochAndFractionWithoutDoubleEpochCancellation() {
        assertEquals("2001-01-01T00:00:00Z", SemesterBoardCodec.referenceDateToInstant(0.0))
        assertEquals("1970-01-01T00:00:00Z", SemesterBoardCodec.referenceDateToInstant(-978307200.0))
        assertEquals("2000-12-31T23:59:59.875Z", SemesterBoardCodec.referenceDateToInstant(-0.125))
        assertEquals("2001-01-01T00:00:00.123456789Z", SemesterBoardCodec.referenceDateToInstant(0.123456789))
        for (stamp in listOf(0.123456789, -0.125, 812160000.1234567)) assertEquals(stamp, SemesterBoardCodec.instantToReferenceDate(SemesterBoardCodec.referenceDateToInstant(stamp)))
        invalid { SemesterBoardCodec.referenceDateToInstant(Double.NaN) }
        invalid { SemesterBoardCodec.referenceDateToInstant(Double.MAX_VALUE) }
        invalid { SemesterBoardCodec.instantToReferenceDate("not an instant") }
    }

    @Test fun canonicalFingerprintNormalizesUUIDsButPreservesArraysOmissionAndExplicitNull() {
        val p = proposal()
        val upper = p.copy(proposalID = p.proposalID.uppercase(), semester = p.semester.copy(id = p.semester.id.uppercase(),
            statuses = p.semester.statuses.map { it.copy(id = it.id.uppercase()) }, courses = p.semester.courses.map { c -> c.copy(id = c.id.uppercase(),
                entries = c.entries.mapValues { it.value.copy(statusID = it.value.statusID?.uppercase()) }, assessments = c.assessments.map { it.copy(id = it.id.uppercase()) }) }))
        assertEquals(SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(p)), SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(upper)))
        val order = p.copy(semester = p.semester.copy(courses = p.semester.courses.map { it.copy(activeWeeks = it.activeWeeks.reversed()) }))
        assertNotEquals(SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(p)), SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(order)))
        val omitted = p.copy(semester = p.semester.copy(courses = p.semester.courses.map { it.copy(providedFields = it.providedFields - "moduleCode") }))
        assertNotEquals(SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(p)), SemesterBoardCodec.canonicalPayload(SemesterBoardCodec.encodeProposal(omitted)))
    }

    @Test fun sharedPythonCanonicalVectorMatchesByteForByteFingerprint() {
        val canonical = SemesterBoardCodec.canonicalPayload(serverVector)
        assertEquals(1246, canonical.toByteArray(Charsets.UTF_8).size)
        assertEquals("4b534684d01d4d67b99b78f33ccdda45eeeaf453156e61a1eaea9321a80e890b", sha(canonical))
    }

    private val serverVector = """{"format":"habits.semester-board-proposal","version":1,"proposalID":"11111111-1111-4111-8111-111111111119","source":"Synthetic Semester Board fixture; fictional source dates","semester":{"schemaVersion":3,"id":"22222222-2222-4222-8222-222222222222","title":"Synthetic course-week board","weekCount":14,"startDate":"2026-09-14","endDate":"2026-12-18","timeZoneIdentifier":"Europe/Zurich","showExamWeeks":true,"statuses":[{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"Recorded done","colorHex":"#28A35A"},{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","name":"Revisit","colorHex":"#E9C52F"}],"courses":[{"id":"33333333-3333-4333-8333-333333333333","name":"Synthetic statistics","moduleCode":"QA-STAT","activeWeeks":[1,2,14,52],"entries":{"1":{"statusID":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","notes":"Synthetic recorded week note"},"52":{"notes":"Retained hidden week"}},"assessments":[{"id":"33333333-3333-4333-8333-333333333333","title":"Legacy scoped exam identity","kind":"exam","dueDate":"2026-12-16","dueTime":"09:00","notes":"Synthetic exam note","state":"pending"},{"id":"55555555-5555-4555-8555-555555555555","title":"Recorded completed essay","kind":"essay","dueDate":"2026-10-01","state":"completed","completedAt":812160000.0}]}]}}"""
}
