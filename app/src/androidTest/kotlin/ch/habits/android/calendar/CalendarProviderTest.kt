package ch.habits.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Writes exist only in this test APK, to a uniquely named LOCAL calendar removed in finally. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CalendarProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var folder: File
    private lateinit var connection: CalendarConnection
    private lateinit var account: String
    private var calendarID: Long? = null
    private lateinit var day: LocalDate

    @Before fun createOwnedSyntheticCalendar() {
        assumeTrue("Provider mutations require an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
        context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        folder = File(context.cacheDir, "calendar-provider-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        account = "habits-calendar-fixture-${UUID.randomUUID()}@invalid.local"
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Habits synthetic calendar")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff2244aa.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val inserted = context.contentResolver.insert(syncURI(CalendarContract.Calendars.CONTENT_URI), values)
            ?: error("Synthetic calendar could not be created")
        calendarID = ContentUris.parseId(inserted)
        day = LocalDate.now(ZoneOffset.UTC).plusDays(2)
        connection = CalendarConnection(context, AndroidCalendarReadSource(context), File(folder, "calendar.json"), Clock.systemUTC()) { ZoneId.systemDefault() }
    }

    @After fun removeOnlyOwnedFixture() {
        try {
            if (::context.isInitialized && ::account.isInitialized) calendarID?.let { id ->
                context.contentResolver.delete(syncURI(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)), null, null)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            if (::folder.isInitialized) folder.deleteRecursively()
        }
    }

    private fun syncURI(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun insert(title: String, start: Long, end: Long? = start + 3_600_000, allDay: Boolean = false,
                       repeat: String? = null, status: Int = CalendarContract.Events.STATUS_CONFIRMED,
                       selfStatus: Int = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarID!!)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            if (repeat == null) put(CalendarContract.Events.DTEND, end!!) else {
                put(CalendarContract.Events.RRULE, repeat)
                put(CalendarContract.Events.DURATION, "PT1H")
            }
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.STATUS, status)
            put(CalendarContract.Events.SELF_ATTENDEE_STATUS, selfStatus)
        }
        return ContentUris.parseId(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Synthetic event could not be created"))
    }

    private suspend fun readSelected() { connection.setEnabled(true); connection.selectCalendars(setOf(calendarID!!)) }

    @Test fun actualProviderExpandsRecurrenceAndKeepsUtcAllDayDates() = runBlocking {
        val recurringStart = day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val recurringID = insert("Synthetic recurring event", recurringStart, repeat = "FREQ=DAILY;COUNT=3")
        val allDayID = insert("Synthetic all-day event", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        readSelected()
        assertEquals(CalendarCoverage.AVAILABLE, connection.state.value.coverage)
        val occurrences = connection.state.value.events.filter { it.eventID == recurringID }
        assertEquals(3, occurrences.size)
        assertEquals(3, occurrences.map { it.id }.toSet().size)
        assertTrue(occurrences.all { it.calendarID == calendarID })
        val allDay = connection.state.value.events.single { it.eventID == allDayID }
        assertTrue(CalendarProjection.overlapsDay(allDay, day, ZoneId.of("America/Los_Angeles")))
        assertFalse(CalendarProjection.overlapsDay(allDay, day.minusDays(1), ZoneId.of("America/Los_Angeles")))
        assertFalse(CalendarProjection.overlapsDay(allDay, day.plusDays(1), ZoneId.of("Pacific/Kiritimati")))
        assertFalse("Production APK must not request calendar writes", context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.WRITE_CALENDAR))
    }

    @Test fun cancelledAndDeclinedOwnedEventsAreNotDisplayed() = runBlocking {
        val start = day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val accepted = insert("Accepted synthetic event", start)
        val cancelled = insert("Cancelled synthetic event", start + 2 * 3_600_000, status = CalendarContract.Events.STATUS_CANCELED)
        val declined = insert("Declined synthetic event", start + 4 * 3_600_000, selfStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
        val deleted = insert("Deleted synthetic event", start + 6 * 3_600_000)
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, deleted), null, null)
        readSelected()
        assertEquals(CalendarCoverage.AVAILABLE, connection.state.value.coverage)
        assertTrue(connection.state.value.events.any { it.eventID == accepted })
        assertTrue(connection.state.value.events.none { it.eventID == cancelled || it.eventID == declined || it.eventID == deleted })
    }

    @Test fun hiddenPreviouslySelectedCalendarClearsCoverage() = runBlocking {
        insert("Synthetic event before hiding", day.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        readSelected()
        assertEquals(1, connection.state.value.events.size)
        context.contentResolver.update(syncURI(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarID!!)),
            ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, 0) }, null, null)
        connection.refresh()
        assertEquals(CalendarCoverage.SELECTION_UNAVAILABLE, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
    }
}
