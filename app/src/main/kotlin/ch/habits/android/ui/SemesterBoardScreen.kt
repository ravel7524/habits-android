@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.habits.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.habits.android.data.AcademicAssessment
import ch.habits.android.data.AcademicCourse
import ch.habits.android.data.AcademicSemester
import ch.habits.android.data.AcademicStatus
import ch.habits.android.data.AcademicWeekEntry
import ch.habits.android.data.WorkspaceSnapshot
import ch.habits.android.semester.SemesterBoardProjection
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SemesterWeekSelection(val semesterID: String, val courseID: String, val week: Int)

@Composable
internal fun SemesterBoardScreen(
    snapshot: WorkspaceSnapshot,
    selectedSemesterID: String?,
    copy: UiCopy,
    writesEnabled: Boolean,
    onSelectSemester: (String) -> Unit,
    onAssistant: () -> Unit,
    onImport: () -> Unit,
    onWeek: (SemesterWeekSelection) -> Unit,
    onAssessment: (AcademicAssessment) -> Unit,
    onAllAssessments: () -> Unit,
    onLegacyAssistant: () -> Unit = onAssistant,
) {
    val semesters = snapshot.semesters.filterNot { it.archived }
    val semester = semesters.firstOrNull { it.id == selectedSemesterID } ?: semesters.firstOrNull()
    val courses = semester?.let { SemesterBoardProjection.courses(snapshot, it) }.orEmpty()
    val statuses = semester?.let(SemesterBoardProjection::statuses).orEmpty()
    val builtIns = remember(semester?.id) { semester?.let { SemesterBoardProjection.statuses(it.copy(statuses = emptyList())) }.orEmpty() }
    val weekCount = semester?.let { SemesterBoardProjection.displayWeekCount(snapshot, it) } ?: 0
    val weekNumbers = (1..weekCount).toList()
    val current by produceState<Int?>(semester?.let { SemesterBoardProjection.currentWeek(snapshot, it) }, snapshot, semester?.id) {
        if (semester == null) value = null else while (true) {
            value = SemesterBoardProjection.currentWeek(snapshot, semester, Instant.now())
            delay(60_000)
        }
    }
    // Capture the clock before the deferred lazy builder, just like the source rows.
    val currentWeek = current
    val density = LocalDensity.current
    val courseWidth = if (density.fontScale >= 1.6f) 170.dp else 154.dp
    val weekWidth = maxOf(64f, 54f * density.fontScale).dp
    val weekWidthPx = with(density) { weekWidth.roundToPx() }
    val horizontal = remember(semester?.id) { ScrollState(0) }
    val vertical = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var viewportWidth by remember(semester?.id) { mutableIntStateOf(0) }
    var initialJumpDone by remember(semester?.id) { mutableStateOf(false) }
    var toolsExpanded by remember(semester?.id) { mutableStateOf(false) }
    val populated = semester != null && courses.isNotEmpty()
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    val outlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    val rows = remember(snapshot, semester, weekCount) {
        if (semester == null) emptyList() else courses.map { course ->
            BoardCourseRow(course, weekNumbers.map { week ->
                BoardWeekCell(week, course.entries[week], course.activeWeeks?.contains(week),
                    SemesterBoardProjection.assessments(snapshot, course, semester, week))
            })
        }
    }
    fun currentOffset(): Int = (((currentWeek ?: 1) - 1) * weekWidthPx - (viewportWidth - weekWidthPx) / 2).coerceAtLeast(0)
    LaunchedEffect(semester?.id, viewportWidth) {
        if (!initialJumpDone && viewportWidth > 0) {
            horizontal.scrollTo(currentOffset())
            initialJumpDone = true
        }
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("semester.board.scroll"), state = vertical,
        contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item(key = "semester.controls") {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(semester?.title ?: copy.semester, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("semester.title"))
                    if (populated) Box {
                        TextButton(onClick = { toolsExpanded = !toolsExpanded }, colors = textColors,
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("semester.button.tools").semantics {
                                stateDescription = if (toolsExpanded) copy.t("Expanded", "Ausgeklappt", "Expandido") else copy.t("Collapsed", "Eingeklappt", "Contraído")
                            }) {
                            Text(copy.t("Board tools", "Board-Optionen", "Opciones"), style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                            DropdownMenuItem(text = { Text(copy.t("Plan with an assistant", "Mit Assistenten planen", "Planificar con un asistente")) },
                                onClick = { toolsExpanded = false; onAssistant() }, enabled = writesEnabled, modifier = Modifier.testTag("semester.button.assistant"),
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary))
                            DropdownMenuItem(text = { Text(copy.t("Import semester JSON", "Semester-JSON importieren", "Importar JSON del semestre")) },
                                onClick = { toolsExpanded = false; onImport() }, enabled = writesEnabled, modifier = Modifier.testTag("semester.button.import"),
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary))
                            DropdownMenuItem(text = { Text(copy.t("Import exams and preparation", "Prüfungen und Vorbereitung importieren", "Importar exámenes y preparación")) },
                                onClick = { toolsExpanded = false; onLegacyAssistant() }, enabled = writesEnabled, modifier = Modifier.testTag("semester.button.examPlan"),
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                if (semesters.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    semesters.forEach { option -> FilterChip(selected = option.id == semester?.id, onClick = { onSelectSemester(option.id) },
                        label = { Text(option.title) }, modifier = Modifier.testTag("semester.pick.${option.id}")) }
                }
                Text(copy.localOnly, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!populated) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onAssistant, enabled = writesEnabled, modifier = Modifier.testTag("semester.button.assistant")) {
                        Text(copy.t("Plan with an assistant", "Mit Assistenten planen", "Planificar con un asistente"))
                    }
                    OutlinedButton(onClick = onImport, enabled = writesEnabled, colors = outlinedColors, modifier = Modifier.testTag("semester.button.import")) {
                        Text(copy.t("Import semester JSON", "Semester-JSON importieren", "Importar JSON del semestre"))
                    }
                    TextButton(onClick = onLegacyAssistant, enabled = writesEnabled, colors = textColors,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp), modifier = Modifier.testTag("semester.button.examPlan")) {
                        Text(copy.t("Import exams and preparation", "Prüfungen und Vorbereitung importieren", "Importar exámenes y preparación"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (semester != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onAllAssessments, colors = textColors, modifier = Modifier.testTag("semester.button.assessments")) {
                        Text(copy.t("Exams and deadlines", "Prüfungen und Fristen", "Exámenes y plazos"))
                    }
                    if (currentWeek != null && courses.isNotEmpty()) TextButton(onClick = {
                        scope.launch {
                            // Controls and summary precede the board header in this list.
                            vertical.animateScrollToItem(2)
                            horizontal.animateScrollTo(currentOffset())
                        }
                    }, colors = textColors, modifier = Modifier.testTag("semester.button.currentWeek")) {
                        Text(copy.t("Current week", "Aktuelle Woche", "Semana actual"))
                    }
                }
            }
        }
        item(key = "semester.summary") {
            when {
                semester == null -> BoardEmpty(copy.t("No semester yet", "Noch kein Semester", "Todavía no hay un semestre"),
                    copy.t("Import a Semester Board file to see courses, weekly progress and assessments. You review it before saving.",
                        "Importiere eine Semester-Board-Datei für Kurse, Wochenstatus und Prüfungen. Du prüfst sie vor dem Speichern.",
                        "Importa un archivo de Semester Board para ver cursos, progreso semanal y evaluaciones. Lo revisas antes de guardar."), Modifier.testTag("semester.empty"))
                courses.isEmpty() -> BoardEmpty(copy.t("No courses in this semester", "Keine Kurse in diesem Semester", "No hay cursos en este semestre"),
                    copy.t("Import a file with courses, or review an assistant’s proposal. Existing records stay intact.",
                        "Importiere eine Datei mit Kursen oder prüfe einen Assistentenvorschlag. Bestehende Daten bleiben erhalten.",
                        "Importa un archivo con cursos o revisa una propuesta del asistente. Se conservan los registros existentes."))
                semester.startDate == null -> BoardEmpty(copy.t("Week dates are not recorded", "Wochendaten sind nicht erfasst", "No hay fechas semanales registradas"),
                    copy.t("Progress can still be recorded. Import a file with semester dates to place assessments in weeks; use the assessment list for all recorded deadlines.",
                        "Wochenstatus bleibt nutzbar. Importiere eine Datei mit Semesterdaten, um Prüfungen Wochen zuzuordnen. Alle erfassten Fristen stehen in der Prüfungsliste.",
                        "Puedes registrar el progreso. Importa las fechas del semestre para situar evaluaciones en semanas; la lista muestra todos los plazos registrados."), Modifier.testTag("semester.dates.missing"))
                else -> Text(copy.displayDate(semester.startDate) + " · " + copy.quantity(semester.weekCount,
                    "teaching week", "teaching weeks", "Unterrichtswoche", "Unterrichtswochen", "semana lectiva", "semanas lectivas"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
        if (semester != null && courses.isNotEmpty()) {
            stickyHeader(key = "semester.grid.header.${semester.id}") {
                BoardWeekHeader(semester, weekNumbers, currentWeek, copy, courseWidth, weekWidth, horizontal) { viewportWidth = it }
            }
            items(rows, key = { "semester.course.${it.course.id}" }) { row ->
                BoardCourseWeekRow(row, semester, statuses, builtIns, currentWeek, copy, courseWidth, weekWidth, horizontal, onWeek, onAssessment)
            }
            item(key = "semester.legend.${semester.id}") {
                BoardLegend(statuses, builtIns, copy)
            }
        }
    }
}

@Composable
private fun BoardWeekHeader(semester: AcademicSemester, weeks: List<Int>, currentWeek: Int?, copy: UiCopy,
    courseWidth: Dp, weekWidth: Dp, horizontal: ScrollState, onViewport: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).height(IntrinsicSize.Min).testTag("semester.grid.header")) {
        Box(Modifier.width(courseWidth).fillMaxHeight().padding(vertical = 16.dp, horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(copy.t("Courses", "Kurse", "Cursos"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.weight(1f).onSizeChanged { onViewport(it.width) }.horizontalScroll(horizontal).fillMaxHeight().testTag("semester.grid.scroll")) {
            weeks.forEach { week ->
                val date = SemesterBoardProjection.dateForWeek(semester, week)
                val tint = if (week > semester.weekCount) examMarkerColor() else if (week == currentWeek) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Column(Modifier.width(weekWidth).fillMaxHeight().background(weekHighlight(week == currentWeek)).padding(horizontal = 4.dp, vertical = 12.dp)
                    .testTag("semester.week.$week").clearAndSetSemantics {
                        heading()
                        contentDescription = weekLabel(week, copy) + (if (week == currentWeek) ", " + copy.t("Current week", "Aktuelle Woche", "Semana actual") else "") +
                            ", " + (date?.let { copy.displayDate(it) } ?: copy.t("Date not supplied", "Datum nicht angegeben", "Fecha no indicada"))
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(week.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
                    if (week > semester.weekCount) Text(copy.t("Exam", "Prüfung", "Examen"), style = MaterialTheme.typography.labelSmall, color = tint, textAlign = TextAlign.Center)
                    Text(date?.let { shortWeekDate(it, copy) } ?: "—", style = MaterialTheme.typography.labelSmall, color = tint, textAlign = TextAlign.Center)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BoardCourseWeekRow(row: BoardCourseRow, semester: AcademicSemester, statuses: List<AcademicStatus>, builtIns: List<AcademicStatus>, currentWeek: Int?, copy: UiCopy,
    courseWidth: Dp, weekWidth: Dp, horizontal: ScrollState, onWeek: (SemesterWeekSelection) -> Unit, onAssessment: (AcademicAssessment) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 76.dp)) {
        Column(Modifier.width(courseWidth).fillMaxHeight().padding(start = 8.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
            .testTag("semester.course.${row.course.id}").clearAndSetSemantics {
                contentDescription = row.course.name + row.course.moduleCode?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
            }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(row.course.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            row.course.moduleCode?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(horizontal).fillMaxHeight().testTag("semester.grid.scroll.${row.course.id}")) {
            row.weeks.forEach { cell ->
                val status = statuses.firstOrNull { it.id == cell.entry?.statusID }
                val statusName = status?.let { statusLabel(it, builtIns, copy) } ?: if (cell.entry?.statusID != null)
                    copy.t("Unknown status", "Unbekannter Status", "Estado desconocido") else noStatus(copy)
                val selection = SemesterWeekSelection(semester.id, row.course.id, cell.week)
                val label = row.course.name + ", " + weekLabel(cell.week, copy) + ", " + statusName + ", " + scheduleLabel(cell.active, copy) +
                    markerSummary(cell.assessments, copy) + (if (!cell.entry?.notes.isNullOrBlank()) ", " + copy.t("Week notes available", "Wochennotizen vorhanden", "Hay notas de la semana") else "") +
                    (if (!row.course.scheduleNote.isNullOrBlank()) ", " + copy.t("Schedule note available", "Kursplannotiz vorhanden", "Hay una nota de horario") else "")
                Column(Modifier.width(weekWidth).fillMaxHeight().heightIn(min = 48.dp).background(weekHighlight(cell.week == currentWeek))
                    .clickable(role = Role.Button, onClickLabel = copy.t("Open week details", "Wochendetails öffnen", "Abrir detalles de la semana")) { onWeek(selection) }
                    .testTag("semester.cell.${row.course.id}.${cell.week}").semantics(mergeDescendants = true) {
                        contentDescription = label
                        customActions = cell.assessments.take(10).map { assessment -> CustomAccessibilityAction(assessment.title + ", " + assessmentState(assessment, copy)) {
                            onAssessment(assessment); true
                        } }
                    }.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    when {
                        status != null -> StatusDot(statusColor(status.colorHex, MaterialTheme.colorScheme.onSurfaceVariant), 14.dp)
                        cell.entry?.statusID != null -> Text("?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.clearAndSetSemantics {})
                        cell.active == false && cell.entry == null -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                        else -> EmptyStatusDot()
                    }
                    if (cell.assessments.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cell.assessments.any { it.kind == "exam" }) AssessmentMarker(true)
                        if (cell.assessments.any { it.kind != "exam" }) AssessmentMarker(false)
                        if (cell.assessments.size > 1) Text(cell.assessments.size.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BoardLegend(statuses: List<AcademicStatus>, builtIns: List<AcademicStatus>, copy: UiCopy) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp).testTag("semester.legend"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(copy.t("Status labels", "Statusbezeichnungen", "Etiquetas de estado"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            statuses.forEach { status -> Row(Modifier.widthIn(max = 260.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor(status.colorHex, MaterialTheme.colorScheme.onSurfaceVariant), 12.dp)
                Text(statusLabel(status, builtIns, copy), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { AssessmentMarker(true); Text(copy.t("Exam", "Prüfung", "Examen"), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { AssessmentMarker(false); Text(copy.t("Other assessment", "Andere Prüfung oder Frist", "Otra evaluación"), style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EmptyStatusDot()
            Text(noStatus(copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(copy.t("A dash marks an inactive week with no entry. Tap a week for its status, notes and assessments. Status labels are your records, not evidence of attendance or time studied.",
            "Ein Strich kennzeichnet eine inaktive Woche ohne Eintrag. Tippe auf eine Woche für Status, Notizen und Prüfungen. Statusbezeichnungen sind deine Einträge, kein Nachweis für Anwesenheit oder Lernzeit.",
            "Un guion indica una semana inactiva sin entrada. Toca una semana para ver estado, notas y evaluaciones. Las etiquetas son tus registros, no pruebas de asistencia ni tiempo estudiado."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SemesterWeekDetailDialog(
    snapshot: WorkspaceSnapshot,
    selection: SemesterWeekSelection,
    copy: UiCopy,
    writesEnabled: Boolean,
    error: String?,
    onSetStatus: (String?, AcademicWeekEntry?, List<AcademicStatus>) -> Unit,
    onReload: () -> Unit,
    onPrepare: () -> Unit,
    onAssessment: (AcademicAssessment) -> Unit,
    onDismiss: () -> Unit,
    recoveryRequired: Boolean = false,
) {
    val semester = snapshot.semesters.firstOrNull { it.id == selection.semesterID && !it.archived }
    val course = semester?.let { SemesterBoardProjection.courses(snapshot, it).firstOrNull { item -> item.id == selection.courseID } }
    var baseline by remember(selection) { mutableStateOf(captureWeekBaseline(semester, course, selection.week)) }
    var pending by remember(selection) { mutableStateOf<PendingStatusChoice?>(null) }
    val currentEntry = course?.entries?.get(selection.week)
    val currentDefinitions = semester?.statuses.orEmpty()
    // Rebase only an observed result matching our exact status-only change.
    // Failures and unrelated source edits always require explicit Reload.
    LaunchedEffect(currentEntry, currentDefinitions, error) {
        val requested = pending
        if (requested != null && error == null && requested.matches(currentEntry, currentDefinitions)) {
            baseline = captureWeekBaseline(semester, course, selection.week)
            pending = null
        }
    }
    val observedOwnChange = error == null && pending?.matches(currentEntry, currentDefinitions) == true
    val stale = !observedOwnChange && (baseline.entry != currentEntry || baseline.persistedStatuses != currentDefinitions)
    val available = semester != null && course != null && selection.week in 1..52
    val canChange = available && writesEnabled && !recoveryRequired && error == null && !stale && pending == null
    val date = semester?.let { SemesterBoardProjection.dateForWeek(it, selection.week) }
    val assessments = if (semester != null && course != null) SemesterBoardProjection.assessments(snapshot, course, semester, selection.week) else emptyList()
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("semester.week.detail"),
        title = { Text(weekLabel(selection.week, copy)) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (recoveryRequired) {
                Text(copy.t("Read-only during recovery", "Schreibgeschützt während der Wiederherstellung", "Solo lectura durante la recuperación"),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("semester.week.recovery"))
                Text(copy.t("The original file is preserved. Recover your data in Settings before changing a status or planning preparation.",
                    "Die Originaldatei bleibt erhalten. Stelle deine Daten in den Einstellungen wieder her, bevor du einen Status änderst oder Vorbereitung planst.",
                    "Se conserva el archivo original. Recupera tus datos en Ajustes antes de cambiar un estado o planificar preparación."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!available) Text(copy.t("This course or week is no longer available.", "Dieser Kurs oder diese Woche ist nicht mehr verfügbar.", "Este curso o esta semana ya no está disponible.")) else {
                Text(course!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                course.moduleCode?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(date?.let { copy.displayDate(it) } ?: copy.t("Week date not supplied", "Wochendatum nicht angegeben", "Fecha de la semana no indicada"))
                copy.zoneDetail(semester!!.timeZoneIdentifier)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(scheduleLabel(course.activeWeeks?.contains(selection.week), copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null || stale || pending != null) {
                    if (error != null || stale) Text(copy.t("Reload before changing this status", "Vor der Statusänderung neu laden", "Recarga antes de cambiar este estado"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(if (error == null && !stale) copy.t("Waiting for status confirmation", "Warte auf die Statusbestätigung", "Esperando confirmación del estado") else if (stale) copy.t("This entry or its status labels changed. Review the current data before trying again.",
                        "Dieser Eintrag oder seine Statusbezeichnungen wurden geändert. Prüfe die aktuellen Daten vor einem neuen Versuch.",
                        "Esta entrada o sus etiquetas cambiaron. Revisa los datos actuales antes de reintentarlo.") else
                        copy.t("The status change could not be confirmed. Reload current data before trying again.",
                            "Die Statusänderung konnte nicht bestätigt werden. Lade vor einem neuen Versuch die aktuellen Daten.",
                            "No se pudo confirmar el cambio de estado. Recarga los datos actuales antes de reintentarlo."),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(if (error != null || stale) "semester.week.error" else "semester.week.pending"))
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = {
                        baseline = captureWeekBaseline(semester, course, selection.week)
                        pending = null
                        onReload()
                    }, colors = actionColors, modifier = Modifier.testTag("semester.week.reload")) { Text(copy.t("Reload current data", "Aktuellen Stand laden", "Recargar datos actuales")) }
                }
                Text(copy.t("Status", "Status", "Estado"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (baseline.entry?.statusID != null && baseline.options.none { it.id == baseline.entry?.statusID }) {
                    Text(copy.t("The recorded status label is unknown. Choose a label only if you want to replace it.",
                        "Die erfasste Statusbezeichnung ist unbekannt. Wähle nur eine Bezeichnung, wenn du sie ersetzen möchtest.",
                        "La etiqueta registrada es desconocida. Elige otra solo si quieres sustituirla."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.selectableGroup().testTag("semester.week.status.pick")) {
                    WeekStatusChoice(null, noStatus(copy), null, baseline.entry?.statusID, canChange, "semester.status.option.none") { chosen ->
                        if (chosen != baseline.entry?.statusID) {
                            val reviewed = baseline
                            pending = PendingStatusChoice(chosen, reviewed)
                            onSetStatus(chosen, reviewed.entry, reviewed.persistedStatuses)
                        }
                    }
                    baseline.options.forEach { status ->
                        WeekStatusChoice(status.id, statusLabel(status, baseline.builtIns, copy), statusColor(status.colorHex, MaterialTheme.colorScheme.onSurfaceVariant),
                            baseline.entry?.statusID, canChange, "semester.status.option.${status.id}") { chosen ->
                            if (chosen != baseline.entry?.statusID) {
                                val reviewed = baseline
                                pending = PendingStatusChoice(chosen, reviewed)
                                onSetStatus(chosen, reviewed.entry, reviewed.persistedStatuses)
                            }
                        }
                    }
                }
                baseline.entry?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(copy.t("Week notes", "Wochennotizen", "Notas de la semana"), style = MaterialTheme.typography.titleSmall)
                    Text(notes, modifier = Modifier.testTag("semester.week.notes"))
                }
                course.scheduleNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(copy.t("Schedule note", "Kursplannotiz", "Nota de horario"), style = MaterialTheme.typography.titleSmall)
                    Text(note, modifier = Modifier.testTag("semester.week.scheduleNote"))
                }
                HorizontalDivider()
                Text(copy.t("Exams and deadlines", "Prüfungen und Fristen", "Exámenes y plazos"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (assessments.isEmpty()) Text(if (date == null) copy.t("Semester dates are needed to place assessments in weeks. The board’s assessment list still shows recorded deadlines.",
                    "Semesterdaten sind nötig, um Prüfungen Wochen zuzuordnen. Die Prüfungsliste zeigt weiterhin erfasste Fristen.",
                    "Se necesitan las fechas del semestre para situar evaluaciones en semanas. La lista sigue mostrando los plazos registrados.") else
                    copy.t("No assessments recorded for this week", "Keine Prüfungen oder Fristen für diese Woche erfasst", "No hay evaluaciones registradas para esta semana"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                assessments.forEach { assessment ->
                    OutlinedCard(onClick = { onAssessment(assessment) }, modifier = Modifier.fillMaxWidth().testTag("semester.assessment.row.${assessment.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(assessment.title, style = MaterialTheme.typography.titleSmall)
                            Text(assessmentKindLabel(assessment.kind, copy) + " · " + assessmentState(assessment, copy), style = MaterialTheme.typography.bodySmall)
                            Text(copy.displayDate(assessment.dueDate) + (assessment.dueTime?.let { " · " + copy.displayTime(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                            if (SemesterBoardProjection.clockNeedsReview(assessment, semester)) Text(copy.t("Source clock needs review", "Uhrzeit der Quelle prüfen", "Revisa la hora de origen"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(onClick = onPrepare, enabled = writesEnabled && !recoveryRequired, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag("semester.week.planReview")) { Text(copy.t("Plan review", "Vorbereitung planen", "Planificar preparación")) }
                Text(copy.t("Review a separate Planner task. Completing it does not change this week’s status or an assessment.",
                    "Prüfe eine separate Planer-Aufgabe. Ihr Abschluss ändert weder den Wochenstatus noch eine Prüfung.",
                    "Revisa una tarea separada en el planificador. Completarla no cambia el estado semanal ni una evaluación."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }, confirmButton = { TextButton(onClick = onDismiss, colors = actionColors, modifier = Modifier.testTag("semester.week.close")) { Text(copy.close) } })
}

@Composable
private fun WeekStatusChoice(id: String?, name: String, color: Color?, selectedID: String?, enabled: Boolean, tag: String, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = id == selectedID, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(id) })
        .testTag(tag).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = id == selectedID, onClick = null, enabled = enabled)
        if (color != null) StatusDot(color, 12.dp) else EmptyStatusDot()
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private data class BoardCourseRow(val course: AcademicCourse, val weeks: List<BoardWeekCell>)
private data class BoardWeekCell(val week: Int, val entry: AcademicWeekEntry?, val active: Boolean?, val assessments: List<AcademicAssessment>)
private data class WeekBaseline(val entry: AcademicWeekEntry?, val persistedStatuses: List<AcademicStatus>, val options: List<AcademicStatus>, val generated: Boolean, val builtIns: List<AcademicStatus>)
private data class PendingStatusChoice(val id: String?, val baseline: WeekBaseline) {
    fun matches(entry: AcademicWeekEntry?, definitions: List<AcademicStatus>): Boolean {
        val expected = (baseline.entry ?: AcademicWeekEntry()).copy(statusID = id)
        val sameDefinitions = definitions == baseline.persistedStatuses || (baseline.generated && definitions == baseline.options)
        return sameDefinitions && (entry == expected || (id == null && entry == null && expected.notes.isEmpty() && expected.compatibilityFields.isEmpty()))
    }
}
private fun captureWeekBaseline(semester: AcademicSemester?, course: AcademicCourse?, week: Int) = WeekBaseline(
    course?.entries?.get(week), semester?.statuses?.toList().orEmpty(), semester?.let(SemesterBoardProjection::statuses).orEmpty(), semester?.statuses?.isEmpty() == true,
    semester?.let { SemesterBoardProjection.statuses(it.copy(statuses = emptyList())) }.orEmpty())

private fun statusLabel(status: AcademicStatus, builtIns: List<AcademicStatus>, copy: UiCopy): String = if (builtIns.none { it.id.equals(status.id, ignoreCase = true) && it.name == status.name }) status.name else when (status.name) {
    "To do" -> copy.t("To do", "Offen", "Por hacer")
    "Done" -> copy.t("Done", "Erledigt", "Hecho")
    "Revisit" -> copy.t("Revisit", "Nochmals ansehen", "Repasar")
    "Work in progress" -> copy.t("Work in progress", "In Arbeit", "En curso")
    else -> status.name
}
private fun weekLabel(week: Int, copy: UiCopy) = copy.t("Week ", "Woche ", "Semana ") + week
private fun noStatus(copy: UiCopy) = copy.t("No status recorded", "Kein Status erfasst", "Sin estado registrado")
private fun scheduleLabel(active: Boolean?, copy: UiCopy) = when (active) {
    true -> copy.t("Active course week", "Aktive Kurswoche", "Semana activa del curso")
    false -> copy.t("Not marked as an active course week", "Nicht als aktive Kurswoche markiert", "No está marcada como semana activa del curso")
    null -> copy.t("Course schedule not recorded", "Kurswochen nicht erfasst", "No hay calendario del curso registrado")
}
private fun assessmentState(assessment: AcademicAssessment, copy: UiCopy) = when (assessment.recordedState) {
    "completed" -> copy.t("Completed", "Erledigt", "Completado")
    "dismissed" -> copy.t("Dismissed", "Verworfen", "Descartado")
    else -> copy.t("Pending", "Offen", "Pendiente")
}
private fun markerSummary(assessments: List<AcademicAssessment>, copy: UiCopy): String = assessments.groupBy { (it.kind == "exam") to it.recordedState }.entries.joinToString("") { (kind, group) ->
    ", " + (if (kind.first) copy.quantity(group.size, "exam", "exams", "Prüfung", "Prüfungen", "examen", "exámenes") else
        copy.quantity(group.size, "other assessment", "other assessments", "andere Prüfung oder Frist", "andere Prüfungen und Fristen", "otra evaluación", "otras evaluaciones")) + ", " + assessmentState(group.first(), copy)
}
private fun shortWeekDate(value: String, copy: UiCopy) = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM", copy.locale)) }.getOrDefault(value)
private fun statusColor(hex: String, fallback: Color) = runCatching { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }.getOrDefault(fallback)

@Composable private fun weekHighlight(current: Boolean) = if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent
@Composable private fun examMarkerColor() = if (MaterialTheme.colorScheme.background.luminance() < 0.4f) Color(0xFFBFA0FF) else Color(0xFF7640B8)
@Composable private fun otherMarkerColor() = if (MaterialTheme.colorScheme.background.luminance() < 0.4f) Color(0xFF87AAFF) else Color(0xFF2448CF)

@Composable
private fun StatusDot(color: Color, size: Dp) {
    Box(Modifier.size(size).background(color, CircleShape).border(0.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape).clearAndSetSemantics {})
}
@Composable
private fun EmptyStatusDot() {
    val color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(12.dp).clearAndSetSemantics {}) { drawCircle(color, radius = 4.dp.toPx(), style = Stroke(1.dp.toPx())) }
}
@Composable
private fun AssessmentMarker(exam: Boolean) {
    val color = if (exam) examMarkerColor() else otherMarkerColor()
    Canvas(Modifier.size(12.dp).clearAndSetSemantics {}) {
        if (exam) drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) else {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2
            val diamond = Path().apply { moveTo(center.x, center.y - radius); lineTo(center.x + radius, center.y); lineTo(center.x, center.y + radius); lineTo(center.x - radius, center.y); close() }
            drawPath(diamond, color)
        }
    }
}
@Composable
private fun BoardEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth().padding(bottom = 14.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
