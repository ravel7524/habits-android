package ch.habits.android.localai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.habits.android.calendar.CalendarState
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.data.SourceClockResolution
import ch.habits.android.ui.UiCopy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal data class ReportHighlightDisplay(val title: String, val details: List<String>)

/** Resolves captured typed references, never prompt text or composite string keys. */
internal fun resolveReportHighlight(fact: ReportFact, snapshot: WorkspaceSnapshot?, calendar: CalendarState?,
                                    language: String, zone: ZoneId = ZoneId.systemDefault()): ReportHighlightDisplay? {
    val copy = UiCopy(language)
    fun t(en: String, de: String, es: String) = copy.t(en, de, es)
    fun date(value: String, sourceZone: String? = null): String {
        require(LocalDate.parse(value).year in 1..9999)
        return copy.displayDate(value, relative = true, zone = sourceZone)
    }
    fun time(value: String): String { LocalTime.parse(value); return copy.displayTime(value) }
    fun due(value: String?, clock: String?, sourceZone: String?, assessment: Boolean = false): String {
        if (value == null) { require(clock == null); return t("No deadline recorded", "Keine Frist erfasst", "Sin plazo registrado") }
        val pieces = mutableListOf(date(value, sourceZone))
        if (clock != null) {
            require(SourceClockResolution.instant(value, clock, sourceZone ?: zone.id) != null)
            pieces += time(clock)
        }
        else pieces += if (assessment) t("Time not recorded", "Uhrzeit nicht erfasst", "Hora no registrada")
            else t("Date only", "Nur Datum", "Solo fecha")
        copy.zoneDetail(sourceZone)?.let { pieces += it }
        return pieces.joinToString(" · ")
    }
    fun completed(value: String?): String {
        val finished = Instant.parse(requireNotNull(value)).atZone(zone)
        return t("Completion recorded", "Abschluss erfasst", "Finalización registrada") + " · " +
            date(finished.toLocalDate().toString(), zone.id) + " · " + time(finished.toLocalTime().withSecond(0).withNano(0).toString())
    }
    fun interval(startDate: String, startTime: String, endDate: String, endTime: String, sourceZone: String): String {
        val first = date(startDate, sourceZone) + " · " + time(startTime)
        val last = (if (startDate == endDate) "" else date(endDate, sourceZone) + " · ") + time(endTime)
        return first + "–" + last + copy.zoneDetail(sourceZone)?.let { " · $it" }.orEmpty()
    }
    return runCatching {
        when (val reference = fact.sourceRef ?: return null) {
            is ReportSourceRef.Task -> {
                val task = snapshot?.tasks?.singleOrNull { it.id == reference.record.id } ?: return null
                if (task != reference.record || task.completed != reference.completion || (!reference.completion && task.archived)) return null
                val prep = task.isPreparation
                val sourceKind = if (reference.completion) { if (prep) "preparation-completion" else "task-completion" }
                    else if (task.dueDate == null) { if (prep) "preparation-undated" else "task-undated" }
                    else if (prep) "preparation" else if (task.kind == "assignment") "assignment" else "task"
                if (fact.sourceKind != sourceKind) return null
                val label = if (prep) t("Preparation", "Vorbereitung", "Preparación")
                    else if (task.kind == "assignment") t("Assignment", "Abgabe", "Entrega") else t("Task", "Aufgabe", "Tarea")
                val details = mutableListOf(if (reference.completion) "$label · ${completed(task.completedAt)}"
                    else "$label · ${t("Open", "Offen", "Pendiente")}",
                    if (reference.completion) "" else due(task.dueDate, task.dueTime, task.sourceZone))
                if (!reference.completion && task.minutes > 0) details += t("Planned estimate · ${task.minutes} min",
                    "Geplanter Aufwand · ${task.minutes} Min.", "Estimación prevista · ${task.minutes} min")
                ReportHighlightDisplay(task.title, details.filter { it.isNotEmpty() })
            }
            is ReportSourceRef.Assessment -> {
                val records = snapshot ?: return null
                val assessment = records.assessments.singleOrNull { it.id == reference.record.id } ?: return null
                if (assessment != reference.record || assessment.completed != reference.completion || (!reference.completion && !assessment.pending)) return null
                val courses = records.courses.filter { it.id == assessment.courseID }
                val semesters = records.semesters.filter { it.id == assessment.semesterID }
                if (courses.size > 1 || semesters.size > 1 || courses.singleOrNull() != reference.course || semesters.singleOrNull() != reference.semester) return null
                if (fact.sourceKind != if (reference.completion) "assessment-completion" else "assessment") return null
                val details = mutableListOf<String>()
                reference.course?.let { details += it.name }
                details += if (reference.completion) completed(assessment.completedAt)
                    else t("Assessment · open", "Prüfung / Abgabe · offen", "Evaluación · pendiente")
                if (!reference.completion) details += due(assessment.dueDate, assessment.dueTime, reference.semester?.timeZoneIdentifier, assessment = true)
                ReportHighlightDisplay(assessment.title, details)
            }
            is ReportSourceRef.SavedAppointment -> {
                val appointment = snapshot?.appointments?.singleOrNull { it.id == reference.record.id } ?: return null
                if (appointment != reference.record || appointment.archived || fact.sourceKind != "appointment") return null
                ReportHighlightDisplay(appointment.title, listOf(t("Saved appointment", "Gespeicherter Termin", "Cita guardada"),
                    interval(appointment.startDate, appointment.startTime, appointment.endDate, appointment.endTime, appointment.timeZoneIdentifier)))
            }
            is ReportSourceRef.HabitPlan -> {
                val records = snapshot ?: return null
                val habit = records.habits.singleOrNull { it.id == reference.record.id } ?: return null
                if (habit != reference.record || habit.archived || reference.day.dayOfWeek.value !in habit.weekdays || fact.sourceKind != "habit-plan" ||
                    records.habitLogs.any { it.habitID == habit.id && it.date == reference.day.toString() && it.completed }) return null
                ReportHighlightDisplay(habit.title, listOf(t("Scheduled · no completion logged", "Geplant · kein Abschluss protokolliert", "Programado · sin finalización registrada"), date(reference.day.toString())))
            }
            is ReportSourceRef.HabitCompletion -> {
                val records = snapshot ?: return null
                val habit = records.habits.singleOrNull { it.id == reference.record.id } ?: return null
                val log = records.habitLogs.singleOrNull { it.habitID == reference.log.habitID && it.date == reference.log.date } ?: return null
                if (habit != reference.record || log != reference.log || log.habitID != habit.id || !log.completed || fact.sourceKind != "habit-log") return null
                ReportHighlightDisplay(habit.title, listOf(t("Completion logged", "Abschluss protokolliert", "Finalización registrada"), date(log.date)))
            }
            is ReportSourceRef.CalendarOccurrence -> {
                val source = calendar ?: return null
                val viewerZone = ZoneId.of(reference.viewerZone)
                if (source.busy || !source.covers(reference.day, viewerZone) || fact.sourceKind != "calendar") return null
                val event = source.events.singleOrNull { it.calendarID == reference.record.calendarID && it.eventID == reference.record.eventID && it.startMillis == reference.record.startMillis } ?: return null
                if (event != reference.record || event.calendarID !in source.selectedCalendarIDs) return null
                val whenText = if (event.allDay) {
                    val first = Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    val last = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                    require(last >= first)
                    t("All day", "Ganztägig", "Todo el día") + " · " + date(first.toString()) + if (last == first) "" else "–" + date(last.toString())
                } else {
                    val first = Instant.ofEpochMilli(event.startMillis).atZone(viewerZone)
                    val last = Instant.ofEpochMilli(event.endMillis).atZone(viewerZone)
                    interval(first.toLocalDate().toString(), first.toLocalTime().withSecond(0).withNano(0).toString(),
                        last.toLocalDate().toString(), last.toLocalTime().withSecond(0).withNano(0).toString(), viewerZone.id)
                }
                ReportHighlightDisplay(event.title, listOf(whenText, t("Read-only Calendar", "Kalender · nur lesen", "Calendario de solo lectura") + " · ${event.calendarName}"))
            }
        }
    }.getOrNull()
}

@Composable
internal fun ReportHighlightRow(fact: ReportFact, snapshot: WorkspaceSnapshot?, calendar: CalendarState?, language: String) {
    val copy = UiCopy(language)
    val display = resolveReportHighlight(fact, snapshot, calendar, language)
    Column(Modifier.fillMaxWidth().testTag("localai.report.fact.${fact.id}").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(display?.title ?: copy.t("Recorded item unavailable", "Erfasster Eintrag nicht verfügbar", "Elemento registrado no disponible"),
            style = MaterialTheme.typography.titleSmall)
        val details = display?.details ?: listOf(copy.t("Its source changed or is unavailable. Refresh this report.",
            "Die Quelle wurde geändert oder ist nicht verfügbar. Aktualisiere diesen Bericht.", "La fuente cambió o no está disponible. Actualiza este informe."))
        details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
