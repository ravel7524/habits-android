package ch.habits.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.habits.android.HabitsAndroidRuntime
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(AndroidReminders.EXTRA_TOKEN) ?: return
        if (!Regex("[0-9a-f]{64}").matches(token) || intent.action !in setOf(AndroidReminders.ACTION_DELIVER, AndroidReminders.ACTION_SNOOZE, AndroidReminders.ACTION_DISMISS)) return
        val pending = goAsync()
        HabitsAndroidRuntime.scope.launch {
            try {
                when (intent.action) {
                    AndroidReminders.ACTION_DELIVER -> AndroidReminders.deliver(context, token)
                    AndroidReminders.ACTION_SNOOZE -> AndroidReminders.snooze(context, token)
                    AndroidReminders.ACTION_DISMISS -> AndroidReminders.dismiss(context, token)
                }
            } catch (_: Exception) {
                // Source state and private settings remain preserved; activation can reconcile.
            } finally { pending.finish() }
        }
    }
}
