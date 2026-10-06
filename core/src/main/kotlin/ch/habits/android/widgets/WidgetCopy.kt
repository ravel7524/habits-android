package ch.habits.android.widgets

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WidgetCopy(language: String, systemLanguage: String = Locale.getDefault().language) {
    private val language = if (language == "system") systemLanguage else language
    private val locale = Locale.forLanguageTag(this.language)
    fun t(en: String, de: String, es: String): String = when (language) { "de" -> de; "es" -> es; else -> en }
    val nextUp get() = t("Next up", "Als Nächstes", "A continuación")
    val todayHabits get() = t("Today's habits", "Gewohnheiten heute", "Hábitos de hoy")
    val openApp get() = t("Open app", "App öffnen", "Abrir app")
    val recovery get() = t("Open the app to recover your preserved data.", "Öffne die App, um deine erhaltenen Daten wiederherzustellen.", "Abre la app para recuperar tus datos conservados.")
    val emptyNext get() = t("No pending deadlines or appointments", "Keine offenen Fristen oder Termine", "Sin plazos ni citas pendientes")
    val emptyHabits get() = t("No habits scheduled today", "Heute keine Gewohnheiten geplant", "Sin hábitos programados para hoy")
    val log get() = t("Log", "Erfassen", "Registrar")
    val undoLog get() = t("Undo log", "Zurück", "Deshacer")
    val logged get() = t("Logged today", "Heute erfasst", "Registrado hoy")
    val unrecorded get() = t("Not recorded today", "Heute nicht erfasst", "Sin registro hoy")
    fun nextSummary(visible: Int, total: Int) = if (visible == 0)
        t("$total pending · Open Planner", "$total offen · Planer öffnen", "$total pendientes · Abrir Planificador") else
        t("$visible of $total · Open Planner for all", "$visible von $total · Alle im Planer", "$visible de $total · Ver todos en Planificador")
    fun habitSummary(completed: Int, scheduled: Int) = t("$completed/$scheduled scheduled habits recorded", "$completed/$scheduled geplante Gewohnheiten erfasst", "$completed/$scheduled hábitos programados registrados")
    fun updated(projection: java.time.Instant, zone: ZoneId) = t("Updated ", "Stand ", "Actualizado ") +
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(projection.atZone(zone))
    fun whenText(item: NextUpEntry): String {
        val day = java.time.LocalDate.parse(item.date).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        val clock = item.time ?: t("date only", "nur Datum", "solo fecha")
        val zone = item.sourceZone ?: t("device time zone", "Gerätezeitzone", "zona del dispositivo")
        val state = when { item.ongoing -> t("Now · ", "Jetzt · ", "Ahora · "); item.overdue -> t("Overdue · ", "Überfällig · ", "Vencido · "); else -> "" }
        val kind = when (item.kind) {
            WidgetEntryKind.TASK -> t("Task", "Aufgabe", "Tarea")
            WidgetEntryKind.ASSIGNMENT -> t("Assignment", "Abgabe", "Entrega")
            WidgetEntryKind.ASSESSMENT -> t("Assessment", "Prüfung/Frist", "Evaluación")
            WidgetEntryKind.APPOINTMENT -> t("Appointment", "Termin", "Cita")
        }
        return "$state$kind · $day · $clock · $zone"
    }
}
