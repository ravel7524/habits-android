@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.habits.android.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/** Material's selected millis are UTC midnight, representing a civil day, not a local instant. */
private fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun pickerDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

@Composable
internal fun DateField(value: String?, onChange: (String?) -> Unit, copy: UiCopy, label: String,
    tag: String, optional: Boolean = false, enabled: Boolean = true) {
    var showing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); showing = true }, enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag)) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(if (value.isNullOrBlank()) copy.t("Choose date", "Datum wählen", "Elegir fecha") else copy.displayDate(value),
                modifier = Modifier.weight(1f))
        }
        if (optional && !value.isNullOrBlank()) TextButton(onClick = { onChange(null) }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.testTag("$tag.remove")) { Text(copy.t("Remove date", "Datum entfernen", "Quitar fecha")) }
    }
    if (showing) {
        val initial = remember(value) { value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
        val state = remember(initial, copy.locale) { DatePickerState(locale = copy.locale,
            initialSelectedDateMillis = initial?.let(::pickerMillis), yearRange = 1..9999) }
        DatePickerDialog(onDismissRequest = { showing = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChange(pickerDate(it)); showing = false } },
                enabled = state.selectedDateMillis != null, modifier = Modifier.testTag("$tag.confirm")) {
                Text(copy.t("Use date", "Datum verwenden", "Usar fecha"))
            } }, dismissButton = { TextButton(onClick = { showing = false }, modifier = Modifier.testTag("$tag.cancel")) { Text(copy.cancel) } },
            modifier = Modifier.testTag("$tag.dialog")) {
            DatePicker(state = state, title = {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val todayMillis = pickerMillis(LocalDate.now())
                        state.selectedDateMillis = todayMillis
                        state.displayedMonthMillis = todayMillis
                    },
                        modifier = Modifier.testTag("$tag.today")) {
                        Text(copy.t("Today", "Heute", "Hoy"))
                    }
                }
            })
        }
    }
}

@Composable
internal fun TimeField(value: String?, onChange: (String?) -> Unit, copy: UiCopy, label: String,
    tag: String, optional: Boolean = false, enabled: Boolean = true) {
    var showing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); showing = true }, enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag)) {
            Icon(Icons.Outlined.Schedule, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(if (value.isNullOrBlank()) copy.t("Add time", "Zeit hinzufügen", "Añadir hora") else copy.displayTime(value),
                modifier = Modifier.weight(1f))
        }
        if (optional && !value.isNullOrBlank()) TextButton(onClick = { onChange(null) }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.testTag("$tag.remove")) { Text(copy.t("Remove time", "Zeit entfernen", "Quitar hora")) }
    }
    if (showing) {
        val initial = remember(value) { value?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.now() }
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute,
            is24Hour = DateFormat.is24HourFormat(context))
        val maxHeight = (LocalConfiguration.current.screenHeightDp - 80).coerceAtLeast(280).dp
        Dialog(onDismissRequest = { showing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).widthIn(max = 560.dp)
                    .heightIn(max = maxHeight).testTag("$tag.dialog")) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { TimePicker(state = state) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showing = false }, modifier = Modifier.testTag("$tag.cancel")) { Text(copy.cancel) }
                        TextButton(onClick = {
                            onChange(String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute))
                            showing = false
                        }, modifier = Modifier.testTag("$tag.confirm")) { Text(copy.t("Use time", "Zeit verwenden", "Usar hora")) }
                    }
                }
            }
        }
    }
}
