package ch.habits.android.localai

import ch.habits.android.data.WorkTask
import ch.habits.android.data.WorkspaceSnapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReportSelectionCodecTest {
    private fun facts(count: Int = 2): ReportFactSet = ReportFacts.build("morning", WorkspaceSnapshot(tasks = (0 until count).map { index ->
        WorkTask(id = "task-$index", title = "Recorded title $index", dueDate = "2026-10-08", sourceZone = "UTC",
            createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")
    }), null, LocalDate.parse("2026-10-08"), ZoneId.of("UTC"), "en")

    @Test fun validRankingReturnsOriginalFactObjectsAndOnlyTheirText() {
        val set = facts()
        val selected = ReportSelectionCodec.parse("{\"selected\":[\"f1\",\"f0\"]}", set)
        assertSame(set.facts[1], selected[0])
        assertSame(set.facts[0], selected[1])
        assertEquals("• ${set.facts[1].text}\n• ${set.facts[0].text}", ReportSelectionCodec.render(selected))
    }

    @Test fun missingUnknownDuplicateKeysAndMixedTypesFailClosed() {
        val set = facts()
        val invalid = listOf("{}", "[]", "{\"selected\":null}", "{\"selected\":\"f0\"}",
            "{\"selected\":[0]}", "{\"selected\":[true]}", "{\"selected\":[null]}", "{\"selected\":[{}]}",
            "{\"selected\":[[\"f0\"]]}", "{\"selected\":[\"f99\"]}", "{\"selected\":[\"F0\"]}",
            "{\"selected\":[\"f0\",\"f0\"]}", "{\"selected\":[\"f0\"],\"selected\":[\"f1\"]}",
            "{\"selected\":[\"f0\"],\"notes\":\"Model-authored claims\"}", "{\"version\":1,\"selected\":[\"f0\"]}",
            "{\"selected\":[\"f0\"],\"fingerprint\":\"pretend\"}", "{\"selected\":[\"f0\"]} trailing",
            "```json\n{\"selected\":[\"f0\"]}\n```", "{\"selected\":[\"f0\"]} You studied for an hour.")
        invalid.forEach { response -> assertFailsWith<ReportSelectionException>(response) { ReportSelectionCodec.parse(response, set) } }
    }

    @Test fun invalidUnicodeAndUnicodeLookalikeIdsAreRejected() {
        val set = facts()
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[\"\\uD800\"]}", set) }
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[\"f０\"]}", set) }
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[\"f0\"]}\u0000", set) }
    }

    @Test fun equivalentEscapedIdCannotBypassDuplicateGuard() {
        assertFailsWith<ReportSelectionException> {
            ReportSelectionCodec.parse("{\"selected\":[\"f0\",\"\\u0066\\u0030\"]}", facts())
        }
    }

    @Test fun exactlyEightIdsAllowedAndNineRejectedWithoutTrimming() {
        val set = facts(12)
        val eight = (0..7).joinToString(",") { "\"f$it\"" }
        assertEquals(8, ReportSelectionCodec.parse("{\"selected\":[$eight]}", set).size)
        val nine = (0..8).joinToString(",") { "\"f$it\"" }
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[$nine]}", set) }
    }

    @Test fun emptySelectionRequiresEmptyCatalog() {
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[]}", facts()) }
        assertTrue(ReportSelectionCodec.parse("{\"selected\":[]}", facts(0)).isEmpty())
        assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse("{\"selected\":[\"f0\"]}", facts(0)) }
    }

    @Test fun oversizedOutputRejectedBeforeParsingPrivateProse() {
        val output = " ".repeat(ReportSelectionCodec.MAX_OUTPUT_BYTES + 1) + "{\"selected\":[\"f0\"]}"
        val failure = assertFailsWith<ReportSelectionException> { ReportSelectionCodec.parse(output, facts()) }
        assertFalse(failure.message.orEmpty().contains(output))
    }

    @Test fun grammarContainsOnlyCatalogIdTerminalsAndBoundedItemCount() {
        val set = facts(3)
        set.facts.forEach { fact -> assertTrue(set.grammar.contains("\"\\\"${fact.id}\\\"\"")) }
        assertFalse(set.grammar.contains("\"\\\"f99\\\"\""))
        assertFalse(set.grammar.contains("Recorded title"))
        assertFalse(set.grammar.contains("string ::="))
        assertTrue(set.grammar.contains("{0,7}"))
        assertFalse(facts(0).grammar.contains("item ::="))
    }

    @Test fun catalogAndParsedSelectionCannotBeMutatedByConsumer() {
        val set = facts()
        assertFailsWith<UnsupportedOperationException> { (set.facts as MutableList<ReportFact>).clear() }
        val selected = ReportSelectionCodec.parse("{\"selected\":[\"f0\"]}", set)
        assertFailsWith<UnsupportedOperationException> { (selected as MutableList<ReportFact>).clear() }
        assertEquals(2, set.facts.size)
    }
}
