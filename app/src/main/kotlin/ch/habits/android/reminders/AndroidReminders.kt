package ch.habits.android.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import ch.habits.android.HabitsAndroidRuntime
import ch.habits.android.MainActivity
import ch.habits.android.R
import ch.habits.android.data.WorkspaceRepository
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AndroidReminders {
    const val ACTION_DELIVER = "ch.habits.android.reminders.DELIVER"
    const val ACTION_SNOOZE = "ch.habits.android.reminders.SNOOZE"
    const val ACTION_DISMISS = "ch.habits.android.reminders.DISMISS"
    const val EXTRA_TOKEN = "habits.reminder.token"
    const val EXTRA_DESTINATION = "habits.destination"
    const val EXTRA_SOURCE_ID = "habits.source.id"
    const val EXTRA_REPORT_KIND = "habits.report.kind"
    private val lock = Mutex()
    fun createChannels(context: Context, language: String = "system") {
        val copy = ReminderCopy(language)
        val channels = listOf("habits.work.v1" to copy.t("Work deadlines", "Aufgabenfristen", "Plazos de trabajo"),
            "habits.assessments.v1" to copy.t("Assessment dates", "Prüfungs- und Abgabetermine", "Fechas de evaluaciones"),
            "habits.appointments.v1" to copy.t("Appointments", "Termine", "Citas"),
            "habits.habits.v1" to copy.t("Habit invitations", "Gewohnheitseinladungen", "Invitaciones de hábitos"),
            "habits.reports.v1" to copy.t("Report invitations", "Berichtseinladungen", "Invitaciones de informes"))
        val manager = context.getSystemService(NotificationManager::class.java)
        for ((id, name) in channels) manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = copy.t("Optional local Habits reminders. Android may delay delivery.", "Optionale lokale Habits-Erinnerungen. Android kann die Zustellung verzögern.", "Recordatorios locales opcionales de Habits. Android puede retrasar los avisos.")
        })
    }
    fun permissionAvailable(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    internal fun store(context: Context) = ReminderStateStore(File(context.noBackupFilesDir, "reminders/device.json"))
    internal suspend fun readState(context: Context): ReminderDeviceState = withContext(Dispatchers.IO) { lock.withLock { store(context).read() } }
    suspend fun configure(context: Context, preferences: ReminderPreferences) = withContext(Dispatchers.IO) {
        preferences.validate()
        lock.withLock { val store = store(context); store.save(store.read().copy(preferences = preferences)) }
        reconcile(context)
    }
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { reconcileLocked(context, HabitsAndroidRuntime.repository(context), store(context), Instant.now(), ZoneId.systemDefault()) }
    }
    internal suspend fun reconcileUsing(context: Context, repository: WorkspaceRepository, store: ReminderStateStore, now: Instant, zone: ZoneId) = withContext(Dispatchers.IO) {
        lock.withLock { reconcileLocked(context, repository, store, now, zone) }
    }

    private suspend fun reconcileLocked(context: Context, repository: WorkspaceRepository, store: ReminderStateStore, now: Instant, zone: ZoneId) {
        val state = try { store.read() } catch (_: Exception) { cancelKnownNotifications(context); return }
        val workspace = repository.refresh()
        createChannels(context, workspace.snapshot.preferences.language)
        val permitted = permissionAvailable(context) && !workspace.recoveryRequired
        val future = if (permitted) ReminderPlanBuilder.build(workspace.snapshot, state.preferences, now, zone,
            state.delivered.keys, state.snoozes.mapValues { Instant.parse(it.value) }).filter { channelEnabled(context, it.kind) } else emptyList()
        // Existing armed alarms retain their delivery grace while Android delays
        // them; newly discovered overdue items do not become retroactive alerts.
        val due = if (permitted) state.scheduled.mapNotNull { old ->
            if (old.instant > now || now >= old.expiry || (old.token in state.delivered && old.token !in state.snoozes)) null
            else ReminderPlanBuilder.current(workspace.snapshot, state.preferences, old.token, now, zone)?.takeIf {
                channelEnabled(context, it.kind) && (old.token in state.snoozes || it.fireAt == old.fireAt)
            }?.let { old }
        } else emptyList()
        val queue = (due + future).distinctBy { it.token }.sortedBy { it.instant }.take(ReminderPlanBuilder.MAX_QUEUE)
        val posted = state.posted.filter { plan -> permitted && channelEnabled(context, plan.kind) &&
            ReminderPlanBuilder.current(workspace.snapshot, state.preferences, plan.token, now, zone) != null }
        for (old in state.scheduled) if (queue.none { it.token == old.token && it.fireAt == old.fireAt }) cancelAlarm(context, old.token)
        for (old in state.posted) if (posted.none { it.token == old.token }) context.getSystemService(NotificationManager::class.java).cancel(old.token, 1)
        val snoozes = state.snoozes.filterKeys { token -> queue.any { it.token == token } }.mapValues { (token, _) -> queue.first { it.token == token }.fireAt }
        store.save(state.copy(scheduled = queue, posted = posted, snoozes = snoozes))
        // Re-set the bounded queue after boot/time changes; PendingIntent identity replaces duplicates.
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (plan in queue) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.instant.toEpochMilli(), pending(context, plan.token, ACTION_DELIVER))
    }

    suspend fun deliver(context: Context, token: String) = deliverUsingClock(context, token, HabitsAndroidRuntime.repository(context), store(context), { Instant.now() }, { ZoneId.systemDefault() })
    internal suspend fun deliverUsing(context: Context, token: String, repository: WorkspaceRepository, store: ReminderStateStore, now: Instant, zone: ZoneId) =
        deliverUsingClock(context, token, repository, store, { now }, { zone })
    internal suspend fun deliverUsingClock(context: Context, token: String, repository: WorkspaceRepository, store: ReminderStateStore,
                                           clock: () -> Instant, zoneProvider: () -> ZoneId) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!Regex("[0-9a-f]{64}").matches(token)) return@withLock
            val state = try { store.read() } catch (_: Exception) { return@withLock }
            val scheduled = state.scheduled.firstOrNull { it.token == token } ?: return@withLock
            val workspace = repository.refresh()
            // Sample after mutex/IO waits, not when the receiver was dispatched.
            val now = clock(); val zone = zoneProvider()
            val current = if (!workspace.recoveryRequired) ReminderPlanBuilder.current(workspace.snapshot, state.preferences, token, now, zone) else null
            val snoozed = state.snoozes[token]
            val eligible = current != null && (snoozed != null || current.fireAt == scheduled.fireAt) && now >= scheduled.instant && now < scheduled.expiry &&
                (token !in state.delivered || snoozed == scheduled.fireAt) && permissionAvailable(context) && channelEnabled(context, current.kind)
            if (!eligible) { cancelAlarm(context, token); reconcileLocked(context, repository, store, now, zone); return@withLock }
            val validated = current!!.copy(fireAt = scheduled.fireAt)
            val delivered = (state.delivered + (token to now.toString())).entries.sortedByDescending { Instant.parse(it.value) }.take(4096).associate { it.toPair() }
            val keptPosts = (state.posted.filterNot { it.token == token } + validated).takeLast(32)
            for (old in state.posted) if (keptPosts.none { it.token == old.token }) context.getSystemService(NotificationManager::class.java).cancel(old.token, 1)
            val next = state.copy(scheduled = state.scheduled.filterNot { it.token == token }, delivered = delivered,
                snoozes = state.snoozes - token, posted = keptPosts)
            val manager = context.getSystemService(NotificationManager::class.java)
            // Serialize posting and persistence. If persistence was interrupted,
            // recover the existing same-tag notification without alerting again.
            if (snoozed != null || manager.activeNotifications.none { it.tag == token && it.id == 1 }) {
                val postingNow = clock()
                if (postingNow >= validated.expiry || postingNow < scheduled.instant || zoneProvider() != zone) return@withLock
                manager.notify(token, 1, notification(context, validated, postingNow, workspace.snapshot.preferences.language))
            }
            // Notify failures never create a false delivered/posted journal entry.
            store.save(next)
            reconcileLocked(context, repository, store, now, zone)
        }
    }

    suspend fun snooze(context: Context, token: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val store = store(context); val state = try { store.read() } catch (_: Exception) { return@withLock }
            val repository = HabitsAndroidRuntime.repository(context)
            val workspace = repository.refresh()
            val now = Instant.now(); val zone = ZoneId.systemDefault()
            val current = if (!workspace.recoveryRequired) ReminderPlanBuilder.current(workspace.snapshot, state.preferences, token, now, zone) else null
            val fire = ReminderPlanBuilder.outsideQuietHours(now.plusSeconds(15 * 60), zone)
            val wasShown = token in state.delivered || context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == token && it.id == 1 }
            if (current == null || !wasShown || fire == null || fire >= current.expiry) return@withLock
            val delivered = deliveryHistory(state.delivered, token, now)
            store.save(state.copy(delivered = delivered, snoozes = state.snoozes + (token to fire.toString()), posted = state.posted.filterNot { it.token == token }))
            context.getSystemService(NotificationManager::class.java).cancel(token, 1)
            reconcileLocked(context, repository, store, now, zone)
        }
    }
    suspend fun dismiss(context: Context, token: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val store = store(context); val state = try { store.read() } catch (_: Exception) { return@withLock }
            // A real dismissal also recovers a post whose journal commit failed.
            val known = token in state.delivered || state.scheduled.any { it.token == token } || state.posted.any { it.token == token }
            if (!known) return@withLock
            store.save(state.copy(delivered = deliveryHistory(state.delivered, token, Instant.now()),
                scheduled = state.scheduled.filterNot { it.token == token }, snoozes = state.snoozes - token,
                posted = state.posted.filterNot { it.token == token }))
            cancelAlarm(context, token)
            context.getSystemService(NotificationManager::class.java).cancel(token, 1)
        }
    }
    fun testNotification(context: Context) {
        val copy = ReminderCopy(HabitsAndroidRuntime.repository(context).state.value.snapshot.preferences.language)
        createChannels(context, HabitsAndroidRuntime.repository(context).state.value.snapshot.preferences.language)
        require(permissionAvailable(context) && channelEnabled(context, ReminderKind.MORNING_REPORT)) { "Allow notifications and the report invitation channel first." }
        val public = Notification.Builder(context, channel(ReminderKind.MORNING_REPORT)).setSmallIcon(R.drawable.ic_habits_reminder)
            .setContentTitle(copy.t("Habits reminder test", "Habits-Erinnerungstest", "Prueba de recordatorio de Habits"))
            .setContentText(copy.t("Notifications are available. Scheduled delivery may still be delayed by Android.", "Mitteilungen sind verfügbar. Android kann geplante Zustellungen weiterhin verzögern.", "Las notificaciones están disponibles. Android aún puede retrasar los avisos programados."))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(60_000).build()
        context.getSystemService(NotificationManager::class.java).notify("habits.reminder.test", 1, public)
    }

    private fun notification(context: Context, plan: ReminderPlan, now: Instant, language: String): Notification {
        val copy = ReminderCopy(language)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("habits-reminder://open/${plan.token}")
            putExtra(EXTRA_DESTINATION, plan.route); putExtra(EXTRA_SOURCE_ID, plan.sourceID)
            putExtra(EXTRA_REPORT_KIND, plan.reportKind)
        }
        val content = copy.body(plan)
        val redacted = Notification.Builder(context, channel(plan.kind)).setSmallIcon(R.drawable.ic_habits_reminder)
            .setContentTitle("Habits").setContentText(copy.t("Open Habits to view your reminder.", "Öffne Habits, um deine Erinnerung zu sehen.", "Abre Habits para ver tu recordatorio.")).build()
        return Notification.Builder(context, channel(plan.kind)).setSmallIcon(R.drawable.ic_habits_reminder)
            .setContentTitle(copy.title(plan)).setContentText(content).setStyle(Notification.BigTextStyle().bigText(content))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(redacted).setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(pending(context, plan.token, ACTION_DISMISS))
            .addAction(Notification.Action.Builder(null, copy.t("Snooze 15 min", "15 Min. später", "Posponer 15 min"), pending(context, plan.token, ACTION_SNOOZE)).build())
            .setOnlyAlertOnce(true)
            .setAutoCancel(true).setTimeoutAfter(maxOf(1, Duration.between(now, plan.expiry).toMillis())).build()
    }
    internal fun pending(context: Context, token: String, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(action).setData(Uri.parse("habits-reminder://alarm/$action/$token"))
            .putExtra(EXTRA_TOKEN, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    internal fun cancelAlarm(context: Context, token: String) {
        val intent = pending(context, token, ACTION_DELIVER)
        context.getSystemService(AlarmManager::class.java).cancel(intent); intent.cancel()
    }
    internal fun channel(kind: ReminderKind): String = when (kind) {
        ReminderKind.TASK -> "habits.work.v1"; ReminderKind.ASSESSMENT -> "habits.assessments.v1"
        ReminderKind.APPOINTMENT -> "habits.appointments.v1"; ReminderKind.HABIT -> "habits.habits.v1"
        else -> "habits.reports.v1"
    }
    private fun channelEnabled(context: Context, kind: ReminderKind): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel(kind))?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
    private fun cancelKnownNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.matches(Regex("[0-9a-f]{64}")) == true }.forEach { manager.cancel(it.tag, it.id) }
    }
    private fun deliveryHistory(previous: Map<String, String>, token: String, now: Instant): Map<String, String> =
        (previous + (token to (previous[token] ?: now.toString()))).entries.sortedByDescending { Instant.parse(it.value) }
            .take(4096).associate { it.toPair() }
}
