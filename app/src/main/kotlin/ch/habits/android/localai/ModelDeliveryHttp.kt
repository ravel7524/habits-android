package ch.habits.android.localai

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal interface ModelDeliveryCall {
    fun execute(): ModelDeliveryResponse
    fun cancel()
}
internal fun interface ModelDeliveryTransport { fun newCall(offset: Long, etag: String?): ModelDeliveryCall }
internal class ModelDeliveryResponse(
    val code: Int,
    val length: Long,
    val etag: String?,
    val contentRange: String?,
    val stream: InputStream,
    private val closeResponse: () -> Unit = {},
) : Closeable {
    override fun close() { try { stream.close() } finally { closeResponse() } }
}

/** The production entry point has one immutable author URL and a narrow HTTPS CDN allowlist. */
internal class ModelDeliveryHttps(private val descriptor: ModelDescriptor) : ModelDeliveryTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES).build()
    private val hosts = setOf("huggingface.co", "hf.co", "us.aws.cdn.hf.co", "eu.aws.cdn.hf.co",
        "cdn-lfs.huggingface.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cdn-lfs-eu-1.hf.co", "cas-bridge.xethub.hf.co")
    override fun newCall(offset: Long, etag: String?): ModelDeliveryCall = object : ModelDeliveryCall {
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<Call?>()
        override fun cancel() { cancelled.set(true); active.get()?.cancel() }
        override fun execute(): ModelDeliveryResponse {
            var url = descriptor.url.toHttpUrl()
            for (hop in 0..5) {
                if (cancelled.get()) throw ModelDeliveryCancelled()
                require(url.isHttps && url.host in hosts && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
                    "The official model download redirected to an unsupported host."
                }
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
                    if (offset > 0 && etag != null) { header("Range", "bytes=$offset-"); header("If-Range", etag) }
                }.build()
                val call = client.newCall(request)
                active.set(call)
                if (cancelled.get()) { call.cancel(); throw ModelDeliveryCancelled() }
                val response = call.execute()
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                    response.close()
                    url = location?.let { url.resolve(it) } ?: throw IOException("The official model redirect is invalid.")
                    continue
                }
                val body = response.body ?: run { response.close(); throw IOException("The official model response is empty.") }
                return ModelDeliveryResponse(response.code, body.contentLength(), response.header("ETag"), response.header("Content-Range"), body.byteStream()) { response.close() }
            }
            throw IOException("The official model has too many redirects.")
        }
    }
}
internal class ModelDeliveryCancelled : IOException("Model download cancelled.")
