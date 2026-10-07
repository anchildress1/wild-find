package dev.anchildress1.wildfind.core.download

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ModelDownloaderTest {
    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()
    private val cdn = MockWebServer()
    private val bytes = Random(7).nextBytes(1000)
    private val sha = bytes.toByteString().sha256().hex()
    private val pin = ModelPin("org/model", "abc123", "model.bin", bytes.size.toLong(), sha)
    private val final get() = File(dir, "model.bin")
    private val marker get() = File(dir, "model.bin.sha256")
    private val part get() = File(dir, "model.bin.abc123.part")
    private var free = Long.MAX_VALUE

    @BeforeEach
    fun start() {
        server.start()
        cdn.start()
    }

    @AfterEach
    fun stop() {
        server.close()
        cdn.close()
    }

    private fun downloader(url: String = server.url("/f").toString()) =
        ModelDownloader(OkHttpClient(), pin, dir, { free }, url)

    private fun head(size: Long = pin.bytes, etag: String? = "\"$sha\"") = MockResponse.Builder()
        .code(302)
        .addHeader("Location", cdn.url("/blob"))
        .addHeader("x-linked-size", size)
        .apply { if (etag != null) addHeader("x-linked-etag", etag) }
        .build()

    private fun body(content: ByteArray, code: Int = 200, range: String? = null) = MockResponse.Builder()
        .code(code)
        .body(Buffer().write(content))
        .apply { if (range != null) addHeader("Content-Range", range) }
        .build()

    private fun redirect() = MockResponse.Builder().code(302).addHeader("Location", cdn.url("/blob")).build()

    @Test
    fun `fresh download follows the redirect, verifies, and leaves the file plus its pin marker`() {
        server.enqueue(head())
        server.enqueue(redirect())
        cdn.enqueue(body(bytes))
        val progress = mutableListOf<Long>()

        val file = downloader().download { done, total ->
            assertEquals(pin.bytes, total)
            progress += done
        }

        assertEquals(final, file)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(sha, marker.readText())
        assertFalse(part.exists())
        assertEquals(0L, progress.first())
        assertEquals(pin.bytes, progress.last())
        val preflight = server.takeRequest()
        assertEquals("HEAD", preflight.method)
        assertEquals("/f", preflight.url.encodedPath)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertNull(get.headers["Range"])
        assertEquals("/blob", cdn.takeRequest().url.encodedPath)
    }

    @Test
    fun `ready returns the verified file, or null with nothing downloaded`() {
        assertNull(downloader().ready())

        final.writeBytes(bytes)

        assertEquals(final, downloader().ready())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ready deletes a file that fails the pin and returns null`() {
        final.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        marker.writeText("stale")

        assertNull(downloader().ready())
        assertFalse(final.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun `a file with a matching marker is used without touching the network`() {
        final.writeBytes(bytes)
        marker.writeText(sha)

        assertEquals(final, downloader().download { _, _ -> })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a sideloaded file without a marker is hashed once, then trusted`() {
        final.writeBytes(bytes)

        assertEquals(final, downloader().download { _, _ -> })
        assertEquals(sha, marker.readText())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a full-size file with the wrong bytes is deleted and pulled again`() {
        final.writeBytes(ByteArray(bytes.size))
        marker.writeText("stale")
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        assertEquals(sha, marker.readText())
    }

    @Test
    fun `a marker from another pin forces a re-hash`() {
        final.writeBytes(bytes)
        marker.writeText("old-pin")

        downloader().download { _, _ -> }

        assertEquals(sha, marker.readText())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `preflight stops before pulling when the size differs from the pin`() {
        server.enqueue(head(size = pin.bytes + 1))

        val failure = assertThrows<DownloadFailure.PinMismatch> { downloader().download { _, _ -> } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `preflight stops when the etag differs from the pinned SHA-256`() {
        server.enqueue(head(etag = "\"${"0".repeat(64)}\""))

        assertThrows<DownloadFailure.PinMismatch> { downloader().download { _, _ -> } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing pin headers blame the network, not the pin`() {
        // A captive portal or filtering proxy answers the HEAD itself.
        server.enqueue(MockResponse.Builder().code(200).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }

        assertEquals(server.hostName, failure.host)
    }

    @Test
    fun `a 404 at the pinned URL is a pin mismatch`() {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertThrows<DownloadFailure.PinMismatch> { downloader().download { _, _ -> } }
    }

    @Test
    fun `a weak etag still matches`() {
        server.enqueue(head(etag = "W/\"$sha\""))
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
    }

    @Test
    fun `preflight error status names the start host`() {
        server.enqueue(MockResponse.Builder().code(503).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }

        assertEquals(server.hostName, failure.host)
        assertEquals(503, failure.status)
    }

    @Test
    fun `too little space stops after preflight and reports the bytes still needed`() {
        part.writeBytes(bytes.copyOf(400))
        free = 599
        server.enqueue(head())

        val failure = assertThrows<DownloadFailure.NotEnoughStorage> { downloader().download { _, _ -> } }

        assertEquals(600L, failure.neededBytes)
        assertEquals(1, server.requestCount)
        assertEquals(400L, part.length())
    }

    @Test
    fun `exactly enough space is enough`() {
        free = pin.bytes
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
    }

    @Test
    fun `resume asks for the rest and appends a matching 206`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(body(bytes.copyOfRange(400, bytes.size), code = 206, range = "bytes 400-999/1000"))
        val progress = mutableListOf<Long>()

        val file = downloader().download { done, _ -> progress += done }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(400L, progress.first())
        server.takeRequest()
        assertEquals("bytes=400-", server.takeRequest().headers["Range"])
    }

    @Test
    fun `a late chunk from a stopped run doesn't shift the resumed bytes`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(body(bytes.copyOfRange(400, bytes.size), code = 206, range = "bytes 400-999/1000"))
        var landed = false

        val file = downloader().download { _, _ ->
            // The old run's last chunk lands after this run asked for bytes=400-.
            if (!landed) part.appendBytes(bytes.copyOfRange(400, 500)).also { landed = true }
        }

        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `a 200 that isn't the full file keeps the part`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(body("<html>blocked</html>".toByteArray()))

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }

        assertEquals(200, failure.status)
        assertArrayEquals(bytes.copyOf(400), part.readBytes())
    }

    @Test
    fun `resume restarts from zero when the server answers 200`() {
        part.writeBytes(ByteArray(400))
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
    }

    @Test
    fun `resume restarts without a range when the 206 starts elsewhere`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(body(bytes.copyOfRange(300, bytes.size), code = 206, range = "bytes 300-999/1000"))
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        server.takeRequest()
        assertEquals("bytes=400-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun `an unranged 206 is an error, not a body`() {
        server.enqueue(head())
        server.enqueue(body(bytes, code = 206, range = "bytes 0-999/1000"))

        assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }
    }

    @Test
    fun `a complete part is verified offline, with no request at all`() {
        part.writeBytes(bytes)

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a complete part that fails its hash is deleted without a request`() {
        part.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertThrows<DownloadFailure> { downloader().download { _, _ -> } }
        assertFalse(part.exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an oversized part is dropped before resuming`() {
        part.writeBytes(ByteArray(bytes.size + 1))
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        server.takeRequest()
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun `parts from another revision are deleted`() {
        val stale = File(dir, "model.bin.oldrev.part").apply { writeBytes(ByteArray(10)) }
        val unrelated = File(dir, "other.bin.oldrev.part").apply { writeBytes(ByteArray(10)) }
        server.enqueue(head())
        server.enqueue(body(bytes))

        downloader().download { _, _ -> }

        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `wrong full-length bytes delete the part`() {
        server.enqueue(head())
        server.enqueue(body(ByteArray(bytes.size)))

        val failure = assertThrows<DownloadFailure.HashMismatch> { downloader().download { _, _ -> } }
        assertFalse(part.exists())
        assertFalse(final.exists())
    }

    @Test
    fun `a body longer than the pin stops and deletes the part`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(
            body(bytes.copyOfRange(400, bytes.size) + ByteArray(5), code = 206, range = "bytes 400-1004/1005"),
        )

        assertThrows<DownloadFailure.HashMismatch> { downloader().download { _, _ -> } }
        assertFalse(part.exists())
    }

    @Test
    fun `a body that ends early keeps its bytes for a resume`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(body(bytes.copyOfRange(400, 500), code = 206, range = "bytes 400-999/1000"))

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }

        assertNull(failure.status)
        assertArrayEquals(bytes.copyOf(500), part.readBytes())
    }

    @Test
    fun `a range the server rejects restarts from zero`() {
        part.writeBytes(bytes.copyOf(400))
        server.enqueue(head())
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        server.takeRequest()
        assertEquals("bytes=400-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun `an interrupted pull resumes from the bytes on disk on the next call`() {
        server.enqueue(head())
        server.enqueue(body(bytes).newBuilder().throttleBody(100, 1, TimeUnit.MILLISECONDS).build())

        assertThrows<IllegalStateException> {
            downloader().download { done, _ -> check(done < 400) { "stopped" } }
        }
        val kept = part.length()
        assertTrue(kept in 400L until pin.bytes)
        assertArrayEquals(bytes.copyOf(kept.toInt()), part.readBytes())

        server.enqueue(head())
        val tail = bytes.copyOfRange(kept.toInt(), bytes.size)
        server.enqueue(body(tail, code = 206, range = "bytes $kept-999/1000"))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
        repeat(3) { server.takeRequest() }
        assertEquals("bytes=$kept-", server.takeRequest().headers["Range"])
    }

    @Test
    fun `a resumed part with bad bytes is deleted after the hash check`() {
        part.writeBytes(ByteArray(400))
        server.enqueue(head())
        server.enqueue(body(bytes.copyOfRange(400, bytes.size), code = 206, range = "bytes 400-999/1000"))

        assertThrows<DownloadFailure.HashMismatch> { downloader().download { _, _ -> } }
        assertFalse(part.exists())
    }

    @Test
    fun `a truncated final file is deleted and pulled again`() {
        final.writeBytes(bytes.copyOf(500))
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertArrayEquals(bytes, downloader().download { _, _ -> }.readBytes())
    }

    @Test
    fun `a sideload in progress is never treated as a stale part`() {
        val pushing = File(dir, "model.bin.part").apply { writeBytes(ByteArray(10)) }
        server.enqueue(head())
        server.enqueue(body(bytes))

        downloader().download { _, _ -> }

        assertTrue(pushing.exists())
    }

    @Test
    fun `an error status from the CDN names the CDN host`() {
        val start = "http://127.0.0.1:${server.port}/f"
        val cdnUrl = "http://localhost:${cdn.port}/blob"
        server.enqueue(head())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", cdnUrl).build())
        cdn.enqueue(MockResponse.Builder().code(403).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader(start).download { _, _ -> } }

        assertEquals("localhost", failure.host)
        assertEquals(403, failure.status)
    }

    @Test
    fun `a body dropped after the redirect names the CDN host`() {
        val start = "http://127.0.0.1:${server.port}/f"
        server.enqueue(head())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://localhost:${cdn.port}/b").build())
        cdn.enqueue(body(bytes).newBuilder().onResponseBody(SocketEffect.ShutdownConnection).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader(start).download { _, _ -> } }

        assertEquals("localhost", failure.host)
    }

    @Test
    fun `an unreachable CDN is named in the failure`() {
        val start = "http://127.0.0.1:${server.port}/f"
        val deadUrl = "http://localhost:${cdn.port}/blob"
        cdn.close()
        server.enqueue(head())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", deadUrl).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader(start).download { _, _ -> } }

        assertEquals("localhost", failure.host)
        assertNull(failure.status)
        assertTrue(failure.cause is IOException)
    }

    @Test
    fun `a connection dropped mid-body is a host failure`() {
        server.enqueue(head())
        server.enqueue(body(bytes).newBuilder().onResponseBody(SocketEffect.ShutdownConnection).build())

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }
        assertFalse(final.exists())
    }

    @Test
    fun `a redirect that switches scheme is never followed`() {
        server.enqueue(head())
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "https://localhost:${cdn.port}/b").build(),
        )

        val failure = assertThrows<DownloadFailure.HostFailed> { downloader().download { _, _ -> } }

        assertTrue("302" in failure.message.orEmpty())
        assertEquals(0, cdn.requestCount)
    }

    @Test
    fun `stopping from the progress callback keeps the part for a resume`() {
        server.enqueue(head())
        server.enqueue(body(bytes))

        assertThrows<IllegalStateException> {
            downloader().download { done, _ -> check(done == 0L) { "stopped" } }
        }

        assertTrue(part.exists())
        assertFalse(final.exists())
    }
}
