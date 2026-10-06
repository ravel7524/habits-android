package ch.habits.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.habits.android.HabitsAndroidRuntime
import ch.habits.android.widgets.HabitsWidgets
import kotlinx.coroutines.launch

class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCALE_CHANGED)) return
        val pending = goAsync()
        HabitsAndroidRuntime.scope.launch {
            try { AndroidReminders.reconcile(context) }
            catch (_: Exception) { /* Preserve source data and retry on activation. */ }
            finally {
                try { HabitsWidgets.refresh(context) }
                catch (_: Exception) { /* Widgets also refresh on activation/data changes. */ }
                finally { pending.finish() }
            }
        }
    }
}
