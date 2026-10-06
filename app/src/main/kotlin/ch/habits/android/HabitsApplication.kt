package ch.habits.android

import android.app.Application
import ch.habits.android.reminders.AndroidReminders
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HabitsApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) HabitsAndroidRuntime.trimLocalAI()
    }

    override fun onCreate() {
        super.onCreate()
        AndroidReminders.createChannels(this)
        HabitsAndroidRuntime.scope.launch {
            HabitsAndroidRuntime.repository(this@HabitsApplication).state.collectLatest {
                HabitsAndroidRuntime.refreshDerived(this@HabitsApplication)
            }
        }
    }
}
