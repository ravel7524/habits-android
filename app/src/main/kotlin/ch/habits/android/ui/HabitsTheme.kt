package ch.habits.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal class UiCopy(language: String) {
    private val language = if (language == "system") Locale.getDefault().language else language
    val locale: Locale get() = Locale.forLanguageTag(when (language.lowercase()) { "german" -> "de"; "spanish" -> "es"; "english" -> "en"; else -> language })
    fun t(english: String, german: String, spanish: String): String = when (language.lowercase()) {
        "de", "german" -> german
        "es", "spanish" -> spanish
        else -> english
    }
    fun displayDate(value: String?, relative: Boolean = false, zone: String? = null): String {
        if (value.isNullOrBlank()) return noDeadline
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return value
        val today = LocalDate.now(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()))
        if (relative) when (date) {
            today -> return t("Today", "Heute", "Hoy")
            today.plusDays(1) -> return t("Tomorrow", "Morgen", "Mañana")
            today.minusDays(1) -> return t("Yesterday", "Gestern", "Ayer")
        }
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    fun displayTime(value: String?): String = value?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { LocalTime.parse(raw).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) }.getOrDefault(raw)
    }.orEmpty()
    fun zoneDetail(value: String?): String? = value?.takeIf { it.isNotBlank() && it != ZoneId.systemDefault().id }
    fun quantity(count: Int, singularEn: String, pluralEn: String, singularDe: String, pluralDe: String, singularEs: String, pluralEs: String): String =
        "$count " + if (count == 1) t(singularEn, singularDe, singularEs) else t(pluralEn, pluralDe, pluralEs)
    val today get() = t("Today", "Heute", "Hoy")
    val planner get() = t("Planner", "Planer", "Planificador")
    val habits get() = t("Habits", "Gewohnheiten", "Hábitos")
    val settings get() = t("Settings", "Einstellungen", "Ajustes")
    val work get() = t("Work", "Aufgaben", "Tareas")
    val schedule get() = t("Schedule", "Termine", "Agenda")
    val semester get() = t("Semester", "Semester", "Semestre")
    val close get() = t("Close", "Schliessen", "Cerrar")
    val cancel get() = t("Cancel", "Abbrechen", "Cancelar")
    val undo get() = t("Undo", "Rückgängig", "Deshacer")
    val review get() = t("Review", "Prüfen", "Revisar")
    val save get() = t("Confirm and save", "Prüfen und speichern", "Confirmar y guardar")
    val title get() = t("Title", "Titel", "Título")
    val notes get() = t("Notes", "Notizen", "Notas")
    val noDeadline get() = t("No deadline", "Ohne Frist", "Sin fecha límite")
    val unknownTime get() = t("Time not supplied", "Zeit nicht angegeben", "Hora no indicada")
    val archive get() = t("Archive", "Archivieren", "Archivar")
    val localOnly get() = t("Stored on this phone", "Auf diesem Telefon gespeichert", "Guardado en este teléfono")
}

@Composable
internal fun HabitsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFF2D65B), onPrimary = Color(0xFF292820),
        primaryContainer = Color(0xFF3D3925), onPrimaryContainer = Color(0xFFFFE998),
        secondary = Color(0xFFF2D65B), onSecondary = Color(0xFF292820),
        secondaryContainer = Color(0xFF3D3925), onSecondaryContainer = Color(0xFFFFE998),
        tertiary = Color(0xFFD5D2C5), tertiaryContainer = Color(0xFF34352F), onTertiaryContainer = Color(0xFFF0EEDF),
        background = Color(0xFF202126), onBackground = Color(0xFFF4F0E6),
        surface = Color(0xFF202126), onSurface = Color(0xFFF4F0E6),
        surfaceContainerLow = Color(0xFF26272D), surfaceContainer = Color(0xFF2B2C32), surfaceContainerHigh = Color(0xFF33343A),
        surfaceVariant = Color(0xFF303137), onSurfaceVariant = Color(0xFFC6C4BC),
        outline = Color(0xFF8C8D85), outlineVariant = Color(0xFF484950)
    ) else lightColorScheme(
        primary = Color(0xFF294CC7), onPrimary = Color.White,
        primaryContainer = Color(0xFFE3EAFD), onPrimaryContainer = Color(0xFF183690),
        secondary = Color(0xFF294CC7), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE3EAFD), onSecondaryContainer = Color(0xFF183690),
        tertiary = Color(0xFF4D6254), tertiaryContainer = Color(0xFFE1EAE1), onTertiaryContainer = Color(0xFF294732),
        background = Color(0xFFF5F2E9), onBackground = Color(0xFF22231F),
        surface = Color(0xFFF5F2E9), onSurface = Color(0xFF22231F),
        surfaceContainerLow = Color(0xFFFAF8F2), surfaceContainer = Color(0xFFEEEBE2), surfaceContainerHigh = Color(0xFFE8E5DB),
        surfaceVariant = Color(0xFFEEEBE2), onSurfaceVariant = Color(0xFF5A5D55),
        outline = Color(0xFF85887D), outlineVariant = Color(0xFFD4D1C6)
    )
    val baseline = Typography()
    val type = baseline.copy(
        headlineLarge = baseline.headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = baseline.headlineMedium.copy(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = baseline.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = baseline.titleLarge.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = baseline.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = baseline.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = baseline.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = type,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)), content = content)

}

internal val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@Composable
internal fun SectionHeading(title: String, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }
}

@Composable
internal fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
