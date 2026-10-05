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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

internal class UiCopy(private val language: String) {
    val locale: Locale get() = Locale.forLanguageTag(when (language.lowercase()) { "german" -> "de"; "spanish" -> "es"; "english" -> "en"; else -> language })
    fun t(english: String, german: String, spanish: String): String = when (language.lowercase()) {
        "de", "german" -> german
        "es", "spanish" -> spanish
        else -> english
    }
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
        background = Color(0xFF202126), onBackground = Color(0xFFF4F0E6),
        surface = Color(0xFF202126), onSurface = Color(0xFFF4F0E6),
        surfaceContainer = Color(0xFF2B2C32), surfaceContainerHigh = Color(0xFF33343A),
        onSurfaceVariant = Color(0xFFBBB9B1), outlineVariant = Color(0xFF484950)
    ) else lightColorScheme(
        primary = Color(0xFF294CC7), onPrimary = Color.White,
        background = Color(0xFFF5F2E9), onBackground = Color(0xFF22231F),
        surface = Color(0xFFF5F2E9), onSurface = Color(0xFF22231F),
        surfaceContainer = Color(0xFFEEEBE2), surfaceContainerHigh = Color(0xFFE8E5DB),
        onSurfaceVariant = Color(0xFF66685F), outlineVariant = Color(0xFFD4D1C6)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

internal val ScreenPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)

@Composable
internal fun SectionHeading(title: String, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
