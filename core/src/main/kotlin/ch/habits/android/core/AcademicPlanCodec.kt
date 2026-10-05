package ch.habits.android.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Strict JSON boundary. Unknown or duplicate fields cannot be silently discarded. */
@OptIn(ExperimentalSerializationApi::class)
object AcademicPlanCodec {
    const val MAX_DATA_BYTES: Int = AcademicPlanValidator.MAX_BYTES
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
        encodeDefaults = true
    }
    private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")

    fun decode(text: String): AcademicPlan {
        return try {
            val element = parseStrictJson(text)
            checkShape(element)
            val plan = json.decodeFromJsonElement<AcademicPlan>(element)
            AcademicPlanValidator.validate(plan)
            plan
        } catch (error: PlanValidationException) {
            throw error
        } catch (_: SerializationException) {
            invalidPlan("proposal", "must contain valid JSON with the required field types")
        } catch (_: IllegalArgumentException) {
            invalidPlan("proposal", "must contain valid JSON with the required field types")
        }
    }

    /** Parse wire envelopes without silently collapsing duplicated proposal keys.
     * The caller still validates its envelope shape and any nested academic plan.
     */
    fun parseStrictJson(text: String, maxBytes: Int = MAX_DATA_BYTES): JsonElement {
        boundedUtf8(text, maxBytes)
        return try {
            JsonKeyValidator(text, json).validate()
            json.parseToJsonElement(text)
        } catch (error: PlanValidationException) {
            throw error
        } catch (_: SerializationException) {
            invalidPlan("JSON", "must contain valid JSON")
        } catch (_: IllegalArgumentException) {
            invalidPlan("JSON", "must contain valid JSON")
        }
    }

    fun encode(plan: AcademicPlan): String {
        AcademicPlanValidator.validate(plan)
        val encoded = json.encodeToString(plan)
        boundedUtf8(encoded)
        return encoded
    }

    private fun checkShape(element: JsonElement) {
        val root = obj(element, "proposal", setOf("format", "version", "proposalID", "source", "semester", "courses", "assessments", "preparationTasks"))
        strings(root, "proposal", "format", "proposalID", "source")
        integer(root, "version", "version")
        val semester = obj(root.getValue("semester"), "semester", setOf("id", "title", "startDate", "weekCount", "timeZoneIdentifier"))
        strings(semester, "semester", "id", "title", "startDate", "timeZoneIdentifier")
        integer(semester, "weekCount", "semester.weekCount")
        array(root.getValue("courses"), "courses", AcademicPlanValidator.MAX_COURSES).forEachIndexed { index, value ->
            val path = "courses[$index]"
            val course = obj(value, path, setOf("id", "name"))
            strings(course, path, "id", "name")
        }
        array(root.getValue("assessments"), "assessments", AcademicPlanValidator.MAX_ASSESSMENTS).forEachIndexed { index, value ->
            val path = "assessments[$index]"
            val assessment = obj(value, path, setOf("id", "courseID", "title", "kind", "dueDate", "notes"), setOf("dueTime"))
            strings(assessment, path, "id", "courseID", "title", "kind", "dueDate", "notes")
            optionalString(assessment, "dueTime", "$path.dueTime")
        }
        array(root.getValue("preparationTasks"), "preparationTasks", AcademicPlanValidator.MAX_PREPARATION_TASKS).forEachIndexed { index, value ->
            val path = "preparationTasks[$index]"
            val task = obj(value, path, setOf("id", "assessmentID", "title", "estimatedWorkMinutes", "notes"), setOf("dueDate", "dueTime"))
            strings(task, path, "id", "assessmentID", "title", "notes")
            optionalString(task, "dueDate", "$path.dueDate")
            optionalString(task, "dueTime", "$path.dueTime")
            integer(task, "estimatedWorkMinutes", "$path.estimatedWorkMinutes")
        }
    }

    private fun obj(element: JsonElement, path: String, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
        val value = element as? JsonObject ?: invalidPlan(path, "must be an object")
        checkPlan(value.keys.containsAll(required), path, "missing required fields")
        checkPlan(value.keys.all { it in required || it in optional }, path, "contains unsupported fields")
        return value
    }

    private fun strings(value: JsonObject, path: String, vararg keys: String) {
        keys.forEach { string(value.getValue(it), "$path.$it") }
    }

    private fun string(value: JsonElement, path: String) {
        checkPlan(value is JsonPrimitive && value !is JsonNull && value.isString, path, "must be a string")
    }

    private fun optionalString(value: JsonObject, key: String, path: String) {
        value[key]?.let { string(it, path) } // An explicit JSON null is rejected.
    }

    private fun integer(value: JsonObject, key: String, path: String) {
        val number = value.getValue(key) as? JsonPrimitive ?: invalidPlan(path, "must be an integer")
        checkPlan(!number.isString && integerPattern.matches(number.content) && number.content.toIntOrNull() != null,
            path, "must be an integer")
    }

    private fun array(value: JsonElement, path: String, limit: Int): JsonArray {
        val array = value as? JsonArray ?: invalidPlan(path, "must be an array")
        checkPlan(array.size <= limit, path, "exceeds item limit")
        return array
    }
}

/** kotlinx.serialization's JSON tree collapses duplicate keys; scan before building it. */
private class JsonKeyValidator(private val input: String, private val json: Json) {
    private var index = 0
    fun validate() {
        value(0)
        whitespace()
        checkPlan(index == input.length, "proposal", "invalid JSON")
    }

    private fun value(depth: Int) {
        checkPlan(depth <= 64, "proposal", "JSON nesting exceeds limit")
        whitespace()
        checkPlan(index < input.length, "proposal", "incomplete JSON")
        when (input[index]) {
            '{' -> {
                index += 1
                whitespace()
                if (consume('}')) return
                val keys = mutableSetOf<String>()
                while (true) {
                    whitespace()
                    val key = string()
                    checkPlan(keys.add(key), "proposal", "duplicate JSON object key")
                    whitespace()
                    checkPlan(consume(':'), "proposal", "invalid JSON object")
                    value(depth + 1)
                    whitespace()
                    if (consume('}')) return
                    checkPlan(consume(','), "proposal", "invalid JSON object")
                }
            }
            '[' -> {
                index += 1
                whitespace()
                if (consume(']')) return
                while (true) {
                    value(depth + 1)
                    whitespace()
                    if (consume(']')) return
                    checkPlan(consume(','), "proposal", "invalid JSON array")
                }
            }
            '"' -> string()
            else -> {
                val start = index
                while (index < input.length && input[index] !in "\t\n\r ,]}") index += 1
                checkPlan(index > start, "proposal", "invalid JSON value")
                // The configured JSON parser checks number/literal grammar next.
            }
        }
    }

    private fun string(): String {
        val start = index
        checkPlan(consume('"'), "proposal", "JSON object keys must be quoted strings")
        while (index < input.length) {
            val character = input[index++]
            if (character == '"') {
                val parsed = json.parseToJsonElement(input.substring(start, index)) as? JsonPrimitive
                    ?: invalidPlan("proposal", "invalid JSON string")
                checkPlan(parsed.isString && validUnicode(parsed.content), "proposal", "invalid JSON string")
                return parsed.content
            }
            if (character == '\\') {
                checkPlan(index < input.length, "proposal", "invalid JSON escape")
                index += 1
            } else checkPlan(character.code >= 0x20, "proposal", "unescaped JSON control character")
        }
        invalidPlan("proposal", "unterminated JSON string")
    }

    private fun consume(character: Char): Boolean {
        if (index >= input.length || input[index] != character) return false
        index += 1
        return true
    }

    private fun whitespace() {
        while (index < input.length && input[index] in "\t\n\r ") index += 1
    }
}
