package ch.habits.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import ch.habits.android.R
import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicSemester
import ch.habits.android.data.Habit
import ch.habits.android.data.HabitLog
import ch.habits.android.data.Preferences
import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.data.WorkspaceState
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Applies actual RemoteViews with synthetic values; never writes the planning store. */
class FrameworkWidgetViewsTest {
    private lateinit var context: Context
    private val nextID = 1_700_001
    private val habitsID = 1_700_002
    private val now = Instant.parse("2026-10-05T10:00:00Z")
    private val zone = ZoneId.of("Europe/Zurich")
    private val options get() = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 480) }

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)
        assertNull(manager.getAppWidgetInfo(nextID))
        assertNull(manager.getAppWidgetInfo(habitsID))
    }
    @After fun cleanup() {
        WidgetPendingIntents.clear(context, nextID)
        WidgetPendingIntents.clear(context, habitsID)
    }

    @Test fun nextUpAppliesFrameworkViewsWithTruthfulCivilMetadataAndNoActionButtons() {
        val state = WorkspaceState(WorkspaceSnapshot(tasks = listOf(
            WorkTask(title = "Read Statistics", dueDate = "2026-10-05", sourceZone = "Europe/Zurich")),
            preferences = Preferences(language = "en", theme = "light")))
        val view = inflate(HabitsWidgetViews.nextUp(context, state, nextID, options, now, zone))
        assertEquals("Next up", view.findViewById<TextView>(R.id.habits_widget_title).text.toString())
        assertEquals("Read Statistics", view.findViewById<TextView>(R.id.habits_widget_item_title_1).text.toString())
        val detail = view.findViewById<TextView>(R.id.habits_widget_item_detail_1).text.toString()
        assertTrue(detail.contains("date only"))
        assertTrue(detail.contains("Europe/Zurich"))
        assertFalse(detail.contains("00:00"))
        assertEquals(View.GONE, view.findViewById<Button>(R.id.habits_widget_action_1).visibility)
        assertTrue(view.findViewById<View>(R.id.habits_widget_root).hasOnClickListeners())
        assertTrue(view.findViewById<TextView>(R.id.habits_widget_updated).text.toString().contains("2026"))
    }

    @Test fun habitsApplyThreeNamedRowsExplicitStateAndPrivateImmutableCapabilities() {
        val habits = listOf(Habit(id = "h1", title = "Read"), Habit(id = "h2", title = "Stretch"), Habit(id = "h3", title = "Walk"))
        val state = WorkspaceState(WorkspaceSnapshot(revision = 7, habits = habits,
            habitLogs = listOf(HabitLog("h1", "2026-10-05")), preferences = Preferences(language = "en", theme = "dark")))
        val view = inflate(HabitsWidgetViews.habits(context, state, habitsID, options, now, zone))
        assertEquals("Today's habits", view.findViewById<TextView>(R.id.habits_widget_title).text.toString())
        assertTrue(view.findViewById<TextView>(R.id.habits_widget_summary).text.toString().contains("1/3"))
        assertEquals("Undo log", view.findViewById<Button>(R.id.habits_widget_action_1).text.toString())
        assertEquals("Log", view.findViewById<Button>(R.id.habits_widget_action_2).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.habits_widget_row_3).visibility)
        assertEquals(Color.parseColor("#F4F0E6"), view.findViewById<TextView>(R.id.habits_widget_title).currentTextColor)
        val intent = WidgetPendingIntents.habitIntent(context, habitsID, WidgetHabitAction("h1", "2026-10-05", 7, true, false))
        val pending = PendingIntent.getBroadcast(context, habitsID, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(pending)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(checkNotNull(pending).isImmutable)
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, WidgetActionReceiver::class.java), 0)
        assertFalse(receiver.exported)
    }

    @Test fun staleCapabilitiesAreCancelledWhenRevisionChangesOrWidgetIsDeleted() {
        val action = WidgetHabitAction("h1", "2026-10-05", 7, false, true)
        val oldIntent = WidgetPendingIntents.habitIntent(context, habitsID, action)
        PendingIntent.getBroadcast(context, habitsID, oldIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        WidgetPendingIntents.replace(context, habitsID, setOf(checkNotNull(oldIntent.data).toString()))
        val nextIntent = WidgetPendingIntents.habitIntent(context, habitsID, action.copy(revision = 8))
        PendingIntent.getBroadcast(context, habitsID, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        WidgetPendingIntents.replace(context, habitsID, setOf(checkNotNull(nextIntent.data).toString()))
        assertNull(PendingIntent.getBroadcast(context, habitsID, oldIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        WidgetPendingIntents.clear(context, habitsID)
        assertNull(PendingIntent.getBroadcast(context, habitsID, nextIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun wrongTypedMetadataCannotCrashClearOrProviderDeletion() {
        val store = context.getSharedPreferences("habits_widget_actions", Context.MODE_PRIVATE)
        val untouched = "unrelated-derived-entry"
        try {
            assertTrue(store.edit().putString(habitsID.toString(), "wrong type").putString(untouched, "keep").commit())
            WidgetPendingIntents.clear(context, habitsID)
            assertFalse(store.contains(habitsID.toString()))
            assertEquals("keep", store.getString(untouched, null))

            assertTrue(store.edit().putInt(nextID.toString(), 42).putBoolean(habitsID.toString(), true).commit())
            NextUpWidgetProvider().onDeleted(context, intArrayOf(nextID, habitsID))
            assertFalse(store.contains(nextID.toString()))
            assertFalse(store.contains(habitsID.toString()))
            assertEquals("keep", store.getString(untouched, null))
        } finally { store.edit().remove(untouched).commit() }
    }

    @Test fun wrongTypedMetadataIsRebuiltWithoutBlockingFreshHabitViews() {
        val store = context.getSharedPreferences("habits_widget_actions", Context.MODE_PRIVATE)
        assertTrue(store.edit().putLong(habitsID.toString(), 99).commit())
        val state = WorkspaceState(WorkspaceSnapshot(revision = 8, habits = listOf(Habit(id = "h1", title = "Read")),
            preferences = Preferences(language = "en", theme = "light")))
        val view = inflate(HabitsWidgetViews.habits(context, state, habitsID, options, now, zone))
        assertEquals("Read", view.findViewById<TextView>(R.id.habits_widget_item_title_1).text.toString())
        val currentUris = checkNotNull(store.getStringSet(habitsID.toString(), null))
        assertEquals(1, currentUris.size)
        val action = WidgetHabitAction("h1", "2026-10-05", 8, false, true)
        assertEquals(setOf(checkNotNull(WidgetPendingIntents.habitIntent(context, habitsID, action).data).toString()), currentUris)
    }

    @Test fun capabilitiesAreDistinctForWidgetHabitDayAndRevisionAndAppRoutesAreExplicit() {
        val action = WidgetHabitAction("h1", "2026-10-05", 7, false, true)
        val original = WidgetPendingIntents.habitIntent(context, habitsID, action)
        assertEquals(habitsID to action, WidgetPendingIntents.decode(original))
        assertFalse(original.filterEquals(WidgetPendingIntents.habitIntent(context, nextID, action)))
        assertFalse(original.filterEquals(WidgetPendingIntents.habitIntent(context, habitsID, action.copy(habitID = "h2"))))
        assertFalse(original.filterEquals(WidgetPendingIntents.habitIntent(context, habitsID, action.copy(date = "2026-10-06"))))
        assertFalse(original.filterEquals(WidgetPendingIntents.habitIntent(context, habitsID, action.copy(revision = 8))))
        assertNull(WidgetPendingIntents.decode(Intent(original).setAction("other")))
        val open = WidgetPendingIntents.openIntent(context, nextID, HabitsWidgets.NEXT_UP, "planner")
        assertEquals("planner", open.getStringExtra(HabitsWidgets.EXTRA_ROUTE))
        assertTrue(open.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertNotNull(open.component)
    }

    @Test fun recoveryEmptyAndCompactLayoutsDoNotExposeHiddenSourceRows() {
        val state = WorkspaceState(WorkspaceSnapshot(tasks = listOf(WorkTask(title = "Hidden private title", dueDate = "2026-10-06"))), recoveryRequired = true)
        val recovering = inflate(HabitsWidgetViews.nextUp(context, state, nextID, options, now, zone))
        assertEquals(View.GONE, recovering.findViewById<View>(R.id.habits_widget_row_1).visibility)
        assertTrue(recovering.findViewById<TextView>(R.id.habits_widget_empty).text.toString().contains("preserved"))
        val empty = inflate(HabitsWidgetViews.habits(context, WorkspaceState(), habitsID, options, now, zone))
        assertTrue(empty.findViewById<TextView>(R.id.habits_widget_empty).text.toString().contains("No habits"))
        val compact = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) }
        val small = inflate(HabitsWidgetViews.nextUp(context, state.copy(recoveryRequired = false), nextID, compact, now, zone))
        assertEquals(View.GONE, small.findViewById<View>(R.id.habits_widget_row_1).visibility)
        assertTrue(small.findViewById<TextView>(R.id.habits_widget_summary).text.toString().contains("Open Planner"))
    }

    @Test fun unresolvedAcademicClockShowsReviewCoverageAlongsideValidRowsInsteadOfRecoveryOrEmpty() {
        val semester = AcademicSemester("semester", "Autumn", null, 14, zone.id)
        val course = AcademicCourse("course", semester.id, "Statistics")
        val exam = AcademicAssessment("exam", semester.id, course.id, "Unresolved exam", "exam", "2026-10-25", "02:30")
        val state = WorkspaceState(WorkspaceSnapshot(semesters = listOf(semester), courses = listOf(course), assessments = listOf(exam),
            tasks = listOf(WorkTask(title = "Valid preparation", dueDate = "2026-10-06", sourceCourseID = course.id, sourceWeek = 3)),
            habits = listOf(Habit(id = "h1", title = "Read")), preferences = Preferences(language = "en")))
        val mixed = inflate(HabitsWidgetViews.nextUp(context, state, nextID, options, now, zone))
        assertEquals("Valid preparation", mixed.findViewById<TextView>(R.id.habits_widget_item_title_1).text.toString())
        assertTrue(mixed.findViewById<TextView>(R.id.habits_widget_item_detail_1).text.toString().contains("Preparation"))
        assertTrue(mixed.findViewById<TextView>(R.id.habits_widget_summary).text.toString().contains("1 source time needs review"))
        assertEquals(View.GONE, mixed.findViewById<View>(R.id.habits_widget_empty).visibility)
        val unresolvedOnly = inflate(HabitsWidgetViews.nextUp(context, state.copy(snapshot = state.snapshot.copy(tasks = emptyList())), nextID, options, now, zone))
        assertEquals(View.GONE, unresolvedOnly.findViewById<View>(R.id.habits_widget_row_1).visibility)
        val detail = unresolvedOnly.findViewById<TextView>(R.id.habits_widget_empty).text.toString()
        assertTrue(detail.contains("clocks were not shifted"))
        assertFalse(detail.contains("recover"))
        assertFalse(detail.contains("No pending"))
        val habits = inflate(HabitsWidgetViews.habits(context, state, habitsID, options, now, zone))
        assertEquals("Read", habits.findViewById<TextView>(R.id.habits_widget_item_title_1).text.toString())
        assertEquals("Log", habits.findViewById<Button>(R.id.habits_widget_action_1).text.toString())
    }

    private fun inflate(views: RemoteViews): View {
        val result = AtomicReference<View>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = views.apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            view.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((480 * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            result.set(view)
        }
        return result.get()
    }
}
