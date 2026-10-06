package ch.habits.android.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.data.WorkTask
import ch.habits.android.data.Preferences
import ch.habits.android.data.WorkspaceRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Source/preferences use isolated cache stores. Permission/channel checks run in the test app. */
@RunWith(AndroidJUnit4::class)
class AndroidRemindersTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var repository: WorkspaceRepository
    private lateinit var store: ReminderStateStore
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val tokens = mutableSetOf<String>()
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        directory = File(context.cacheDir, "reminder-tests-${UUID.randomUUID()}").apply { mkdirs() }
        repository = WorkspaceRepository(File(directory, "workspace.json"))
        runBlocking { repository.savePreferences(Preferences(language = "en")) }
        store = ReminderStateStore(File(directory, "reminders.json"))
        AndroidReminders.createChannels(context)
    }
    @After fun cleanup() {
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        val state = runCatching { store.read() }.getOrNull()
        tokens += state?.scheduled?.map { it.token }.orEmpty()
        tokens += state?.delivered?.keys.orEmpty()
        for (token in tokens) {
            AndroidReminders.cancelAlarm(context, token)
            context.getSystemService(NotificationManager::class.java).cancel(token, 1)
        }
        directory.deleteRecursively()
    }
    private suspend fun armed(): Pair<WorkTask, ReminderPlan> {
        val task = WorkTask(title = "Synthetic private deadline", dueDate = "2030-01-02", dueTime = "09:00", sourceZone = "UTC")
        repository.saveTask(task)
        store.save(ReminderDeviceState(preferences = ReminderPreferences(tasksEnabled = true)))
        AndroidReminders.reconcileUsing(context, repository, store, now, zone)
        val plan = store.read().scheduled.single()
        tokens += plan.token
        return task to plan
    }
    private fun posted(token: String): android.service.notification.StatusBarNotification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag == token }?.let { return it }
            Thread.sleep(20)
        }
        error("The synthetic reminder was not posted within the test window")
    }
    @Test fun explicitChannelsAndBlockedPermissionPreventPosting() = runBlocking {
        org.junit.Assume.assumeTrue("The runtime notification permission gate applies on API 33+", Build.VERSION.SDK_INT >= 33)
        val (_, plan) = armed()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = ReminderKind.entries.map { AndroidReminders.channel(it) }.distinct()
        assertEquals("Reminder kinds retain five independent Android channels", 5, channels.size)
        assertTrue("Every reminder channel must exist", channels.all { manager.getNotificationChannel(it) != null })
        // Revoking the real permission kills this app's instrumentation UID.
        // Delegate all services while exposing the framework's denied result.
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        assertFalse("A denied runtime permission must block the production gate", AndroidReminders.permissionAvailable(denied))
        AndroidReminders.deliverUsing(denied, plan.token, repository, store, plan.instant.plusSeconds(30), zone)
        assertTrue("Denied permission must prevent posting", manager.activeNotifications.none { it.tag == plan.token })
        assertFalse("Blocked delivery must not consume the version token", plan.token in store.read().delivered)
        assertTrue("Blocked permission must remove the scheduled queue", store.read().scheduled.isEmpty())
    }
    @Test fun staleCompletedSourceCannotPostAfterItsAlarmWasArmed() = runBlocking {
        val (task, plan) = armed()
        repository.completeTask(task.id)
        AndroidReminders.deliverUsing(context, plan.token, repository, store, plan.instant.plusSeconds(30), zone)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == plan.token })
        assertFalse(plan.token in store.read().delivered)
        assertTrue(store.read().scheduled.isEmpty())
    }
    @Test fun validDeliveryIsPrivateAndDuplicateBroadcastCannotReplay() = runBlocking {
        val (_, plan) = armed()
        AndroidReminders.deliverUsing(context, plan.token, repository, store, plan.instant.plusSeconds(30), zone)
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = posted(plan.token)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
        assertEquals("Habits", posted.notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(posted.notification.contentIntent)
        assertTrue(posted.notification.actions.any { it.title.toString().contains("Snooze") })
        assertTrue(plan.token in store.read().delivered)
        val firstPostTime = posted.postTime
        AndroidReminders.deliverUsing(context, plan.token, repository, store, plan.instant.plusSeconds(60), zone)
        assertEquals(firstPostTime, manager.activeNotifications.single { it.tag == plan.token }.postTime)
        assertFalse(repository.state.value.snapshot.tasks.single().completed)
    }
    @Test fun optOutAfterArmingCancelsPendingWorkWithoutChangingWorkspace() = runBlocking {
        val (_, plan) = armed()
        store.save(store.read().copy(preferences = ReminderPreferences()))
        AndroidReminders.deliverUsing(context, plan.token, repository, store, plan.instant.plusSeconds(30), zone)
        assertTrue(store.read().scheduled.isEmpty())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == plan.token })
        assertEquals(1, repository.state.value.snapshot.tasks.size)
        assertFalse(repository.state.value.snapshot.tasks.single().completed)
    }
    @Test fun alarmIdentityIsUniqueImmutableAndCancellationRemovesIt() = runBlocking {
        val (_, plan) = armed()
        val first = AndroidReminders.pending(context, plan.token, AndroidReminders.ACTION_DELIVER)
        assertEquals(first, AndroidReminders.pending(context, plan.token, AndroidReminders.ACTION_DELIVER))
        val otherToken = "a".repeat(64)
        tokens += otherToken
        assertNotEquals(first, AndroidReminders.pending(context, otherToken, AndroidReminders.ACTION_DELIVER))
        if (Build.VERSION.SDK_INT >= 31) assertTrue(first.isImmutable)
        AndroidReminders.cancelAlarm(context, plan.token)
        val lookup = Intent(context, ReminderAlarmReceiver::class.java).setAction(AndroidReminders.ACTION_DELIVER)
            .setData(Uri.parse("habits-reminder://alarm/${AndroidReminders.ACTION_DELIVER}/${plan.token}"))
        assertNull(PendingIntent.getBroadcast(context, 0, lookup, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }
    @Test fun aPostedNotificationSurvivesFailedJournalCommitWithoutAlertingAgain() = runBlocking {
        val (_, plan) = armed()
        val failing = object : ReminderStateStore(File(directory, "reminders.json")) {
            var fail = true
            override fun save(state: ReminderDeviceState) {
                if (fail) throw java.io.IOException("Synthetic reminder persistence failure")
                super.save(state)
            }
        }
        try {
            AndroidReminders.deliverUsing(context, plan.token, repository, failing, plan.instant.plusSeconds(30), zone)
            fail("The injected journal commit must fail")
        } catch (_: java.io.IOException) { }
        val first = posted(plan.token)
        assertFalse("An interrupted commit remains retryable", plan.token in store.read().delivered)
        failing.fail = false
        AndroidReminders.deliverUsing(context, plan.token, repository, failing, plan.instant.plusSeconds(60), zone)
        assertEquals(first.postTime, posted(plan.token).postTime)
        assertTrue(plan.token in store.read().delivered)
    }
    @Test fun generatedNotificationCopyFollowsLanguageWhileSourceTitleStaysLiteral() = runBlocking {
        val (task, plan) = armed()
        repository.savePreferences(Preferences(language = "de"))
        AndroidReminders.deliverUsing(context, plan.token, repository, store, plan.instant.plusSeconds(30), zone)
        val item = posted(plan.token).notification
        assertEquals(task.title, item.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(item.extras.getString(Notification.EXTRA_TEXT)!!.contains("Planer"))
        assertEquals("15 Min. später", item.actions.single().title.toString())
        assertEquals("Aufgabenfristen", context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AndroidReminders.channel(ReminderKind.TASK)).name.toString())
    }
    @Test fun delayedDeliverySamplesClockAfterTheSerializationWait() = runBlocking {
        val (_, plan) = armed()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : ReminderStateStore(File(directory, "reminders.json")) {
            override fun read(): ReminderDeviceState {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return super.read()
            }
        }
        val holder = async(Dispatchers.IO) { AndroidReminders.reconcileUsing(context, repository, blocking, plan.instant.plusSeconds(30), zone) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val clock = java.util.concurrent.atomic.AtomicReference(plan.instant.plusSeconds(30))
        val started = java.util.concurrent.CountDownLatch(1)
        val delivery = async(Dispatchers.IO) {
            started.countDown()
            AndroidReminders.deliverUsingClock(context, plan.token, repository, store, { clock.get() }, { zone })
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        clock.set(plan.expiry.plusSeconds(1))
        release.countDown()
        holder.await(); delivery.await()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == plan.token })
        assertFalse(plan.token in store.read().delivered)
    }
}
