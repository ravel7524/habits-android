package ch.habits.android.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import ch.habits.android.HabitsAndroidRuntime
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Explicit immutable widget capabilities; the manifest keeps this receiver private. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decoded = WidgetPendingIntents.decode(intent) ?: return
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(decoded.first)?.provider != ComponentName(context, HabitsTodayWidgetProvider::class.java)) return
        val result = goAsync()
        HabitsAndroidRuntime.scope.launch {
            try {
                withTimeout(9_000) {
                    try {
                        actionMutex.withLock {
                            val repository = HabitsAndroidRuntime.repository(context)
                            repository.refresh()
                            val action = decoded.second
                            if (WidgetProjection.canApplyHabitAction(repository.state.value, action, LocalDate.now())) {
                                repository.logHabit(action.habitID, action.date, action.targetCompleted,
                                    expectedRevision = action.revision,
                                    expectedCompleted = action.expectedCompleted,
                                    scheduledTodayOnly = true)
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Log.w("HabitsWidgets", "Habit write rejected: ${failure.javaClass.simpleName}")
                    }
                    // Also refresh when the atomic guard rejects a concurrent write.
                    HabitsWidgets.refresh(context)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("HabitsWidgets", "Habit action unavailable: ${failure.javaClass.simpleName}")
            } finally { result.finish() }
        }
    }
    companion object { private val actionMutex = Mutex() }
}
