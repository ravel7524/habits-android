package ch.habits.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import ch.habits.android.HabitsAndroidRuntime
import ch.habits.android.MainActivity
import ch.habits.android.R
import ch.habits.android.data.WorkspaceState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Framework widgets compose the same local source as the app. No network reads. */
object HabitsWidgets {
    const val NEXT_UP = "next-up"
    const val HABITS_TODAY = "habits-today"
    const val EXTRA_ROUTE = "habits.widget.route"
    private val refreshMutex = Mutex()

    suspend fun refresh(context: Context) = refreshMutex.withLock {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val nextIDs = manager.getAppWidgetIds(ComponentName(app, NextUpWidgetProvider::class.java))
        val habitIDs = manager.getAppWidgetIds(ComponentName(app, HabitsTodayWidgetProvider::class.java))
        if (nextIDs.isEmpty() && habitIDs.isEmpty()) return@withLock
        val repository = HabitsAndroidRuntime.repository(app)
        repository.refresh()
        val state = repository.state.value
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        for (id in nextIDs) manager.updateAppWidget(id, HabitsWidgetViews.nextUp(app, state, id, manager.getAppWidgetOptions(id), now, zone))
        for (id in habitIDs) manager.updateAppWidget(id, HabitsWidgetViews.habits(app, state, id, manager.getAppWidgetOptions(id), now, zone))
    }

    /** False means the launcher does not support pinning, or the kind is unknown. */
    fun requestPin(context: Context, kind: String): Boolean {
        val provider = when (kind) {
            NEXT_UP -> NextUpWidgetProvider::class.java
            HABITS_TODAY -> HabitsTodayWidgetProvider::class.java
            else -> return false
        }
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, provider), null, null)
    }
}

abstract class BaseHabitsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refreshAsync(context) }
    override fun onEnabled(context: Context) { refreshAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refreshAsync(context) }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPendingIntents.clear(context, it) } }
    private fun refreshAsync(context: Context) {
        val result = goAsync()
        HabitsAndroidRuntime.scope.launch {
            try { withTimeout(9_000) { HabitsWidgets.refresh(context) } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("HabitsWidgets", "Refresh unavailable: ${failure.javaClass.simpleName}")
            } finally { result.finish() }
        }
    }
}

class NextUpWidgetProvider : BaseHabitsWidgetProvider()
class HabitsTodayWidgetProvider : BaseHabitsWidgetProvider()

internal object HabitsWidgetViews {
    private val rows = intArrayOf(R.id.habits_widget_row_1, R.id.habits_widget_row_2, R.id.habits_widget_row_3)
    private val titles = intArrayOf(R.id.habits_widget_item_title_1, R.id.habits_widget_item_title_2, R.id.habits_widget_item_title_3)
    private val details = intArrayOf(R.id.habits_widget_item_detail_1, R.id.habits_widget_item_detail_2, R.id.habits_widget_item_detail_3)
    private val buttons = intArrayOf(R.id.habits_widget_action_1, R.id.habits_widget_action_2, R.id.habits_widget_action_3)

    fun nextUp(context: Context, state: WorkspaceState, widgetID: Int, options: Bundle, now: Instant, zone: ZoneId): RemoteViews {
        val copy = WidgetCopy(state.snapshot.preferences.language)
        val limit = rowLimit(context, options)
        val projection = runCatching { WidgetProjection.nextUp(state, now, zone, limit) }
            .getOrElse { NextUpProjection(emptyList(), 0, now, true) }
        val views = base(context, state, widgetID, HabitsWidgets.NEXT_UP, "planner", copy.nextUp, now, zone, limit)
        if (projection.recoveryRequired) recovery(views, copy, limit)
        else if (projection.totalCount == 0) empty(views, copy.emptyNext, limit)
        else {
            views.setTextViewText(R.id.habits_widget_summary, copy.nextSummary(projection.entries.size, projection.totalCount))
            views.setViewVisibility(R.id.habits_widget_empty, View.GONE)
            projection.entries.forEachIndexed { index, item ->
                views.setViewVisibility(rows[index], View.VISIBLE)
                views.setTextViewText(titles[index], item.title)
                views.setTextViewText(details[index], copy.whenText(item))
                views.setContentDescription(rows[index], "${item.title}. ${copy.whenText(item)}")
            }
        }
        WidgetPendingIntents.replace(context, widgetID, emptySet())
        return views
    }

    fun habits(context: Context, state: WorkspaceState, widgetID: Int, options: Bundle, now: Instant, zone: ZoneId): RemoteViews {
        val copy = WidgetCopy(state.snapshot.preferences.language)
        val limit = rowLimit(context, options)
        val projection = runCatching { WidgetProjection.habitsToday(state, now, zone, limit) }
            .getOrElse { TodayHabitsProjection(emptyList(), 0, 0, now.atZone(zone).toLocalDate().toString(), state.snapshot.revision, now, true) }
        val views = base(context, state, widgetID, HabitsWidgets.HABITS_TODAY, "habits", copy.todayHabits, now, zone, limit)
        val actionUris = mutableSetOf<String>()
        if (projection.recoveryRequired) recovery(views, copy, limit)
        else if (projection.scheduledCount == 0) empty(views, copy.emptyHabits, limit)
        else {
            views.setTextViewText(R.id.habits_widget_summary, "${projection.date} · ${copy.habitSummary(projection.completedCount, projection.scheduledCount)}")
            views.setViewVisibility(R.id.habits_widget_empty, View.GONE)
            projection.entries.forEachIndexed { index, item ->
                views.setViewVisibility(rows[index], View.VISIBLE)
                views.setTextViewText(titles[index], item.title)
                views.setTextViewText(details[index], if (item.completed) copy.logged else copy.unrecorded)
                views.setViewVisibility(buttons[index], View.VISIBLE)
                views.setTextViewText(buttons[index], if (item.completed) copy.undoLog else copy.log)
                views.setContentDescription(buttons[index], (if (item.completed) copy.undoLog else copy.log) + ": " + item.title)
                val action = WidgetHabitAction(item.id, projection.date, projection.revision, item.completed, !item.completed)
                val intent = WidgetPendingIntents.habitIntent(context, widgetID, action)
                actionUris += checkNotNull(intent.data).toString()
                views.setOnClickPendingIntent(buttons[index], PendingIntent.getBroadcast(context, widgetID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        WidgetPendingIntents.replace(context, widgetID, actionUris)
        return views
    }

    private fun base(context: Context, state: WorkspaceState, widgetID: Int, kind: String, route: String,
        title: String, now: Instant, zone: ZoneId, limit: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.habits_widget_panel)
        val dark = when (state.snapshot.preferences.theme) {
            "dark" -> true; "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val foreground = Color.parseColor(if (dark) "#F4F0E6" else "#22231F")
        val secondary = Color.parseColor(if (dark) "#BBB9B1" else "#66685F")
        val accent = Color.parseColor(if (dark) "#F2D65B" else "#294CC7")
        views.setInt(R.id.habits_widget_root, "setBackgroundResource", if (dark) R.drawable.habits_widget_background_dark else R.drawable.habits_widget_background_light)
        views.setTextViewText(R.id.habits_widget_title, title)
        views.setTextColor(R.id.habits_widget_title, foreground)
        views.setTextColor(R.id.habits_widget_empty, foreground)
        views.setTextColor(R.id.habits_widget_summary, secondary)
        views.setTextColor(R.id.habits_widget_updated, secondary)
        val copy = WidgetCopy(state.snapshot.preferences.language)
        views.setTextViewText(R.id.habits_widget_updated, "${copy.openApp} · ${copy.updated(now, zone)}")
        if (limit == 0) views.setInt(R.id.habits_widget_summary, "setMaxLines", 1)
        for (index in rows.indices) {
            views.setViewVisibility(rows[index], View.GONE)
            views.setViewVisibility(buttons[index], View.GONE)
            views.setTextColor(titles[index], foreground)
            views.setTextColor(details[index], secondary)
            views.setTextColor(buttons[index], accent)
            views.setInt(buttons[index], "setBackgroundResource", if (dark) R.drawable.habits_widget_action_dark else R.drawable.habits_widget_action_light)
        }
        val launch = WidgetPendingIntents.openIntent(context, widgetID, kind, if (state.recoveryRequired) "today" else route)
        val pending = PendingIntent.getActivity(context, widgetID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.habits_widget_root, pending)
        views.setOnClickPendingIntent(R.id.habits_widget_title, pending)
        views.setOnClickPendingIntent(R.id.habits_widget_updated, pending)
        return views
    }

    private fun recovery(views: RemoteViews, copy: WidgetCopy, limit: Int) {
        views.setTextViewText(R.id.habits_widget_summary, copy.t("Data preserved · Open app", "Daten erhalten · App öffnen", "Datos conservados · Abrir app"))
        views.setTextViewText(R.id.habits_widget_empty, copy.recovery)
        views.setViewVisibility(R.id.habits_widget_empty, if (limit == 0) View.GONE else View.VISIBLE)
    }
    private fun empty(views: RemoteViews, message: String, limit: Int) {
        views.setTextViewText(R.id.habits_widget_summary, if (limit == 0) message else "")
        views.setTextViewText(R.id.habits_widget_empty, message)
        views.setViewVisibility(R.id.habits_widget_empty, if (limit == 0) View.GONE else View.VISIBLE)
    }
    private fun rowLimit(context: Context, options: Bundle): Int {
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 320)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        return (((height - 28) / fontScale - 90) / 80).toInt().coerceIn(0, 3)
    }
}

/** Keep only current action identities so resized/deleted widgets cancel old capabilities. */
internal object WidgetPendingIntents {
    const val ACTION_LOG_HABIT = "ch.habits.android.widgets.LOG_HABIT"
    private const val STORE = "habits_widget_actions"
    fun openIntent(context: Context, widgetID: Int, kind: String, route: String): Intent =
        Intent(context, MainActivity::class.java).setData(Uri.Builder().scheme("habits-widget").authority("open").appendPath(kind).appendPath(widgetID.toString()).build())
            .putExtra(HabitsWidgets.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun habitIntent(context: Context, widgetID: Int, action: WidgetHabitAction): Intent = Intent(context, WidgetActionReceiver::class.java)
        .setAction(ACTION_LOG_HABIT).setData(Uri.Builder().scheme("habits-widget").authority("habit-action")
            .appendPath(widgetID.toString()).appendPath(action.habitID).appendPath(action.date).appendPath(action.revision.toString())
            .appendQueryParameter("expected", action.expectedCompleted.toString()).appendQueryParameter("target", action.targetCompleted.toString()).build())

    fun decode(intent: Intent): Pair<Int, WidgetHabitAction>? {
        if (intent.action != ACTION_LOG_HABIT) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "habits-widget" || uri.authority != "habit-action" || uri.pathSegments.size != 4) return null
        val parts = uri.pathSegments
        val widgetID = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val revision = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val expected = uri.getQueryParameter("expected")?.toBooleanStrictOrNull() ?: return null
        val target = uri.getQueryParameter("target")?.toBooleanStrictOrNull() ?: return null
        return widgetID to WidgetHabitAction(parts[1], parts[2], revision, expected, target)
    }

    fun replace(context: Context, widgetID: Int, current: Set<String>) {
        guardMetadata {
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val key = widgetID.toString()
            val previous = readUris(store, key)
            (previous - current).forEach { cancel(context, widgetID, it) }
            // Only this derived widget entry is rebuilt; workspace data is separate.
            store.edit().putStringSet(key, current).apply()
        }
    }
    fun clear(context: Context, widgetID: Int) {
        guardMetadata {
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            readUris(store, widgetID.toString()).forEach { cancel(context, widgetID, it) }
            store.edit().remove(widgetID.toString()).apply()
        }
    }
    private fun readUris(store: SharedPreferences, key: String): Set<String> = try {
        val values: Set<*> = store.getStringSet(key, emptySet()).orEmpty()
        if (values.any { it !is String }) throw ClassCastException()
        values.filterIsInstance<String>().toSet()
    } catch (failure: RuntimeException) {
        logMetadataFailure(failure)
        emptySet()
    }
    private fun cancel(context: Context, widgetID: Int, uri: String) {
        guardMetadata {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_LOG_HABIT).setData(Uri.parse(uri))
            PendingIntent.getBroadcast(context, widgetID, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
        }
    }
    private inline fun guardMetadata(block: () -> Unit) {
        try { block() } catch (failure: RuntimeException) { logMetadataFailure(failure) }
    }
    private fun logMetadataFailure(failure: RuntimeException) {
        Log.w("HabitsWidgets", "Derived widget metadata unavailable: ${failure.javaClass.simpleName}")
    }
}
