package ch.habits.android.desktop

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class DesktopConnectionTest {
    private fun plan(): ch.habits.android.core.AcademicPlan {
        val json = requireNotNull(javaClass.getResourceAsStream("/assistant/two-course-exams.json")) {
            "The portable academic proposal test resource is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ch.habits.android.core.AcademicPlanCodec.decode(json)
    }
    private fun credential(plan: ch.habits.android.core.AcademicPlan, reported: String? = null) = DesktopCredential(
        baseURL = "https://localhost:8766", certificateSHA256 = "a".repeat(64),
        deviceID = "22222222-2222-4222-8222-222222222222", deviceSecret = "0".repeat(64),
        deliveries = listOf(DesktopDelivery(plan.proposalID, "b".repeat(64), DesktopSigning.planFingerprint(plan), reported)))

    /** Serialized durable bytes survive replacing the connection instance. */
    private class StoredCredential(initial: DesktopCredential) : DesktopCredentialStore {
        var bytes = Json.encodeToString(initial)
        var writes = 0
        var failSave = false
        var cleared = false
        override fun read(): DesktopCredential = Json.decodeFromString(bytes)
        override fun save(value: DesktopCredential) {
            if (failSave) throw java.io.IOException("Synthetic storage failure")
            bytes = Json.encodeToString(value)
            writes += 1
        }
        override fun clear() { cleared = true }
    }

    private fun receipt(body: String, key: DesktopCredential, status: String? = null): JsonObject {
        val request = Json.parseToJsonElement(body).jsonObject
        return JsonObject(mapOf("proposalID" to request.getValue("proposalID"),
            "proposalDigest" to request.getValue("proposalDigest"), "targetDeviceID" to JsonPrimitive(key.deviceID),
            "status" to (status?.let(::JsonPrimitive) ?: request.getValue("state"))))
    }
    private fun invite(url: String = "https://192.168.1.2:8766", extra: String = ""): String = """{"format":"habits.desktop-pairing","version":1,"baseURL":"$url","certificateSHA256":"${"a".repeat(64)}","pairingCode":"${"b".repeat(32)}"$extra}"""

    @Test fun acceptsExplicitPrivateInvitation() {
        assertEquals("https://192.168.1.2:8766", DesktopInvite.decode(invite()).baseURL)
        assertEquals("https://localhost:8766", DesktopInvite.decode(invite("https://localhost:8766")).baseURL)
        assertEquals("https://[fd12::1]:8766", DesktopInvite.decode(invite("https://[fd12::1]:8766")).baseURL)
        assertFails { DesktopInvite.decode(invite("https://[fe80::1]:8766")) }
        assertFails { DesktopInvite.decode(invite("https://[2001:4860::1]:8766")) }
    }
    @Test fun rejectsPublicInsecureOrAmbiguousAddresses() {
        for (url in listOf("http://192.168.1.2:8766", "https://example.com:8766", "https://192.168.foo.1.2:8766", "https://192.168.001.2:8766", "https://user@192.168.1.2:8766", "https://192.168.1.2:8766/path")) {
            assertFails { DesktopInvite.decode(invite(url)) }
        }
    }
    @Test fun rejectsDuplicateUnsupportedAndStringVersionFields() {
        assertFails { DesktopInvite.decode(invite(extra = ",\"baseURL\":\"https://localhost:8766\"")) }
        assertFails { DesktopInvite.decode(invite(extra = ",\"unknown\":true")) }
        assertFails { DesktopInvite.decode(invite().replace("\"version\":1", "\"version\":\"1\"")) }
    }
    @Test fun matchesIndependentPythonHmacVector() {
        val actual = DesktopSigning.signature("00".repeat(32), "POST", "/v1/assistant/receipt", "1700000000", "00000000-0000-0000-0000-000000000001", "{\"state\":\"applied\"}".toByteArray())
        assertEquals("171d83cd9636b8d9b0f6fb90fc3c407ae88d4c91b69b4d115ac61c4a65373f09", actual)
        assertNotEquals(actual, DesktopSigning.signature("00".repeat(32), "POST", "/v1/assistant/receipt", "1700000000", "00000000-0000-0000-0000-000000000001", "{\"state\":\"undone\"}".toByteArray()))
    }
    @Test fun initializationNeverStartsRequestsAndDamagedSettingsArePreserved() {
        val store = object : DesktopCredentialStore {
            var read = 0; var writes = 0
            override fun read(): DesktopCredential? { read++; throw IllegalStateException("damaged") }
            override fun save(value: DesktopCredential) { writes++ }
            override fun clear() { writes++ }
        }
        val connection = DesktopConnection(store)
        assertFalse(connection.state.value.paired)
        assertNotNull(connection.state.value.error)
        assertEquals(1, store.read)
        assertEquals(0, store.writes)
    }
    @Test fun localDisconnectNeedsNoNetworkWhenUnpaired() = runTest {
        val store = object : DesktopCredentialStore {
            var cleared = false
            override fun read(): DesktopCredential? = null
            override fun save(value: DesktopCredential) = error("unexpected")
            override fun clear() { cleared = true }
        }
        val connection = DesktopConnection(store)
        connection.disconnect()
        assertFalse(connection.state.value.paired)
        assertTrue(store.cleared)
    }
    @Test fun rememberedReceiptSurvivesConnectionReloadAndCannotAckDifferentBody() {
        val plan = plan()
        var saved = DesktopCredential(baseURL = "https://localhost:8766", certificateSHA256 = "a".repeat(64),
            deviceID = "22222222-2222-4222-8222-222222222222", deviceSecret = "0".repeat(64),
            deliveries = listOf(DesktopDelivery(plan.proposalID, "b".repeat(64), DesktopSigning.planFingerprint(plan))))
        val store = object : DesktopCredentialStore {
            override fun read() = saved
            override fun save(value: DesktopCredential) { saved = value }
            override fun clear() {}
        }
        val first = DesktopConnection(store)
        assertNotNull(first.rememberedProposal(plan))
        val restarted = DesktopConnection(store)
        assertEquals("b".repeat(64), restarted.rememberedProposal(plan)?.proposalDigest)
        assertNull(restarted.rememberedProposal(plan.copy(source = "Different accepted source")))
        assertTrue(restarted.state.value.proposals.isEmpty(), "Receipt metadata does not depend on the pending inbox")
    }

    @Test fun successfulStateIsPersistedOnceAndOnlyUndoNeedsAnotherReportAfterRestart() = runTest {
        val plan = plan()
        val store = StoredCredential(credential(plan))
        var requests = 0
        val transport: (String, String, String, String, String, DesktopCredential?) -> JsonObject = { _, _, _, path, body, key ->
            assertEquals("/v1/assistant/receipt", path)
            requests += 1
            receipt(body, key!!)
        }
        val connection = DesktopConnection(store, transport)
        assertFalse(store.bytes.contains("reportedState"), "Old encrypted settings omit the new optional state")
        assertTrue(connection.needsAcknowledgement(plan, "applied"))
        connection.acknowledge(connection.rememberedProposal(plan)!!, "applied")
        assertEquals("applied", store.read().deliveries.single().reportedState)
        connection.acknowledge(connection.rememberedProposal(plan)!!, "applied")
        assertEquals(1, requests, "Repeating an already successful receipt performs no network work")
        assertEquals(1, store.writes)
        val restarted = DesktopConnection(store, transport)
        assertFalse(restarted.needsAcknowledgement(plan, "applied"))
        assertTrue(restarted.needsAcknowledgement(plan, "undone"))
        restarted.acknowledge(restarted.rememberedProposal(plan)!!, "undone")
        assertEquals(2, requests)
        assertEquals("undone", store.read().deliveries.single().reportedState)
        val restored = DesktopConnection(store, transport)
        assertFalse(restored.needsAcknowledgement(plan, "applied"), "An older restored receipt cannot reverse terminal Undo")
        assertFalse(restored.needsAcknowledgement(plan, "undone"))
        assertFalse(restored.needsAcknowledgement(plan, "rejected"))
        assertNull(restored.rememberedProposal(plan.copy(source = "Changed body")))
        assertFailsWith<IllegalArgumentException> {
            restored.acknowledge(DesktopProposal(plan.copy(source = "Changed body"), "b".repeat(64), "pending"), "applied")
        }
        assertEquals(2, requests, "Changed content is rejected before transport")
    }

    @Test fun refreshPreservesAcknowledgedStateAndRejectsChangedImmutableEnvelope() = runTest {
        val plan = plan()
        val store = StoredCredential(credential(plan, "applied"))
        var returnedPlan = plan
        val connection = DesktopConnection(store) { _, _, _, path, _, _ ->
            assertEquals("/v1/assistant/proposals", path)
            JsonObject(mapOf("proposals" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                "proposal" to Json.parseToJsonElement(ch.habits.android.core.AcademicPlanCodec.encode(returnedPlan)),
                "proposalDigest" to JsonPrimitive("b".repeat(64)), "status" to JsonPrimitive("pending")))))))
        }
        connection.refresh()
        assertEquals("applied", store.read().deliveries.single().reportedState)
        assertFalse(connection.needsAcknowledgement(plan, "applied"))
        assertTrue(connection.needsAcknowledgement(plan, "undone"))
        val before = store.bytes
        returnedPlan = plan.copy(source = "Different immutable content")
        assertFailsWith<IllegalArgumentException> { connection.refresh() }
        assertEquals(before, store.bytes)
        assertEquals("applied", connection.state.value.deliveries.single().reportedState)
    }

    @Test fun failedRequestLeavesTransitionPendingAndLocalDisconnectAvailable() = runTest {
        val plan = plan()
        val store = StoredCredential(credential(plan))
        var requests = 0
        val connection = DesktopConnection(store) { _, _, _, _, _, _ ->
            requests += 1
            throw java.io.IOException("Synthetic offline desktop")
        }
        assertFailsWith<java.io.IOException> { connection.acknowledge(connection.rememberedProposal(plan)!!, "applied") }
        assertTrue(connection.needsAcknowledgement(plan, "applied"))
        assertNull(store.read().deliveries.single().reportedState)
        assertFalse(connection.state.value.busy, "A stopped batch must leave Disconnect enabled")
        assertEquals(1, requests)
        connection.disconnect()
        assertTrue(store.cleared)
        assertFalse(connection.state.value.paired)
        assertFalse(connection.state.value.busy)
    }

    @Test fun serverTerminalUndoSuppressesStaleAppliedReceiptsAfterRestart() = runTest {
        val plan = plan()
        val store = StoredCredential(credential(plan))
        var requests = 0
        val transport: (String, String, String, String, String, DesktopCredential?) -> JsonObject = { _, _, _, _, body, key ->
            requests += 1
            receipt(body, key!!, status = "undone")
        }
        val connection = DesktopConnection(store, transport)
        connection.acknowledge(connection.rememberedProposal(plan)!!, "applied")
        val restarted = DesktopConnection(store, transport)
        assertEquals("undone", store.read().deliveries.single().reportedState)
        assertFalse(restarted.needsAcknowledgement(plan, "applied"))
        restarted.acknowledge(restarted.rememberedProposal(plan)!!, "applied")
        assertEquals(1, requests)
    }

    @Test fun invalidResponseAndFailedStatePersistenceNeverConsumeTheTransition() = runTest {
        val plan = plan()
        val store = StoredCredential(credential(plan))
        var wrongDevice = true
        var requests = 0
        val connection = DesktopConnection(store) { _, _, _, _, body, key ->
            requests += 1
            val response = receipt(body, key!!).toMutableMap()
            if (wrongDevice) response["targetDeviceID"] = JsonPrimitive("33333333-3333-4333-8333-333333333333")
            JsonObject(response)
        }
        assertFailsWith<IllegalArgumentException> { connection.acknowledge(connection.rememberedProposal(plan)!!, "applied") }
        assertNull(store.read().deliveries.single().reportedState)
        assertTrue(connection.needsAcknowledgement(plan, "applied"))
        wrongDevice = false
        store.failSave = true
        assertFailsWith<java.io.IOException> { connection.acknowledge(connection.rememberedProposal(plan)!!, "applied") }
        assertNull(store.read().deliveries.single().reportedState)
        assertTrue(connection.needsAcknowledgement(plan, "applied"))
        store.failSave = false
        connection.acknowledge(connection.rememberedProposal(plan)!!, "applied")
        assertEquals("applied", store.read().deliveries.single().reportedState)
        assertFalse(connection.needsAcknowledgement(plan, "applied"))
        assertEquals(3, requests, "Failed persistence retries the idempotent server receipt")
    }
}
