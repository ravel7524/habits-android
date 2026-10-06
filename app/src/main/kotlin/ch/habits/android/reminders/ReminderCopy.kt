package ch.habits.android.reminders

import java.util.Locale

internal class ReminderCopy(language: String) {
    private val language = if (language == "system") Locale.getDefault().language else language
    fun t(en: String, de: String, es: String): String = when (language) {
        "de", "german" -> de; "es", "spanish" -> es; else -> en
    }
    fun title(plan: ReminderPlan): String = when (plan.kind) {
        ReminderKind.HABIT -> t("Habits", "Gewohnheiten", "Hábitos")
        ReminderKind.MORNING_REPORT -> t("Morning report", "Morgenbericht", "Informe matutino")
        ReminderKind.EVENING_REPORT -> t("Evening report", "Abendbericht", "Informe nocturno")
        ReminderKind.WEEKLY_REPORT -> t("Weekly review", "Wochenrückblick", "Revisión semanal")
        else -> plan.title
    }
    fun body(plan: ReminderPlan): String = when (plan.kind) {
        ReminderKind.ASSESSMENT -> if (plan.timeSupplied) t("Upcoming assessment. Open Semester to review the recorded date and time.",
            "Anstehende Prüfung oder Abgabe. Öffne Semester, um Datum und Uhrzeit zu prüfen.",
            "Próxima evaluación. Abre Semestre para revisar la fecha y hora registradas.") else
            t("Date: ${plan.sourceDate} (${plan.sourceZone}). Time not supplied.", "Datum: ${plan.sourceDate} (${plan.sourceZone}). Uhrzeit nicht angegeben.",
                "Fecha: ${plan.sourceDate} (${plan.sourceZone}). Hora no indicada.")
        ReminderKind.TASK -> if (plan.timeSupplied) t("Open Planner to review this deadline.", "Öffne den Planer, um diese Frist zu prüfen.", "Abre el Planificador para revisar este plazo.")
            else t("Due date: ${plan.sourceDate}. Time not supplied.", "Fristdatum: ${plan.sourceDate}. Uhrzeit nicht angegeben.", "Fecha límite: ${plan.sourceDate}. Hora no indicada.")
        ReminderKind.APPOINTMENT -> t("Upcoming appointment. Open Planner to review.", "Anstehender Termin. Öffne den Planer für die Details.", "Próxima cita. Abre el Planificador para revisarla.")
        ReminderKind.HABIT -> t("Open Habits to record today's scheduled routines.", "Öffne Gewohnheiten, um die heutigen Routinen zu protokollieren.", "Abre Hábitos para registrar las rutinas de hoy.")
        else -> t("Open this report when you are ready. This invitation does not generate a report.", "Öffne diesen Bericht, wenn du bereit bist. Diese Einladung erzeugt keinen Bericht.", "Abre este informe cuando estés listo. Esta invitación no genera un informe.")
    }
}
