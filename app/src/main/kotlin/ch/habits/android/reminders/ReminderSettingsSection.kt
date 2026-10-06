package ch.habits.android.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ch.habits.android.ui.UiCopy
import java.util.Locale

/** Device-local opt-ins. Opening this panel never asks for permission or enables a type. */
@Composable
fun ReminderSettingsSection(context: Context, language: String) {
    val scope = rememberCoroutineScope()
    val lang = if (language == "system") Locale.getDefault().language else language
    val uiCopy = remember(lang) { UiCopy(lang) }
    fun t(en: String, de: String, es: String) = when (lang) { "de", "german" -> de; "es", "spanish" -> es; else -> en }
    var state by remember { mutableStateOf<ReminderDeviceState?>(null) }
    var allowed by remember { mutableStateOf(AndroidReminders.permissionAvailable(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    fun reload() { scope.launch {
        allowed = AndroidReminders.permissionAvailable(context)
        try { state = AndroidReminders.readState(context); error = null }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            state = null
            error = t("Settings could not be read. Original data is preserved; reminders remain off.", "Einstellungen nicht lesbar. Originaldaten bleiben erhalten; Erinnerungen bleiben aus.", "No se pudieron leer los ajustes. Se conservan los datos; los recordatorios siguen desactivados.")
        }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        reload()
        scope.launch { runCatching { AndroidReminders.reconcile(context) } }
    }
    LaunchedEffect(language) { AndroidReminders.createChannels(context, language); reload() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun change(prefs: ReminderPreferences) {
        if (busy) return
        busy = true
        scope.launch {
            try { AndroidReminders.configure(context, prefs); state = AndroidReminders.readState(context); error = null }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                state = runCatching { AndroidReminders.readState(context) }.getOrNull()
                error = t("Update paused. Check the current choices and Android notification settings.", "Aktualisierung angehalten. Prüfe die Auswahl und Android-Mitteilungseinstellungen.", "Actualización pausada. Revisa las opciones y los ajustes de Android.")
            } finally { busy = false }
        }
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    fun channelBlocked(kind: ReminderKind) = manager.getNotificationChannel(AndroidReminders.channel(kind))?.importance == NotificationManager.IMPORTANCE_NONE
    fun enabled(checked: Boolean, kind: ReminderKind) = state != null && !busy && (checked || (allowed && !channelBlocked(kind)))
    fun blocked(kind: ReminderKind): String? = when {
        !allowed -> t("Blocked until permission is allowed", "Bis zur Berechtigung blockiert", "Bloqueado hasta permitir notificaciones")
        channelBlocked(kind) -> t("Channel blocked in Android", "Kanal in Android blockiert", "Canal bloqueado en Android")
        else -> null
    }
    val prefs = state?.preferences ?: ReminderPreferences()
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    fun androidSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { error = t("Android settings could not open. Use the phone's Settings → Apps → Habits.", "Android-Einstellungen konnten nicht geöffnet werden. Nutze Einstellungen → Apps → Habits.", "No se pudieron abrir los ajustes. Usa Ajustes → Aplicaciones → Habits.") }
    }
    Column(Modifier.fillMaxWidth().testTag("reminders.settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("Choose what may alert you. Off by default.", "Wähle, was dich erinnern darf. Standardmäßig ausgeschaltet.", "Elige qué puede avisarte. Desactivados por defecto."), style = MaterialTheme.typography.bodyMedium)
        Text(if (!allowed) t("Android notifications are blocked", "Android-Mitteilungen sind blockiert", "Las notificaciones de Android están bloqueadas")
            else t("Android notifications are allowed", "Android-Mitteilungen sind erlaubt", "Las notificaciones de Android están permitidas"),
            style = MaterialTheme.typography.bodySmall, color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("reminders.permission.status").semantics { liveRegion = LiveRegionMode.Polite })
        if (!allowed) Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else androidSettings()
        }, modifier = Modifier.fillMaxWidth().testTag("reminders.permission")) { Text(t("Allow notifications", "Mitteilungen erlauben", "Permitir notificaciones")) }
        if (state == null && error == null) Text(t("Reading device choices…", "Geräteauswahl wird gelesen…", "Leyendo opciones del dispositivo…"), style = MaterialTheme.typography.bodySmall)
        ReminderGroup(t("Deadlines", "Fristen", "Plazos"))
        ReminderOption(t("Tasks and assignments", "Aufgaben und Abgaben", "Tareas y entregas"), blocked(ReminderKind.TASK), prefs.tasksEnabled, "tasks", enabled(prefs.tasksEnabled, ReminderKind.TASK)) { change(prefs.copy(tasksEnabled = it)) }
        ReminderOption(t("Exams and assessments", "Prüfungen und Bewertungen", "Exámenes y evaluaciones"), blocked(ReminderKind.ASSESSMENT), prefs.assessmentsEnabled, "assessments", enabled(prefs.assessmentsEnabled, ReminderKind.ASSESSMENT)) { change(prefs.copy(assessmentsEnabled = it)) }
        ReminderOption(t("Include imported preparation", "Importierte Vorbereitung einbeziehen", "Incluir preparación importada"),
            t("Uses task reminders; separate from exam completion", "Nutzt Aufgabenerinnerungen; getrennt vom Prüfungsabschluss", "Usa avisos de tareas; separado de completar el examen"),
            prefs.includePreparation, "preparation", enabled(prefs.includePreparation, ReminderKind.TASK)) { change(prefs.copy(includePreparation = it)) }
        val dateTime = uiCopy.displayTime("09:00")
        Text(t("Without a supplied time: a date reminder at $dateTime, not an exam start time.", "Ohne angegebene Uhrzeit: Datumserinnerung um $dateTime, keine Prüfungsstartzeit.", "Sin hora indicada: aviso de fecha a las $dateTime, no una hora de inicio del examen."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReminderGroup(t("Appointments and habits", "Termine und Gewohnheiten", "Citas y hábitos"))
        ReminderOption(t("Saved appointments", "Gespeicherte Termine", "Citas guardadas"), blocked(ReminderKind.APPOINTMENT) ?:
            t("${prefs.appointmentLeadMinutes} minutes before", "${prefs.appointmentLeadMinutes} Minuten vorher", "${prefs.appointmentLeadMinutes} minutos antes"),
            prefs.appointmentsEnabled, "appointments", enabled(prefs.appointmentsEnabled, ReminderKind.APPOINTMENT)) { change(prefs.copy(appointmentsEnabled = it)) }
        ReminderOption(t("Daily habit invitation", "Tägliche Gewohnheitseinladung", "Invitación diaria de hábitos"), blocked(ReminderKind.HABIT) ?: uiCopy.displayTime(prefs.habitTime),
            prefs.habitsEnabled, "habits", enabled(prefs.habitsEnabled, ReminderKind.HABIT)) { change(prefs.copy(habitsEnabled = it)) }
        Text(t("External calendars keep their provider's alerts.", "Externe Kalender behalten die Benachrichtigungen ihres Anbieters.", "Los calendarios externos conservan los avisos de su proveedor."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReminderGroup(t("Report invitations", "Berichtseinladungen", "Invitaciones a informes"))
        Text(t("Open a report; no background AI generation.", "Öffne einen Bericht; keine KI-Erzeugung im Hintergrund.", "Abren un informe; sin IA en segundo plano."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReminderOption(t("Morning", "Morgen", "Mañana"), blocked(ReminderKind.MORNING_REPORT) ?: uiCopy.displayTime("08:00"), prefs.morningEnabled, "morning", enabled(prefs.morningEnabled, ReminderKind.MORNING_REPORT)) { change(prefs.copy(morningEnabled = it)) }
        ReminderOption(t("Evening", "Abend", "Noche"), blocked(ReminderKind.EVENING_REPORT) ?: uiCopy.displayTime("20:00"), prefs.eveningEnabled, "evening", enabled(prefs.eveningEnabled, ReminderKind.EVENING_REPORT)) { change(prefs.copy(eveningEnabled = it)) }
        val weeklyTime = uiCopy.displayTime("18:00")
        ReminderOption(t("Weekly review", "Wochenrückblick", "Revisión semanal"), blocked(ReminderKind.WEEKLY_REPORT) ?: t("Sunday · $weeklyTime", "Sonntag · $weeklyTime", "Domingo · $weeklyTime"),
            prefs.weeklyEnabled, "weekly", enabled(prefs.weeklyEnabled, ReminderKind.WEEKLY_REPORT)) { change(prefs.copy(weeklyEnabled = it)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val quiet = "${uiCopy.displayTime("22:00")}–${uiCopy.displayTime("07:00")}"
        Text(t("Quiet hours $quiet · Snooze 15 minutes. Android may delay or suppress these inexact alerts.",
            "Ruhezeit $quiet · Schlummern 15 Minuten. Android kann diese ungenauen Hinweise verzögern oder verhindern.",
            "Silencio $quiet · Posponer 15 minutos. Android puede retrasar o impedir estos avisos inexactos."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t("Device-only choices, excluded from backups and assistant sharing.", "Gerätebezogene Auswahl, nicht in Sicherungen oder Assistentenfreigaben.", "Opciones del dispositivo, excluidas de copias y de compartir con asistentes."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            try { AndroidReminders.testNotification(context); error = null }
            catch (_: Exception) { error = t("Test alert could not be sent. Check Android permission and the report channel.", "Testhinweis konnte nicht gesendet werden. Prüfe Android-Berechtigung und Berichtskanal.", "No se pudo enviar el aviso. Revisa el permiso y el canal de informes.") }
        }, enabled = allowed && !busy && !channelBlocked(ReminderKind.MORNING_REPORT), colors = outlineColors,
            modifier = Modifier.fillMaxWidth().testTag("reminders.test")) { Text(t("Send test notification", "Testmitteilung senden", "Enviar notificación de prueba")) }
        TextButton(onClick = ::androidSettings, colors = textColors, modifier = Modifier.testTag("reminders.androidSettings")) { Text(t("Android notification settings", "Android-Mitteilungseinstellungen", "Ajustes de notificaciones de Android")) }
        TextButton(onClick = { details = !details }, colors = textColors, modifier = Modifier.testTag("reminders.details")) {
            Text(if (details) t("Hide delivery details", "Zustellungsdetails ausblenden", "Ocultar detalles de avisos") else t("Delivery details", "Details zur Zustellung", "Detalles de avisos"))
        }
        if (details) Text(t("Only current, active sources are eligible. Completed or archived items cancel their alerts; stale deliveries are skipped. Imported preparation stays independently opt-in. Channel controls and battery restrictions belong to Android; these alerts do not reserve Calendar time.",
            "Nur aktuelle, aktive Quellen kommen infrage. Erledigte oder archivierte Einträge beenden ihre Hinweise; veraltete Zustellungen werden übersprungen. Importierte Vorbereitung bleibt separat optional. Kanal- und Energiesparregeln gehören zu Android; Hinweise reservieren keine Kalenderzeit.",
            "Solo se usan fuentes actuales y activas. Los elementos completados o archivados cancelan sus avisos; se omiten los obsoletos. La preparación importada sigue siendo opcional por separado. Android controla canales y batería; los avisos no reservan tiempo."), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("reminders.error").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun ReminderGroup(label: String) {
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ReminderOption(label: String, detail: String?, checked: Boolean, key: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, enabled = enabled, onValueChange = onChange)
        .padding(vertical = 5.dp).testTag("reminders.$key"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
