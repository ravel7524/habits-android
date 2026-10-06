package ch.habits.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.util.AtomicFile
import ch.habits.android.core.AcademicPlanCodec
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Device-only opt-in and a read-only, non-persisted CalendarContract snapshot. */
class CalendarConnection internal constructor(
    context: Context,
    private val source: CalendarReadSource,
    private val settingsFile: File,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    constructor(context: Context) : this(
        context.applicationContext,
        AndroidCalendarReadSource(context.applicationContext),
        File(context.noBackupFilesDir, "calendar/connection.json"),
        Clock.systemUTC(),
    )

    private val mutex = Mutex()
    private val atomicSettings = AtomicFile(settingsFile)
    private var configuration = CalendarConfiguration()
    private var unreadableSettings = false
    private var saveFailed = false
    private var generation = 0L
    private var activeSignal: CancellationSignal? = null
    private val mutableState = MutableStateFlow(loadSettings())
    val state: StateFlow<CalendarState> = mutableState.asStateFlow()

    suspend fun setEnabled(value: Boolean) {
        updateConfiguration { it.copy(enabled = value) }
        refresh()
    }

    suspend fun selectCalendars(ids: Set<Long>) {
        require(ids.size <= CalendarProjection.MAX_CALENDARS && ids.all { it > 0 }) { "Select valid calendars within the supported limit." }
        updateConfiguration { old ->
            val choices = state.value.calendarChoices.filter { it.visible }.map { it.id }.toSet()
            require(ids.all { it in choices }) { "A selected calendar is unavailable or hidden. Refresh and choose current calendars." }
            old.copy(selectedCalendarIDs = ids.sorted())
        }
        refresh()
    }

    /** Explicit recovery clears connection choices only, preserving any old configuration bytes. */
    suspend fun resetLocalSettings() = withContext(Dispatchers.IO) {
        mutex.withLock {
            invalidateRead()
            if (settingsFile.exists()) {
                val preserved = File(settingsFile.parentFile, "connection.preserved-${UUID.randomUUID()}.json")
                preserved.outputStream().use { output ->
                    settingsFile.inputStream().use { input -> input.copyTo(output, bufferSize = 8 * 1024) }
                    output.flush()
                    if (output is java.io.FileOutputStream) output.fd.sync()
                }
            }
            persist(CalendarConfiguration())
            configuration = CalendarConfiguration()
            unreadableSettings = false
            saveFailed = false
            mutableState.value = CalendarState(permissionGranted = source.hasPermission())
        }
    }

    suspend fun refresh() {
        val request = mutex.withLock {
            invalidateRead()
            if (unreadableSettings || saveFailed) {
                mutableState.value = cleared(CalendarCoverage.SETTINGS_ERROR,
                    error = "Calendar settings could not be saved or read. The original file was preserved; no calendar events are shown.")
                null
            } else if (!configuration.enabled) {
                mutableState.value = cleared(CalendarCoverage.OFF)
                null
            } else if (!source.hasPermission()) {
                mutableState.value = cleared(CalendarCoverage.PERMISSION_REQUIRED, permission = false)
                null
            } else {
                val signal = CancellationSignal()
                activeSignal = signal
                val currentZone = zone()
                val window = CalendarProjection.window(clock.instant().atZone(currentZone).toLocalDate(), currentZone)
                mutableState.value = cleared(CalendarCoverage.LOADING, permission = true, busy = true,
                    choices = state.value.calendarChoices)
                CalendarRequest(generation, configuration.selectedCalendarIDs.toSet(), window, signal)
            }
        } ?: return

        try {
            val result = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val calendars = source.calendars(request.signal)
                val choices = calendars.choices
                val visible = choices.filter { it.visible }.map { it.id }.toSet()
                when {
                    calendars.truncated -> CalendarResult(choices, CalendarCoverage.TRUNCATED,
                        error = "Too many calendars were returned. Calendar coverage is incomplete; no events are shown.")
                    request.selectedIDs.isEmpty() -> CalendarResult(choices, CalendarCoverage.NO_SELECTION)
                    request.selectedIDs.any { it !in visible } -> CalendarResult(choices, CalendarCoverage.SELECTION_UNAVAILABLE,
                        error = "A selected calendar is unavailable or hidden in the Calendar app. Choose current calendars.")
                    else -> {
                        val read = source.events(request.selectedIDs, choices, request.window, request.signal)
                        if (read.truncated || read.events.size > CalendarProjection.MAX_EVENTS) {
                            CalendarResult(choices, CalendarCoverage.TRUNCATED,
                                error = "More than 500 occurrences were returned. Calendar coverage is incomplete; no events are shown.")
                        } else {
                            require(read.events.all { it.calendarID in request.selectedIDs && it.endMillis >= it.startMillis })
                            val events = read.events.filter { CalendarProjection.inWindow(it, request.window) }
                                .distinctBy { it.id }.sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.id })
                            CalendarResult(choices, CalendarCoverage.AVAILABLE, events)
                        }
                    }
                }.also { currentCoroutineContext().ensureActive() }
            }
            mutex.withLock {
                if (request.generation != generation) return@withLock
                activeSignal = null
                if (!source.hasPermission()) mutableState.value = cleared(CalendarCoverage.PERMISSION_REQUIRED, permission = false)
                else mutableState.value = cleared(result.coverage, permission = true, choices = result.choices,
                    events = if (result.coverage == CalendarCoverage.AVAILABLE) result.events else emptyList(),
                    window = request.window, error = result.error, refreshedAt = clock.millis())
            }
        } catch (failure: Exception) {
            request.signal.cancel()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (request.generation == generation) {
                        activeSignal = null
                        val granted = source.hasPermission()
                        mutableState.value = cleared(if (granted) CalendarCoverage.UNAVAILABLE else CalendarCoverage.PERMISSION_REQUIRED,
                            permission = granted, error = if (granted) "Calendar events could not be read. Coverage is unknown; no events are shown." else null)
                    }
                }
            }
            if (failure is CancellationException) throw failure
        }
    }

    private suspend fun updateConfiguration(change: (CalendarConfiguration) -> CalendarConfiguration) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!unreadableSettings) { "Calendar settings are unreadable. Reset connection settings explicitly before changing them." }
            val next = change(configuration)
            invalidateRead()
            configuration = next // Disable/read selection takes effect before the disk write.
            mutableState.value = cleared(if (next.enabled) CalendarCoverage.LOADING else CalendarCoverage.OFF)
            try {
                persist(next)
                saveFailed = false
            } catch (failure: Exception) {
                saveFailed = true
                mutableState.value = cleared(CalendarCoverage.SETTINGS_ERROR,
                    error = "The calendar choice could not be saved. Events are hidden; retry saving before reading calendar data.")
                throw failure
            }
        }
    }

    private fun cleared(coverage: CalendarCoverage, permission: Boolean = source.hasPermission(), busy: Boolean = false,
                        choices: List<CalendarChoice> = emptyList(), events: List<CalendarEvent> = emptyList(),
                        window: CalendarWindow? = null, error: String? = null, refreshedAt: Long? = null) = CalendarState(
        enabled = configuration.enabled, selectedCalendarIDs = configuration.selectedCalendarIDs.toSet(),
        permissionGranted = permission, busy = busy, calendarChoices = choices, events = events,
        coverage = coverage, window = window, refreshedAtMillis = refreshedAt, error = error,
    )

    private fun invalidateRead() { generation += 1; activeSignal?.cancel(); activeSignal = null }

    private fun loadSettings(): CalendarState {
        if (!settingsFile.exists() && !File(settingsFile.path + ".bak").exists()) return CalendarState()
        return try {
            // Reject an oversized primary before AtomicFile legacy-backup recovery can replace it.
            require(!settingsFile.exists() || settingsFile.length() <= MAX_SETTINGS_BYTES)
            val legacyBackup = File(settingsFile.path + ".bak")
            require(!legacyBackup.exists() || legacyBackup.length() <= MAX_SETTINGS_BYTES)
            val bytes = atomicSettings.openRead().use { input ->
                val buffer = ByteArray(MAX_SETTINGS_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    check(read > 0)
                    count += read
                }
                require(count <= MAX_SETTINGS_BYTES)
                buffer.copyOf(count)
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val parsed = AcademicPlanCodec.parseStrictJson(text, MAX_SETTINGS_BYTES).jsonObject
            require(parsed.keys == setOf("version", "enabled", "selectedCalendarIDs"))
            val saved = json.decodeFromString<CalendarConfiguration>(text)
            require(saved.version == 1 && saved.selectedCalendarIDs.size <= CalendarProjection.MAX_CALENDARS &&
                saved.selectedCalendarIDs.all { it > 0 } && saved.selectedCalendarIDs.distinct().size == saved.selectedCalendarIDs.size)
            configuration = saved
            CalendarState(enabled = saved.enabled, selectedCalendarIDs = saved.selectedCalendarIDs.toSet(),
                coverage = if (saved.enabled) CalendarCoverage.UNAVAILABLE else CalendarCoverage.OFF)
        } catch (_: Exception) {
            unreadableSettings = true
            CalendarState(coverage = CalendarCoverage.SETTINGS_ERROR,
                error = "Calendar settings could not be read. The file was preserved; calendar reading is disabled.")
        }
    }

    private fun persist(value: CalendarConfiguration) {
        val parent = settingsFile.parentFile ?: error("Calendar settings directory is unavailable.")
        check(parent.isDirectory || parent.mkdirs()) { "Calendar settings directory could not be created." }
        val output = atomicSettings.startWrite()
        try {
            output.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            output.flush(); output.fd.sync()
            atomicSettings.finishWrite(output)
        } catch (failure: Exception) { atomicSettings.failWrite(output); throw failure }
    }

    private companion object {
        const val MAX_SETTINGS_BYTES = 16 * 1024
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    }
}

@Serializable
private data class CalendarConfiguration(val version: Int = 1, val enabled: Boolean = false, val selectedCalendarIDs: List<Long> = emptyList())
private data class CalendarRequest(val generation: Long, val selectedIDs: Set<Long>, val window: CalendarWindow, val signal: CancellationSignal)
private data class CalendarResult(val choices: List<CalendarChoice>, val coverage: CalendarCoverage,
                                  val events: List<CalendarEvent> = emptyList(), val error: String? = null)
internal data class CalendarChoicesRead(val choices: List<CalendarChoice>, val truncated: Boolean = false)
internal data class CalendarEventsRead(val events: List<CalendarEvent>, val truncated: Boolean = false)
internal interface CalendarReadSource {
    fun hasPermission(): Boolean
    fun calendars(signal: CancellationSignal): CalendarChoicesRead
    fun events(selected: Set<Long>, choices: List<CalendarChoice>, window: CalendarWindow, signal: CancellationSignal): CalendarEventsRead
}

/** Query only. Production does not request WRITE_CALENDAR or call provider mutation methods. */
internal class AndroidCalendarReadSource(private val context: Context) : CalendarReadSource {
    override fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun calendars(signal: CancellationSignal): CalendarChoicesRead {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.ACCOUNT_NAME)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val cursor = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection,
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_READ.toString()), CalendarContract.Calendars._ID + " ASC", signal)
            ?: error("Calendar list unavailable")
        cursor.use {
            val choices = mutableListOf<CalendarChoice>()
            while (it.moveToNext()) {
                signal.throwIfCanceled()
                if (choices.size == CalendarProjection.MAX_CALENDARS) return CalendarChoicesRead(choices, true)
                require(!it.isNull(0) && it.getLong(0) > 0)
                choices += CalendarChoice(it.getLong(0), it.getString(1).orEmpty(),
                    if (it.isNull(2)) null else it.getInt(2), !it.isNull(3) && it.getInt(3) != 0,
                    it.getString(4)?.takeIf { value -> value.isNotBlank() })
            }
            return CalendarChoicesRead(choices)
        }
    }

    override fun events(selected: Set<Long>, choices: List<CalendarChoice>, window: CalendarWindow, signal: CancellationSignal): CalendarEventsRead {
        val selectedIDs = selected.sorted()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, window.queryStartMillis)
        ContentUris.appendId(builder, window.queryEndMillis)
        val projection = arrayOf(CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS)
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${selectedIDs.joinToString(",") { "?" }}) AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?) AND " +
            "(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?)"
        val args = selectedIDs.map { it.toString() } + CalendarContract.Events.STATUS_CANCELED.toString() +
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString()
        val cursor = context.contentResolver.query(builder.build(), projection, selection, args.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC", signal)
            ?: error("Calendar instances unavailable")
        cursor.use {
            val events = mutableListOf<CalendarEvent>()
            while (it.moveToNext()) {
                signal.throwIfCanceled()
                if (events.size == CalendarProjection.MAX_EVENTS) return CalendarEventsRead(emptyList(), true)
                if ((!it.isNull(6) && it.getInt(6) == CalendarContract.Events.STATUS_CANCELED) ||
                    (!it.isNull(7) && it.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)) continue
                require((0..4).all { index -> !it.isNull(index) })
                val calendarID = it.getLong(0)
                val eventID = it.getLong(1)
                val begin = it.getLong(2)
                val end = it.getLong(3)
                val allDay = it.getInt(4) != 0
                require(calendarID in selected && eventID > 0 && end >= begin)
                if (allDay) {
                    require(end > begin && java.time.Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalTime() == java.time.LocalTime.MIDNIGHT &&
                        java.time.Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalTime() == java.time.LocalTime.MIDNIGHT)
                }
                events += CalendarEvent(CalendarProjection.instanceID(calendarID, eventID, begin), calendarID, eventID,
                    it.getString(5).orEmpty(), begin, end, allDay, choices.first { choice -> choice.id == calendarID }.displayName)
            }
            return CalendarEventsRead(events)
        }
    }
}
