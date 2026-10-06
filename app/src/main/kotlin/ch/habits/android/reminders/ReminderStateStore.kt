package ch.habits.android.reminders

import android.util.AtomicFile
import ch.habits.android.core.AcademicPlanCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
internal data class ReminderDeviceState(
    val version: Int = 1,
    val preferences: ReminderPreferences = ReminderPreferences(),
    val scheduled: List<ReminderPlan> = emptyList(),
    val delivered: Map<String, String> = emptyMap(),
    val snoozes: Map<String, String> = emptyMap(),
    val posted: List<ReminderPlan> = emptyList(),
)

/** This cache is device-local and excluded from workspace export and desktop context. */
internal open class ReminderStateStore(path: File) {
    private val file = AtomicFile(path)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    open fun read(): ReminderDeviceState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return ReminderDeviceState()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= AcademicPlanCodec.MAX_DATA_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromJsonElement<ReminderDeviceState>(AcademicPlanCodec.parseStrictJson(text)).also {
            require(it.version == 1 && it.scheduled.size <= 64 && it.posted.size <= 64 && it.delivered.size <= 4096 && it.snoozes.size <= 64)
            it.preferences.validate()
            (it.scheduled + it.posted).forEach { plan -> require(Regex("[0-9a-f]{64}").matches(plan.token)); plan.instant; plan.expiry }
            (it.delivered + it.snoozes).forEach { (token, date) -> require(Regex("[0-9a-f]{64}").matches(token)); Instant.parse(date) }
        }
    }
    open fun save(state: ReminderDeviceState) {
        file.baseFile.parentFile?.mkdirs()
        val bytes = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        require(bytes.size <= AcademicPlanCodec.MAX_DATA_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
