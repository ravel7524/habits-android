package ch.habits.android

import android.content.Context
import android.util.Log
import ch.habits.android.data.WorkspaceRepository
import ch.habits.android.reminders.AndroidReminders
import ch.habits.android.widgets.HabitsWidgets
import ch.habits.android.localai.LocalAIController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException

/** One process-owned writer shared by activities, reminders and explicit widget actions. */
object HabitsAndroidRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var workspace: WorkspaceRepository? = null
    @Volatile private var ai: LocalAIController? = null

    fun localAI(context: Context): LocalAIController = ai ?: synchronized(this) {
        ai ?: LocalAIController(context.applicationContext).also { ai = it }
    }

    fun trimLocalAI() { ai?.cancel() }

    fun repository(context: Context): WorkspaceRepository = workspace ?: synchronized(this) {
        workspace ?: WorkspaceRepository(File(context.applicationContext.filesDir, "workspace.json"))
            .also { workspace = it }
    }

    suspend fun refreshDerived(context: Context) {
        try { AndroidReminders.reconcile(context) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("HabitsRuntime", "Reminder refresh unavailable: ${failure.javaClass.simpleName}")
        }
        try { HabitsWidgets.refresh(context) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("HabitsRuntime", "Widget refresh unavailable: ${failure.javaClass.simpleName}")
        }
    }
}
