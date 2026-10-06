package ch.habits.android.localai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real loopback HTTP and private synthetic storage; no production URL or weights are used. */
@RunWith(AndroidJUnit4::class)
class ModelStoreTest {
    private lateinit var directory: File
    private lateinit var server: ModelDeliveryFixtureServer
    private val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }.apply { "GGUF".toByteArray().copyInto(this) }
    @Before fun setup() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "model-tests-${UUID.randomUUID()}").apply { mkdirs() }
        server = ModelDeliveryFixtureServer()
    }
    @After fun cleanup() { server.close(); directory.deleteRecursively() }
    private fun model(data: ByteArray = bytes) = ModelDescriptor("synthetic-model", "Synthetic fixture", "http://127.0.0.1:${server.port}/model", "fixture.gguf", data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }, "https://example.invalid/LICENSE")
    private fun store(descriptor: ModelDescriptor = model(), space: Long = Long.MAX_VALUE) = ModelStore(directory, descriptor, server.transport(), { space }, reserveBytes = 0)

    @Test fun actualHttpDownloadVerifiesAtomicallyAndColdStartRechecksTheFile() = runBlocking {
        server.reply(bytes)
        val store = store()
        assertNull(store.verifiedModelPath())
        assertEquals("Startup verification must not download", 0, server.requests.size)
        store.download()
        assertEquals(ModelDeliveryStatus.READY, store.state.value.status)
        val path = store.verifiedModelPath()!!
        assertArrayEquals(bytes, File(path).readBytes())
        assertFalse(File(directory, "fixture.gguf.part").exists())
        val restarted = store()
        assertEquals(path, restarted.verifiedModelPath())
        val damaged = bytes.clone().apply { this[lastIndex] = 99 }
        File(path).writeBytes(damaged)
        assertNull(restarted.verifiedModelPath())
        assertEquals(ModelDeliveryStatus.ERROR, restarted.state.value.status)
        assertArrayEquals(damaged, File(path).readBytes())
    }
    @Test fun unknownLengthIsOnlyReadyAfterExactBytesAndChecksum() = runBlocking {
        server.reply(bytes, length = null)
        val store = store()
        store.download()
        assertEquals(ModelDeliveryStatus.READY, store.state.value.status)
        store.remove()
        server.reply(bytes.copyOf(bytes.size - 1), length = null)
        store.download()
        assertEquals(ModelDeliveryStatus.ERROR, store.state.value.status)
        assertNull(store.verifiedModelPath())
    }
    @Test fun checksumOversizeAndHttpErrorsPreserveAnOlderInstalledFile() = runBlocking {
        val old = File(directory, "fixture.gguf").apply { writeBytes(bytes) }
        val wanted = bytes.clone().apply { this[20] = 77 }
        val next = store(model(wanted))
        server.reply(bytes) // Same size/magic, wrong current descriptor checksum.
        next.download()
        assertEquals(ModelDeliveryStatus.ERROR, next.state.value.status)
        assertArrayEquals(bytes, old.readBytes())
        server.reply(wanted + byteArrayOf(1), length = null)
        next.download()
        assertEquals(ModelDeliveryStatus.ERROR, next.state.value.status)
        assertArrayEquals(bytes, old.readBytes())
        server.reply(ByteArray(0), code = 503)
        next.download()
        assertEquals(ModelDeliveryStatus.ERROR, next.state.value.status)
        assertTrue(next.state.value.error!!.contains("503"))
        assertArrayEquals(bytes, old.readBytes())
    }
    @Test fun cancelClosesBlockingSocketAndRestartUsesConfirmedRange() = runBlocking {
        val release = CountDownLatch(1)
        server.enqueue { _, output ->
            server.headers(output, 200, bytes.size.toLong(), "\"fixture\"", null)
            output.write(bytes, 0, 8192); output.flush()
            release.await(10, TimeUnit.SECONDS)
        }
        val first = store()
        val downloading = async(Dispatchers.IO) { first.download() }
        withTimeout(5_000) { first.state.first { it.status == ModelDeliveryStatus.DOWNLOADING && it.received >= 8192 } }
        withTimeout(5_000) { first.cancel() }
        downloading.await(); release.countDown()
        assertFalse(first.state.value.status == ModelDeliveryStatus.READY)
        val offset = File(directory, "fixture.gguf.part").length()
        assertTrue(offset in 1 until bytes.size.toLong())
        server.reply(bytes.copyOfRange(offset.toInt(), bytes.size), code = 206, range = "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        val restarted = store()
        restarted.download()
        assertEquals(ModelDeliveryStatus.READY, restarted.state.value.status)
        assertEquals("bytes=$offset-", server.requests.last()["range"])
        assertEquals("\"fixture\"", server.requests.last()["if-range"])
        assertArrayEquals(bytes, File(restarted.verifiedModelPath()!!).readBytes())
    }
    @Test fun ignoredRangeRestartsSafelyAndRemoveLeavesUnrelatedFiles() = runBlocking {
        server.reply(bytes.copyOfRange(0, 8192), length = bytes.size.toLong())
        val first = store()
        first.download() // Premature socket EOF retains a resumable partial.
        assertEquals(ModelDeliveryStatus.ERROR, first.state.value.status)
        val other = File(directory, "user-data.txt").apply { writeText("Retain this unrelated file") }
        server.reply(bytes) // Full HTTP 200 intentionally ignores Range.
        val restarted = store()
        restarted.download()
        assertEquals(ModelDeliveryStatus.READY, restarted.state.value.status)
        restarted.cancel()
        assertEquals(ModelDeliveryStatus.READY, restarted.state.value.status)
        restarted.remove()
        assertEquals(ModelDeliveryStatus.MISSING, restarted.state.value.status)
        assertEquals("Retain this unrelated file", other.readText())
    }
    @Test fun storageReserveAndUnexpectedRangeCannotPublishReady() = runBlocking {
        val lowSpace = store(space = 0)
        lowSpace.download()
        assertEquals(ModelDeliveryStatus.ERROR, lowSpace.state.value.status)
        assertEquals(0, server.requests.size)
        server.reply(bytes.copyOfRange(0, 8192), length = bytes.size.toLong())
        val first = store()
        first.download()
        val offset = File(directory, "fixture.gguf.part").length()
        server.reply(bytes.copyOfRange(offset.toInt(), bytes.size), code = 206, range = "bytes 0-${bytes.lastIndex}/${bytes.size}")
        first.download()
        assertEquals(ModelDeliveryStatus.ERROR, first.state.value.status)
        assertFalse(File(directory, "fixture.gguf").exists())
    }
}

/** Small raw-socket HTTP seam: production still uses its pinned-author HTTPS allowlist. */
private class ModelDeliveryFixtureServer : java.io.Closeable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    private val closed = AtomicBoolean()
    private val replies = LinkedBlockingQueue<(Map<String, String>, java.io.OutputStream) -> Unit>()
    private val thread = Thread {
        while (!closed.get()) {
            try {
                listener.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    line(input)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val value = line(input); if (value.isEmpty()) break
                        val split = value.indexOf(':'); if (split > 0) headers[value.substring(0, split).lowercase()] = value.substring(split + 1).trim()
                    }
                    requests += headers
                    val reply = replies.poll(10, TimeUnit.SECONDS) ?: error("No synthetic HTTP reply queued")
                    reply(headers, socket.getOutputStream())
                }
            } catch (_: Exception) { if (closed.get()) break }
        }
    }.apply { isDaemon = true; start() }
    fun enqueue(reply: (Map<String, String>, java.io.OutputStream) -> Unit) { replies.put(reply) }
    fun headers(output: java.io.OutputStream, code: Int, length: Long?, etag: String?, range: String?) {
        val text = buildString {
            append("HTTP/1.1 $code Synthetic\r\nConnection: close\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            if (etag != null) append("ETag: $etag\r\n")
            if (range != null) append("Content-Range: $range\r\n")
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.US_ASCII)); output.flush()
    }
    fun reply(body: ByteArray, code: Int = 200, length: Long? = body.size.toLong(), range: String? = null) = enqueue { _, output ->
        headers(output, code, length, "\"fixture\"", range); output.write(body); output.flush()
    }
    fun transport(): ModelDeliveryTransport = ModelDeliveryTransport { offset, etag -> object : ModelDeliveryCall {
        private val socket = AtomicReference<Socket?>()
        private val cancelled = AtomicBoolean()
        override fun cancel() { cancelled.set(true); runCatching { socket.get()?.close() } }
        override fun execute(): ModelDeliveryResponse {
            val connection = Socket()
            socket.set(connection)
            if (cancelled.get()) { connection.close(); throw ModelDeliveryCancelled() }
            connection.connect(java.net.InetSocketAddress("127.0.0.1", port), 3_000); connection.soTimeout = 5_000
            val request = buildString {
                append("GET /model HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n")
                if (offset > 0) append("Range: bytes=$offset-\r\nIf-Range: $etag\r\n")
                append("\r\n")
            }
            connection.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val input = BufferedInputStream(connection.getInputStream())
            val status = line(input).split(' ')[1].toInt()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val text = line(input); if (text.isEmpty()) break
                val split = text.indexOf(':'); headers[text.substring(0, split).lowercase()] = text.substring(split + 1).trim()
            }
            return ModelDeliveryResponse(status, headers["content-length"]?.toLong() ?: -1, headers["etag"], headers["content-range"], input) { connection.close() }
        }
    } }
    override fun close() { closed.set(true); listener.close(); thread.interrupt(); thread.join(1_000) }
    private fun line(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read(); if (byte < 0) throw IOException("Synthetic socket EOF")
            if (byte == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(byte); if (out.size() > 8192) throw IOException("Synthetic header too large")
        }
    }
}
