package ch.habits.android.calendar

import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarConnectionTest {
    private lateinit var directory: File
    private lateinit var config: File
    private lateinit var source: FakeCalendarReadSource
    private lateinit var connection: CalendarConnection
    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), zone)

    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "calendar-connection-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        config = File(directory, "calendar/connection.json")
        source = FakeCalendarReadSource()
        connection = CalendarConnection(context, source, config, clock) { zone }
    }

    @After fun cleanup() { source.release.countDown(); directory.deleteRecursively() }

    private suspend fun loadEvents() { connection.setEnabled(true); connection.selectCalendars(setOf(1)) }

    @Test fun offDoesNotReadProviderEvenWhenPermissionAlreadyGranted() = runBlocking {
        connection.refresh()
        assertEquals(CalendarCoverage.OFF, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        assertEquals(0, source.calendarReads)
        assertEquals(0, source.eventReads)
        assertFalse(config.exists())
    }

    @Test fun denialHasUnknownCoverageAndDoesNotReadProvider() = runBlocking {
        source.granted = false
        connection.setEnabled(true)
        assertEquals(CalendarCoverage.PERMISSION_REQUIRED, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        assertTrue(connection.state.value.calendarChoices.isEmpty())
        assertEquals(0, source.calendarReads)
        assertEquals(0, source.eventReads)
    }

    @Test fun grantDoesNotAutomaticallyChooseAnyCalendar() = runBlocking {
        connection.setEnabled(true)
        assertEquals(CalendarCoverage.NO_SELECTION, connection.state.value.coverage)
        assertEquals(listOf(1L), connection.state.value.calendarChoices.map { it.id })
        assertTrue(connection.state.value.selectedCalendarIDs.isEmpty())
        assertTrue(connection.state.value.events.isEmpty())
        assertEquals(0, source.eventReads)
    }

    @Test fun revokedPermissionClearsPreviouslyLoadedEventsAndChoices() = runBlocking {
        loadEvents()
        assertEquals(CalendarCoverage.AVAILABLE, connection.state.value.coverage)
        assertEquals(1, connection.state.value.events.size)
        source.granted = false
        connection.refresh()
        assertEquals(CalendarCoverage.PERMISSION_REQUIRED, connection.state.value.coverage)
        assertFalse(connection.state.value.permissionGranted)
        assertTrue(connection.state.value.events.isEmpty())
        assertTrue(connection.state.value.calendarChoices.isEmpty())
    }

    @Test fun providerFailureReplacesOldEventsWithUnknownCoverage() = runBlocking {
        loadEvents()
        source.failRead = true
        connection.refresh()
        assertEquals(CalendarCoverage.UNAVAILABLE, connection.state.value.coverage)
        assertNotNull(connection.state.value.error)
        assertTrue(connection.state.value.events.isEmpty())
        assertFalse(connection.state.value.busy)
    }

    @Test fun overLimitResultIsNotDisplayedAsPartialCoverage() = runBlocking {
        loadEvents()
        source.values = (1..501).map { number -> source.event.copy(eventID = number.toLong(),
            id = CalendarProjection.instanceID(1, number.toLong(), source.event.startMillis)) }
        connection.refresh()
        assertEquals(CalendarCoverage.TRUNCATED, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        assertFalse(connection.state.value.covers(java.time.LocalDate.parse("2026-10-08"), zone))
    }

    @Test fun missingSelectedCalendarDoesNotSilentlyChooseAnotherSource() = runBlocking {
        loadEvents()
        source.choices = listOf(CalendarChoice(2, "Other synthetic calendar"))
        connection.refresh()
        assertEquals(CalendarCoverage.SELECTION_UNAVAILABLE, connection.state.value.coverage)
        assertEquals(setOf(1L), connection.state.value.selectedCalendarIDs)
        assertTrue(connection.state.value.events.isEmpty())
        connection.selectCalendars(emptySet())
        assertEquals(CalendarCoverage.NO_SELECTION, connection.state.value.coverage)
        assertTrue(connection.state.value.selectedCalendarIDs.isEmpty())
    }

    @Test fun lateProviderResultCannotReappearAfterDisable() = runBlocking {
        loadEvents()
        source.blockRead = true
        val read = launch(Dispatchers.Default) { connection.refresh() }
        assertTrue(source.started.await(5, TimeUnit.SECONDS))
        connection.setEnabled(false)
        assertEquals(CalendarCoverage.OFF, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        source.release.countDown()
        read.join()
        assertEquals(CalendarCoverage.OFF, connection.state.value.coverage)
        assertFalse(connection.state.value.enabled)
        assertTrue(connection.state.value.events.isEmpty())
        val restarted = CalendarConnection(InstrumentationRegistry.getInstrumentation().targetContext, source, config, clock) { zone }
        assertFalse(restarted.state.value.enabled)
    }

    @Test fun permissionRevokedDuringReadSuppressesItsLateEvents() = runBlocking {
        loadEvents()
        source.blockRead = true
        val read = launch(Dispatchers.Default) { connection.refresh() }
        assertTrue(source.started.await(5, TimeUnit.SECONDS))
        source.granted = false
        source.release.countDown()
        read.join()
        assertEquals(CalendarCoverage.PERMISSION_REQUIRED, connection.state.value.coverage)
        assertFalse(connection.state.value.permissionGranted)
        assertTrue(connection.state.value.events.isEmpty())
        assertTrue(connection.state.value.calendarChoices.isEmpty())
        assertFalse(connection.state.value.busy)
    }

    @Test fun cancelledReadClearsBusyAndRetainsUnknownCoverage() = runBlocking {
        loadEvents()
        source.blockRead = true
        val read = launch(Dispatchers.Default) { connection.refresh() }
        assertTrue(source.started.await(5, TimeUnit.SECONDS))
        read.cancel()
        source.release.countDown()
        read.join()
        assertEquals(CalendarCoverage.UNAVAILABLE, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        assertFalse(connection.state.value.busy)
    }

    @Test fun optInAndSelectionPersistWithoutEventOrAccountContent() = runBlocking {
        val workspace = File(directory, "workspace.json").apply { writeText("Untouched planning fixture") }
        loadEvents()
        val text = config.readText()
        assertTrue(text.contains("selectedCalendarIDs"))
        assertFalse(text.contains(source.event.title))
        assertFalse(text.contains(source.choices.single().displayName))
        assertFalse(text.contains(source.choices.single().accountLabel!!))
        assertEquals("Untouched planning fixture", workspace.readText())
        val restarted = CalendarConnection(InstrumentationRegistry.getInstrumentation().targetContext, source, config, clock) { zone }
        assertEquals(setOf(1L), restarted.state.value.selectedCalendarIDs)
        assertTrue("Event snapshots must not persist across restart", restarted.state.value.events.isEmpty())
        restarted.refresh()
        assertEquals(CalendarCoverage.AVAILABLE, restarted.state.value.coverage)
        assertEquals(1, restarted.state.value.events.size)
    }

    @Test fun unreadableConfigurationIsPreservedUntilExplicitReset() = runBlocking {
        config.parentFile!!.mkdirs()
        val original = "{damaged calendar preferences"
        config.writeText(original)
        val restarted = CalendarConnection(InstrumentationRegistry.getInstrumentation().targetContext, source, config, clock) { zone }
        restarted.refresh()
        assertEquals(CalendarCoverage.SETTINGS_ERROR, restarted.state.value.coverage)
        assertEquals(original, config.readText())
        assertEquals(0, source.calendarReads)
        restarted.resetLocalSettings()
        assertEquals(CalendarCoverage.OFF, restarted.state.value.coverage)
        assertFalse(restarted.state.value.enabled)
        assertTrue(restarted.state.value.selectedCalendarIDs.isEmpty())
        val preserved = config.parentFile!!.listFiles()!!.single { it.name.startsWith("connection.preserved-") }
        assertEquals(original, preserved.readText())
    }

    @Test fun oversizedConfigurationIsRejectedWithoutProviderReadsOrPrimaryReset() = runBlocking {
        config.parentFile!!.mkdirs()
        val size = 64L * 1024 * 1024
        RandomAccessFile(config, "rw").use { output -> output.setLength(size); output.seek(0); output.write('{'.code) }
        val legacyBackup = File(config.path + ".bak").apply {
            writeText("{\"version\":1,\"enabled\":false,\"selectedCalendarIDs\":[]}")
        }
        val backupBytes = legacyBackup.readBytes()
        val restarted = CalendarConnection(InstrumentationRegistry.getInstrumentation().targetContext, source, config, clock) { zone }
        restarted.refresh()
        assertEquals(CalendarCoverage.SETTINGS_ERROR, restarted.state.value.coverage)
        assertTrue(restarted.state.value.events.isEmpty())
        assertEquals(0, source.calendarReads)
        assertEquals(0, source.eventReads)
        assertEquals(size, config.length())
        assertEquals('{'.code, config.inputStream().use { it.read() })
        assertArrayEquals(backupBytes, legacyBackup.readBytes())
        assertEquals(2, config.parentFile!!.listFiles()!!.size)
    }

    @Test fun explicitResetPreservesOversizedConfigurationByStreaming() = runBlocking {
        config.parentFile!!.mkdirs()
        val block = ByteArray(8 * 1024) { (it % 251).toByte() }
        config.outputStream().use { output -> repeat(256) { output.write(block) } }
        val size = config.length()
        val digest = streamingDigest(config)
        val restarted = CalendarConnection(InstrumentationRegistry.getInstrumentation().targetContext, source, config, clock) { zone }
        assertEquals(CalendarCoverage.SETTINGS_ERROR, restarted.state.value.coverage)
        restarted.resetLocalSettings()
        val preserved = config.parentFile!!.listFiles()!!.single { it.name.startsWith("connection.preserved-") }
        assertEquals(size, preserved.length())
        assertArrayEquals(digest, streamingDigest(preserved))
        assertEquals(CalendarCoverage.OFF, restarted.state.value.coverage)
        assertFalse(restarted.state.value.enabled)
        assertEquals(0, source.calendarReads)
        assertEquals(0, source.eventReads)
    }

    private fun streamingDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest()
    }

    @Test fun failedPreferenceWriteHidesEventsAndPreservesPriorSettings() = runBlocking {
        loadEvents()
        val before = config.readText()
        val blocker = File(config.path + ".new").apply { check(mkdir()); File(this, "keep").writeText("fixture") }
        var failed = false
        try { connection.setEnabled(false) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(CalendarCoverage.SETTINGS_ERROR, connection.state.value.coverage)
        assertTrue(connection.state.value.events.isEmpty())
        assertEquals(before, config.readText())
        assertTrue(blocker.exists())
    }
}

private class FakeCalendarReadSource : CalendarReadSource {
    var granted = true
    var failRead = false
    var blockRead = false
    var calendarReads = 0
    var eventReads = 0
    var choices = listOf(CalendarChoice(1, "Synthetic source", accountLabel = "fixture-account.invalid"))
    val event = CalendarEvent(CalendarProjection.instanceID(1, 2, Instant.parse("2026-10-08T11:00:00Z").toEpochMilli()),
        1, 2, "Private synthetic occurrence", Instant.parse("2026-10-08T11:00:00Z").toEpochMilli(),
        Instant.parse("2026-10-08T12:00:00Z").toEpochMilli(), false, "Synthetic source")
    var values = listOf(event)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun hasPermission(): Boolean = granted
    override fun calendars(signal: CancellationSignal): CalendarChoicesRead { calendarReads += 1; return CalendarChoicesRead(choices) }
    override fun events(selected: Set<Long>, choices: List<CalendarChoice>, window: CalendarWindow, signal: CancellationSignal): CalendarEventsRead {
        eventReads += 1
        if (failRead) error("Synthetic unavailable provider")
        if (blockRead) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        return CalendarEventsRead(values)
    }
}
