package ch.habits.android.localai

import ch.habits.android.core.AcademicPlanCodec
import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ReportSelectionException : IllegalArgumentException("The on-device selection was invalid. Recorded facts remain unchanged.")

/** Callers compare the captured source fingerprint with a fresh build before displaying late output. */
object ReportSelectionCodec {
    const val MAX_SELECTED = 8
    const val MAX_OUTPUT_BYTES = 2048

    fun parse(json: String, facts: ReportFactSet): List<ReportFact> {
        try {
            val root = AcademicPlanCodec.parseStrictJson(json, MAX_OUTPUT_BYTES) as? JsonObject ?: throw ReportSelectionException()
            if (root.keys != setOf("selected")) throw ReportSelectionException()
            val values = root["selected"] as? JsonArray ?: throw ReportSelectionException()
            if (values.size > MAX_SELECTED || (facts.facts.isNotEmpty() && values.isEmpty())) throw ReportSelectionException()
            val catalog = facts.facts.associateBy { it.id }
            if (catalog.size != facts.facts.size) throw ReportSelectionException()
            val selected = mutableListOf<ReportFact>()
            val ids = mutableSetOf<String>()
            values.forEach { element ->
                val id = element as? JsonPrimitive ?: throw ReportSelectionException()
                if (id is JsonNull || !id.isString || !ids.add(id.content)) throw ReportSelectionException()
                selected += catalog[id.content] ?: throw ReportSelectionException()
            }
            return Collections.unmodifiableList(selected)
        } catch (_: Exception) { throw ReportSelectionException() }
    }

    /** Render only codec-selected original text; no model-authored factual prose is accepted. */
    fun render(selection: List<ReportFact>): String = selection.joinToString("\n") { "• ${it.text}" }
}
