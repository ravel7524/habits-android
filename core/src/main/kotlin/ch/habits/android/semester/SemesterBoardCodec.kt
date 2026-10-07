package ch.habits.android.semester

import ch.habits.android.core.AcademicPlanCodec
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** iOS semester.json compatibility is intentionally separate from the closed assistant envelope. */
object SemesterBoardCodec {
    const val MAX_DATA_BYTES = 2 * 1024 * 1024
    const val MAX_COURSES = 10
    const val MAX_STATUSES = 20
    const val MAX_ASSESSMENTS = 200
    private val requiredSemester = setOf("schemaVersion", "id", "title", "weekCount", "statuses", "courses")
    private val requiredCourse = setOf("id", "name", "activeWeeks", "entries")
    private val requiredAssessment = setOf("id", "title", "kind", "dueDate")
    private val proposalFields = setOf("format", "version", "proposalID", "source", "expectedBoardDigest", "semester")
    private val recurrenceFields = setOf("seriesID", "scheduledDate", "endDate", "intervalWeeks", "occurrenceIndex", "isException")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val timePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private val weekKeyPattern = Regex("(?:[1-9]|[1-4][0-9]|5[0-2])")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val zonePattern = Regex("(?:UTC|[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)+)")
    private val foundationOffset = BigDecimal("978307200")

    fun decode(text: String): BoardSemester = boundary {
        val board = semester(obj(parse(text), "semester"), false)
        validate(board)
        board
    }

    fun encode(board: BoardSemester): String {
        validate(board)
        ensure(board.courses.all { "activeWeeks" in it.providedFields && it.entries.values.all { entry -> "notes" in entry.providedFields } },
            "semester.courses", "a full iOS file needs explicit schedules and week notes")
        return encoded(semesterJson(board))
    }

    fun validate(board: BoardSemester) {
        validateBoard(board, proposal = false, existingReferences = false)
        encoded(semesterJson(board))
    }

    /** Consent-filtered academic context has optional schedules/notes, never opaque or device data. */
    fun decodeContextSemester(text: String): BoardSemester = boundary {
        val board = semester(obj(parse(text), "semester"), true)
        validateContext(board)
        board
    }

    fun encodeContextSemester(board: BoardSemester): String {
        validateContext(board)
        return encoded(semesterJson(board))
    }

    private fun validateContext(board: BoardSemester) {
        ensure(board.schemaVersion == 3, "semester.schemaVersion", "context requires schema 3")
        validateBoard(board, proposal = true, existingReferences = true)
        ensure(board.courses.all { it.sourceURL == null && "sourceURL" !in it.providedFields }, "semester.courses", "source URLs are not shared context data")
    }

    fun decodeProposal(text: String): BoardProposal = boundary {
        val root = obj(parse(text), "proposal")
        shape(root, proposalFields, setOf("format", "version", "proposalID", "source", "semester"), "proposal")
        val value = BoardProposal(str(root, "format", "proposal"), int(root, "version", "proposal"),
            str(root, "proposalID", "proposal"), str(root, "source", "proposal"),
            if (root.containsKey("expectedBoardDigest")) str(root, "expectedBoardDigest", "proposal") else null,
            semester(obj(root["semester"], "semester"), true))
        validateProposal(value)
        value
    }

    fun encodeProposal(proposal: BoardProposal): String {
        validateProposal(proposal)
        return encoded(proposalJson(proposal))
    }

    /** Stable local fingerprint: significant array order and omission/null remain intact. */
    fun canonicalPayload(text: String): String {
        val proposal = decodeProposal(text)
        val root = obj(parse(encodeProposal(proposal)), "proposal").toMutableMap()
        root["proposalID"] = JsonPrimitive(uuid(proposal.proposalID, "proposal.proposalID"))
        val board = obj(root["semester"], "semester").toMutableMap()
        board["id"] = JsonPrimitive(uuid(proposal.semester.id, "semester.id"))
        board["statuses"] = JsonArray(arr(board["statuses"], "statuses").map { status ->
            val fields = obj(status, "status").toMutableMap()
            fields["id"] = JsonPrimitive(uuid(str(fields, "id", "status"), "status.id")); JsonObject(fields)
        })
        board["courses"] = JsonArray(arr(board["courses"], "courses").map { course ->
            val fields = obj(course, "course").toMutableMap()
            fields["id"] = JsonPrimitive(uuid(str(fields, "id", "course"), "course.id"))
            fields["entries"] = JsonObject(obj(fields["entries"], "entries").mapValues { (_, entry) ->
                val item = obj(entry, "entry").toMutableMap()
                optionalString(item, "statusID", "entry")?.let { item["statusID"] = JsonPrimitive(uuid(it, "entry.statusID")) }
                JsonObject(item)
            })
            fields["assessments"]?.let { assessments -> fields["assessments"] = JsonArray(arr(assessments, "assessments").map { assessment ->
                val item = obj(assessment, "assessment").toMutableMap()
                item["id"] = JsonPrimitive(uuid(str(item, "id", "assessment"), "assessment.id")); JsonObject(item)
            }) }
            JsonObject(fields)
        })
        root["semester"] = JsonObject(board)
        return canonicalJson(JsonObject(root)).also { parse(it) }
    }

    fun referenceDateToInstant(value: Double): String {
        ensure(value.isFinite(), "timestamp", "must be finite")
        return try {
            val seconds = BigDecimal.valueOf(value).add(foundationOffset).setScale(9, RoundingMode.HALF_EVEN)
            val whole = seconds.setScale(0, RoundingMode.FLOOR)
            val nano = seconds.subtract(whole).movePointRight(9).longValueExact()
            val instant = Instant.ofEpochSecond(whole.longValueExact(), nano)
            ensure(instant >= Instant.parse("0001-01-01T00:00:00Z") && instant < Instant.parse("+10000-01-01T00:00:00Z"), "timestamp", "outside supported calendar range")
            instant.toString()
        } catch (failure: BoardValidationException) { throw failure }
        catch (_: Exception) { invalid("timestamp", "outside supported calendar range") }
    }

    fun instantToReferenceDate(value: String): Double = try {
        val instant = Instant.parse(value)
        ensure(instant >= Instant.parse("0001-01-01T00:00:00Z") && instant < Instant.parse("+10000-01-01T00:00:00Z"), "timestamp", "outside supported calendar range")
        BigDecimal.valueOf(instant.epochSecond).add(BigDecimal.valueOf(instant.nano.toLong(), 9)).subtract(foundationOffset).toDouble()
            .also { ensure(it.isFinite(), "timestamp", "must be finite") }
    } catch (failure: BoardValidationException) { throw failure }
    catch (_: Exception) { invalid("timestamp", "must be a valid ISO instant") }

    private fun semester(root: JsonObject, closed: Boolean): BoardSemester {
        if (closed) shape(root, BoardSemester.FIELDS, requiredSemester, "semester") else required(root, requiredSemester, "semester")
        val schema = integer(root["schemaVersion"], "semester.schemaVersion", closed)
        ensure(schema in 1..3 && (!closed || schema == 3), "semester.schemaVersion", "unsupported schema")
        val statuses = arr(root["statuses"], "semester.statuses").also { ensure(it.size <= MAX_STATUSES, "semester.statuses", "too many statuses") }
            .mapIndexed { index, element -> status(obj(element, "status"), closed, "semester.statuses[$index]") }
        val courses = arr(root["courses"], "semester.courses").also { ensure(it.size <= MAX_COURSES, "semester.courses", "too many courses") }
            .mapIndexed { index, element -> course(obj(element, "course"), closed, "semester.courses[$index]") }
        if (closed) ensure(root.containsKey("startDate") == root.containsKey("endDate"), "semester", "date range fields must be paired")
        return BoardSemester(3, str(root, "id", "semester"), str(root, "title", "semester"), integer(root["weekCount"], "semester.weekCount", closed), statuses, courses,
            optionalString(root, "startDate", "semester"), optionalString(root, "endDate", "semester"),
            optionalBool(root, "showExamWeeks", "semester", closed) ?: false, optionalString(root, "timeZoneIdentifier", "semester"),
            extras(root, BoardSemester.FIELDS), root.keys.intersect(BoardSemester.FIELDS))
    }

    private fun status(root: JsonObject, closed: Boolean, path: String): BoardStatus {
        if (closed) shape(root, BoardStatus.FIELDS, BoardStatus.FIELDS, path) else required(root, BoardStatus.FIELDS, path)
        return BoardStatus(str(root, "id", path), str(root, "name", path), str(root, "colorHex", path), extras(root, BoardStatus.FIELDS), root.keys.intersect(BoardStatus.FIELDS))
    }

    private fun course(root: JsonObject, closed: Boolean, path: String): BoardCourse {
        val known = BoardCourse.FIELDS + BoardCourse.LEGACY_FIELDS
        if (closed) shape(root, BoardCourse.FIELDS, requiredCourse - "activeWeeks", path) else required(root, requiredCourse, path)
        val id = str(root, "id", path)
        val weeks = (if (closed) optionalArray(root, "activeWeeks", path, true).orEmpty() else arr(root["activeWeeks"], "$path.activeWeeks"))
            .map { integer(it, "$path.activeWeeks", closed) }
        val entries = obj(root["entries"], "$path.entries")
        ensure(entries.size <= 52, "$path.entries", "too many weeks")
        val decoded = linkedMapOf<Int, BoardWeekEntry>()
        entries.forEach { (key, value) ->
            ensure(if (closed) weekKeyPattern.matches(key) else Regex("[0-9]+").matches(key), "$path.entries", "invalid week key")
            val week = key.toIntOrNull() ?: invalid("$path.entries", "invalid week key")
            ensure(week !in decoded, "$path.entries", "duplicate week identity")
            val item = obj(value, "$path.entries")
            if (closed) shape(item, BoardWeekEntry.FIELDS, emptySet(), "$path.entries") else required(item, setOf("notes"), "$path.entries")
            decoded[week] = BoardWeekEntry(optionalString(item, "statusID", "$path.entries"),
                if (closed) optionalString(item, "notes", "$path.entries", nullAllowed = false) ?: "" else str(item, "notes", "$path.entries"),
                extras(item, BoardWeekEntry.FIELDS), item.keys.intersect(BoardWeekEntry.FIELDS))
        }
        val assessments = optionalArray(root, "assessments", path, closed).orEmpty().also { ensure(it.size <= MAX_ASSESSMENTS, "$path.assessments", "too many assessments") }
            .mapIndexed { index, value -> assessment(obj(value, "$path.assessments"), closed, "$path.assessments[$index]") }.toMutableList()
        val examDate = optionalString(root, "examDate", path)
        val examTime = optionalString(root, "examTime", path)
        val examNote = optionalString(root, "examNote", path)
        if (examTime != null) ensure(examDate != null && timePattern.matches(examTime), "$path.examTime", "requires a date and valid time")
        if (examDate == null) ensure(examNote.isNullOrEmpty(), "$path.examNote", "legacy exam note needs a date")
        val presence = root.keys.intersect(BoardCourse.FIELDS).toMutableSet()
        if (examDate != null) {
            date(examDate, "$path.examDate")
            ensure(assessments.none { uuid(it.id, "$path.assessments.id") == uuid(id, "$path.id") }, "$path.examDate", "legacy exam assessment identity already exists")
            val fields = mutableSetOf("id", "title", "kind", "dueDate")
            if ("examTime" in root) fields += "dueTime"
            if ("examNote" in root) fields += "notes"
            assessments += BoardAssessment(id, "Exam", "exam", examDate, examTime, examNote ?: "", providedFields = fields)
            presence += "assessments"
        }
        return BoardCourse(id, str(root, "name", path), if (closed) weeks else weeks.distinct(), decoded,
            optionalString(root, "moduleCode", path), optionalString(root, "sourceURL", path), optionalString(root, "scheduleNote", path), assessments,
            extras(root, known), presence)
    }

    private fun assessment(root: JsonObject, closed: Boolean, path: String): BoardAssessment {
        val allowed = BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS - setOf("recurrence")
        if (closed) shape(root, allowed, requiredAssessment, path) else required(root, requiredAssessment, path)
        return BoardAssessment(str(root, "id", path), str(root, "title", path), str(root, "kind", path), str(root, "dueDate", path),
            optionalString(root, "dueTime", path), optionalString(root, "notes", path, nullAllowed = !closed) ?: "",
            optionalString(root, "state", path, nullAllowed = !closed) ?: "pending", optionalNumber(root, "completedAt", path),
            root["recurrence"]?.takeUnless { it is JsonNull }, optionalArray(root, "reminderDays", path, false).orEmpty().map { integer(it, "$path.reminderDays", false) },
            optionalNumber(root, "snoozedUntil", path), extras(root, BoardAssessment.FIELDS), root.keys.intersect(BoardAssessment.FIELDS))
    }

    private fun validateProposal(value: BoardProposal) {
        ensure(value.format == "habits.semester-board-proposal" && value.version == 1, "proposal", "unsupported format or version")
        uuid(value.proposalID, "proposal.proposalID")
        boundedText(value.source, "proposal.source", 300, trimmed = true)
        value.expectedBoardDigest?.let { ensure(digestPattern.matches(it), "proposal.expectedBoardDigest", "must be a lowercase SHA-256 digest") }
        ensure(value.semester.schemaVersion == 3, "semester.schemaVersion", "proposal requires schema 3")
        validateBoard(value.semester, proposal = true, existingReferences = value.expectedBoardDigest != null)
    }

    private fun validateBoard(board: BoardSemester, proposal: Boolean, existingReferences: Boolean) {
        ensure(board.schemaVersion in 1..3 && (!proposal || board.schemaVersion == 3), "semester.schemaVersion", "unsupported schema")
        fields(board.unknownFields, board.providedFields, BoardSemester.FIELDS, "semester", proposal)
        uuid(board.id, "semester.id"); name(board.title, "semester.title")
        if (proposal) boundedText(board.title, "semester.title", 200, trimmed = true)
        ensure(board.weekCount in 1..52 && board.courses.size <= MAX_COURSES && board.statuses.size <= MAX_STATUSES, "semester", "counts exceed supported bounds")
        if (proposal) ensure(("startDate" in board.providedFields) == ("endDate" in board.providedFields), "semester", "date range fields must be paired")
        ensure((board.startDate == null) == (board.endDate == null), "semester", "date range must be paired")
        if (board.startDate != null && board.endDate != null) {
            val distance = ChronoUnit.DAYS.between(date(board.startDate, "semester.startDate"), date(board.endDate, "semester.endDate"))
            ensure(distance in 0L..<364L && (distance + 7) / 7 == board.weekCount.toLong(), "semester", "date range must agree with teaching weeks")
        }
        val zone = if (proposal) board.timeZoneIdentifier?.let { proposalZone(it) } else null
        val statusIds = mutableSetOf<String>()
        board.statuses.forEachIndexed { index, status ->
            val path = "semester.statuses[$index]"
            fields(status.unknownFields, status.providedFields, BoardStatus.FIELDS, path, proposal)
            ensure(statusIds.add(uuid(status.id, "$path.id")), path, "duplicate status identity")
            name(status.name, "$path.name")
            if (proposal) boundedText(status.name, "$path.name", 200, trimmed = true)
            ensure(Regex("#[0-9a-fA-F]{6}").matches(status.colorHex), "$path.colorHex", "must be #RRGGBB")
        }
        val courseIds = mutableSetOf<String>()
        var total = 0
        board.courses.forEachIndexed { index, course ->
            val path = "semester.courses[$index]"
            fields(course.unknownFields, course.providedFields, BoardCourse.FIELDS + if (proposal) emptySet() else BoardCourse.LEGACY_FIELDS, path, proposal)
            ensure(courseIds.add(uuid(course.id, "$path.id")), path, "duplicate course identity")
            name(course.name, "$path.name")
            if (proposal) boundedText(course.name, "$path.name", 200, trimmed = true)
            ensure(course.activeWeeks.size <= 52 && course.activeWeeks.all { it in 1..52 } && course.activeWeeks.distinct().size == course.activeWeeks.size && course.entries.keys.all { it in 1..52 }, path, "invalid course week")
            if (proposal) {
                course.moduleCode?.let { boundedText(it, "$path.moduleCode", 200) }
                course.scheduleNote?.let { boundedText(it, "$path.scheduleNote", 4000, notes = true) }
                course.sourceURL?.let { source ->
                    boundedText(source, "$path.sourceURL", 2048)
                    if (source.isNotEmpty()) {
                        val uri = runCatching { URI(source) }.getOrNull()
                        ensure(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null, "$path.sourceURL", "must be HTTP(S) without credentials")
                    }
                }
            }
            course.entries.forEach { (_, entry) ->
                fields(entry.unknownFields, entry.providedFields, BoardWeekEntry.FIELDS, "$path.entries", proposal)
                entry.statusID?.let { ensure(uuid(it, "$path.entries.statusID") in statusIds || existingReferences, "$path.entries.statusID", "unknown status reference") }
                if (proposal) boundedText(entry.notes, "$path.entries.notes", 4000, notes = true)
            }
            val assessmentIds = mutableSetOf<String>()
            val occurrences = mutableSetOf<Pair<String, Int>>()
            val ranges = mutableMapOf<String, Pair<LocalDate, LocalDate>>()
            course.assessments.forEachIndexed { itemIndex, item ->
                total += 1; ensure(total <= MAX_ASSESSMENTS, "semester.courses", "too many assessments")
                val itemPath = "$path.assessments[$itemIndex]"
                val known = if (proposal) BoardAssessment.FIELDS - BoardAssessment.DEVICE_FIELDS - setOf("recurrence") else BoardAssessment.FIELDS
                fields(item.unknownFields, item.providedFields, known, itemPath, proposal)
                ensure(assessmentIds.add(uuid(item.id, "$itemPath.id")), itemPath, "duplicate assessment identity within course")
                name(item.title, "$itemPath.title")
                ensure(item.kind in setOf("exam", "presentation", "essay", "deadline") && item.state in setOf("pending", "completed", "dismissed"), itemPath, "unsupported assessment kind or state")
                val due = date(item.dueDate, "$itemPath.dueDate")
                item.dueTime?.let { clock ->
                    ensure(timePattern.matches(clock), "$itemPath.dueTime", "must be HH:mm")
                    if (proposal) {
                        ensure(zone != null || existingReferences, "$itemPath.dueTime", "source timezone required for a timed new assessment")
                        // A recorded context or existing-board proposal can contain an unchanged
                        // imported raw clock. Only the merge-aware writer knows whether its date,
                        // time or inherited zone changes; it validates those effective changes.
                        if (!existingReferences) zone?.let { ensure(it.rules.getValidOffsets(LocalDateTime.of(due, LocalTime.parse(clock))).size == 1, "$itemPath.dueTime", "clock is skipped or repeated by DST") }
                    }
                }
                ensure(item.reminderDays.all { it in 0..365 } && item.reminderDays.distinct().size == item.reminderDays.size, "$itemPath.reminderDays", "invalid reminder offsets")
                for (stamp in listOfNotNull(item.completedAt, item.snoozedUntil)) ensure(stamp.isFinite(), itemPath, "invalid timestamp")
                if (proposal) {
                    boundedText(item.title, "$itemPath.title", 200, trimmed = true); boundedText(item.notes, "$itemPath.notes", 4000, notes = true)
                    ensure(item.reminderDays.isEmpty() && item.snoozedUntil == null && item.recurrence == null, itemPath, "device and recurrence fields are not proposal data")
                    item.completedAt?.let {
                        referenceDateToInstant(it)
                        ensure(item.state == "completed" || ("state" !in item.providedFields && existingReferences), "$itemPath.completedAt", "requires recorded completed state")
                    }
                }
                item.recurrence?.let { raw ->
                    val r = obj(raw, "$itemPath.recurrence")
                    required(r, recurrenceFields, "$itemPath.recurrence")
                    val series = uuid(str(r, "seriesID", "$itemPath.recurrence"), "$itemPath.recurrence.seriesID")
                    val scheduled = date(str(r, "scheduledDate", "$itemPath.recurrence"), "$itemPath.recurrence.scheduledDate")
                    val end = date(str(r, "endDate", "$itemPath.recurrence"), "$itemPath.recurrence.endDate")
                    val interval = int(r, "intervalWeeks", "$itemPath.recurrence")
                    val occurrence = int(r, "occurrenceIndex", "$itemPath.recurrence")
                    val exception = bool(r["isException"], "$itemPath.recurrence.isException")
                    ensure(interval in setOf(1, 2) && occurrence in 0..<104 && end >= scheduled && (exception || due == scheduled), "$itemPath.recurrence", "invalid occurrence metadata")
                    ensure(occurrences.add(series to occurrence), "$itemPath.recurrence", "duplicate recurrence occurrence")
                    val old = ranges[series]
                    ranges[series] = (old?.first?.let { minOf(it, scheduled) } ?: scheduled) to (old?.second?.let { maxOf(it, end) } ?: end)
                }
            }
            ranges.values.forEach { (first, last) ->
                val limit = first.plusYears(2)
                ensure(limit.year <= 9999 && last <= limit, "$path.recurrence", "series exceeds two years")
            }
        }
    }

    private fun semesterJson(b: BoardSemester): JsonObject = wire(b.unknownFields).apply {
        put("schemaVersion", JsonPrimitive(b.schemaVersion)); put("id", JsonPrimitive(b.id)); put("title", JsonPrimitive(b.title)); put("weekCount", JsonPrimitive(b.weekCount))
        put("statuses", JsonArray(b.statuses.map { s -> JsonObject(wire(s.unknownFields).apply { put("id", JsonPrimitive(s.id)); put("name", JsonPrimitive(s.name)); put("colorHex", JsonPrimitive(s.colorHex)) }) }))
        put("courses", JsonArray(b.courses.map(::courseJson)))
        optional("startDate", b.providedFields, b.startDate?.let(::JsonPrimitive)); optional("endDate", b.providedFields, b.endDate?.let(::JsonPrimitive))
        optional("showExamWeeks", b.providedFields, JsonPrimitive(b.showExamWeeks)); optional("timeZoneIdentifier", b.providedFields, b.timeZoneIdentifier?.let(::JsonPrimitive))
    }.let(::JsonObject)

    private fun courseJson(c: BoardCourse): JsonObject = wire(c.unknownFields).apply {
        put("id", JsonPrimitive(c.id)); put("name", JsonPrimitive(c.name)); optional("activeWeeks", c.providedFields, JsonArray(c.activeWeeks.map(::JsonPrimitive)))
        put("entries", JsonObject(c.entries.mapKeys { it.key.toString() }.mapValues { (_, e) -> JsonObject(wire(e.unknownFields).apply {
            optional("statusID", e.providedFields, e.statusID?.let(::JsonPrimitive)); optional("notes", e.providedFields, JsonPrimitive(e.notes))
        }) }))
        optional("moduleCode", c.providedFields, c.moduleCode?.let(::JsonPrimitive)); optional("sourceURL", c.providedFields, c.sourceURL?.let(::JsonPrimitive)); optional("scheduleNote", c.providedFields, c.scheduleNote?.let(::JsonPrimitive))
        optional("assessments", c.providedFields, JsonArray(c.assessments.map(::assessmentJson)))
    }.let(::JsonObject)

    private fun assessmentJson(a: BoardAssessment): JsonObject = wire(a.unknownFields).apply {
        put("id", JsonPrimitive(a.id)); put("title", JsonPrimitive(a.title)); put("kind", JsonPrimitive(a.kind)); put("dueDate", JsonPrimitive(a.dueDate))
        optional("dueTime", a.providedFields, a.dueTime?.let(::JsonPrimitive)); optional("notes", a.providedFields, JsonPrimitive(a.notes)); optional("state", a.providedFields, JsonPrimitive(a.state))
        optional("completedAt", a.providedFields, a.completedAt?.let(::JsonPrimitive)); optional("recurrence", a.providedFields, a.recurrence)
        optional("reminderDays", a.providedFields, JsonArray(a.reminderDays.map(::JsonPrimitive))); optional("snoozedUntil", a.providedFields, a.snoozedUntil?.let(::JsonPrimitive))
    }.let(::JsonObject)

    private fun proposalJson(p: BoardProposal): JsonObject = linkedMapOf<String, JsonElement>(
        "format" to JsonPrimitive(p.format), "version" to JsonPrimitive(p.version), "proposalID" to JsonPrimitive(p.proposalID), "source" to JsonPrimitive(p.source), "semester" to semesterJson(p.semester)
    ).apply { p.expectedBoardDigest?.let { put("expectedBoardDigest", JsonPrimitive(it)) } }.let(::JsonObject)

    private fun wire(extra: JsonObject) = extra.toMutableMap()
    private fun MutableMap<String, JsonElement>.optional(key: String, presence: Set<String>, value: JsonElement?) { if (key in presence) this[key] = value ?: JsonNull }
    private fun encoded(element: JsonElement): String = sorted(element).toString().also { parse(it) }
    // Python's bridge uses json.dumps(ensure_ascii=False, sort_keys=True). In particular,
    // Foundation Double timestamps such as 812160000.0 must not hash as Java's 8.1216E8.
    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) -> JsonPrimitive(key).toString() + ":" + canonicalJson(value) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonPrimitive -> if (element.isString || element is JsonNull || element.content in setOf("true", "false") || integerPattern.matches(element.content)) element.toString()
            else canonicalFloat(element.content)
    }
    private fun canonicalFloat(content: String): String {
        val value = content.toDoubleOrNull() ?: invalid("timestamp", "invalid number")
        ensure(value.isFinite(), "timestamp", "must be finite")
        if (value == 0.0) return if (content.startsWith('-')) "-0.0" else "0.0"
        val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
        val exponent = decimal.precision() - decimal.scale() - 1
        if (exponent < -4 || exponent >= 16) {
            val mantissa = decimal.movePointLeft(exponent).toPlainString()
            val sign = if (exponent < 0) "-" else "+"
            return mantissa + "e" + sign + kotlin.math.abs(exponent).toString().padStart(2, '0')
        }
        val plain = decimal.toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }
    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
    private fun parse(text: String): JsonElement = try { AcademicPlanCodec.parseStrictJson(text, MAX_DATA_BYTES) }
        catch (_: Exception) { invalid("JSON", "must be valid bounded UTF-8 without duplicate keys") }
    private inline fun <T> boundary(block: () -> T): T = try { block() } catch (failure: BoardValidationException) { throw failure }
        catch (_: Exception) { invalid("board", "invalid academic data") }
    private fun obj(value: JsonElement?, path: String) = value as? JsonObject ?: invalid(path, "must be an object")
    private fun arr(value: JsonElement?, path: String) = value as? JsonArray ?: invalid(path, "must be an array")
    private fun required(value: Map<String, JsonElement>, keys: Set<String>, path: String) { ensure(value.keys.containsAll(keys), path, "missing required fields") }
    private fun shape(value: JsonObject, known: Set<String>, requiredKeys: Set<String>, path: String) { required(value, requiredKeys, path); ensure(value.keys.all { it in known }, path, "unsupported fields") }
    private fun str(value: Map<String, JsonElement>, key: String, path: String): String {
        val primitive = value[key] as? JsonPrimitive ?: invalid("$path.$key", "must be a string")
        ensure(primitive !is JsonNull && primitive.isString, "$path.$key", "must be a string"); return primitive.content
    }
    private fun optionalString(value: Map<String, JsonElement>, key: String, path: String, nullAllowed: Boolean = true): String? {
        if (key !in value) return null
        if (value[key] is JsonNull) { ensure(nullAllowed, "$path.$key", "must be a string"); return null }
        return str(value, key, path)
    }
    private fun integer(value: JsonElement?, path: String, strict: Boolean = true): Int {
        val p = value as? JsonPrimitive ?: invalid(path, "must be an integer")
        ensure(!p.isString && p !is JsonNull, path, "must be an integer")
        return try { if (strict) { ensure(integerPattern.matches(p.content), path, "must be an integer"); p.content.toInt() } else BigDecimal(p.content).intValueExact() }
            catch (_: Exception) { invalid(path, "must be a bounded integer") }
    }
    private fun int(value: Map<String, JsonElement>, key: String, path: String) = integer(value[key], "$path.$key")
    private fun bool(value: JsonElement?, path: String): Boolean {
        val p = value as? JsonPrimitive ?: invalid(path, "must be a boolean")
        ensure(!p.isString && p !is JsonNull && p.content in setOf("true", "false"), path, "must be a boolean"); return p.content == "true"
    }
    private fun optionalBool(value: Map<String, JsonElement>, key: String, path: String, closed: Boolean): Boolean? {
        if (key !in value || (!closed && value[key] is JsonNull)) return null
        return bool(value[key], "$path.$key")
    }
    private fun optionalArray(value: Map<String, JsonElement>, key: String, path: String, closed: Boolean): JsonArray? {
        if (key !in value || (!closed && value[key] is JsonNull)) return null
        return arr(value[key], "$path.$key")
    }
    private fun optionalNumber(value: Map<String, JsonElement>, key: String, path: String): Double? {
        if (key !in value || value[key] is JsonNull) return null
        val p = value[key] as? JsonPrimitive ?: invalid("$path.$key", "must be a number")
        val number = p.content.toDoubleOrNull()
        ensure(!p.isString && number != null && number.isFinite(), "$path.$key", "must be a finite number")
        return number
    }
    private fun extras(value: JsonObject, known: Set<String>) = JsonObject(value.filterKeys { it !in known })
    private fun fields(extra: JsonObject, presence: Set<String>, known: Set<String>, path: String, closed: Boolean) {
        ensure(extra.keys.none { it in known } && presence.all { it in known }, path, "invalid compatibility or field-presence metadata")
        ensure(!closed || extra.isEmpty(), path, "unknown compatibility fields are not proposal data")
    }
    private fun uuid(value: String, path: String): String {
        ensure(uuidPattern.matches(value), path, "must be a UUID")
        return try { UUID.fromString(value).toString() } catch (_: Exception) { invalid(path, "must be a UUID") }
    }
    private fun date(value: String, path: String): LocalDate {
        ensure(datePattern.matches(value), path, "must be YYYY-MM-DD")
        return try { LocalDate.parse(value).also { ensure(it.year in 1..9999, path, "invalid civil year") } } catch (_: Exception) { invalid(path, "invalid civil date") }
    }
    private fun proposalZone(value: String): ZoneId {
        ensure(zonePattern.matches(value), "semester.timeZoneIdentifier", "must be a recognized IANA timezone")
        return try { ZoneId.of(value) } catch (_: Exception) { invalid("semester.timeZoneIdentifier", "must be a recognized IANA timezone") }
    }
    private fun name(value: String, path: String) { ensure(value.trim().isNotEmpty(), path, "must not be blank"); textControls(value, path, false) }
    private fun boundedText(value: String, path: String, limit: Int, notes: Boolean = false, trimmed: Boolean = false) {
        ensure(value.codePointCount(0, value.length) <= limit && (!trimmed || (value.isNotEmpty() && value == value.trim())), path, "invalid text length or surrounding whitespace")
        textControls(value, path, notes)
    }
    private fun textControls(value: String, path: String, notes: Boolean) {
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index); index += Character.charCount(point)
            val control = Character.getType(point) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())
            ensure(point !in 0xd800..0xdfff && (!control || (notes && point in setOf(9, 10, 13))), path, "contains unsupported control characters")
        }
    }
    private fun ensure(condition: Boolean, path: String, reason: String) { if (!condition) invalid(path, reason) }
    private fun invalid(path: String, reason: String): Nothing = throw BoardValidationException("$path $reason")
}
