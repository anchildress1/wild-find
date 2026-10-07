package dev.anchildress1.wildfind.core.download

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Why a download stopped. */
sealed class DownloadFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The pinned URL no longer serves the pinned bytes; only an app update fixes it. Nothing was pulled. */
    class PinMismatch(detail: String) : DownloadFailure(detail)

    /** Free space is under [neededBytes], the bytes still to pull. */
    class NotEnoughStorage(val neededBytes: Long) : DownloadFailure("need $neededBytes free bytes")

    /**
     * [host] couldn't be reached, answered with an error, or dropped the body; the partial file stays for a resume.
     *
     * @property status the HTTP status when the host answered, null when it never did (blocked, offline, dropped)
     */
    class HostFailed(val host: String, detail: String, val status: Int? = null, cause: Throwable? = null) :
        DownloadFailure("$host: $detail", cause)

    /** The full-length bytes didn't match the pinned SHA-256, or ran past the pinned size; the partial file is gone. */
    class HashMismatch : DownloadFailure("size or SHA-256 mismatch")
}

/**
 * Resumable, verified download of one pinned file into [dir].
 *
 * Trust comes from the exact byte count and SHA-256, never the host: the CDN behind the start URL moves.
 * The final file only appears by rename after verification, and a `.sha256` marker beside it records the pin
 * it passed, so later launches skip the 2.6 GB hash.
 *
 * @param baseClient HTTP client; redirect and event settings are layered on a copy
 * @property pin the pinned file
 * @property dir destination directory
 * @property freeBytes usable space in [dir]
 * @property url start URL; the pin's Hugging Face URL outside tests
 */
class ModelDownloader(
    baseClient: OkHttpClient,
    private val pin: ModelPin,
    private val dir: File,
    private val freeBytes: () -> Long,
    private val url: String = pin.url,
) {
    // The host the current request is talking to, so a failure can name it: a filtered network blocks the CDN,
    // not huggingface.co. Each redirect hop resolves or reuses a connection, which updates it.
    @Volatile private var host = url.toHttpUrl().host

    // The start URL is HTTPS, and no scheme switch is followed, so every hop stays HTTPS.
    private val client = baseClient.newBuilder()
        .followSslRedirects(false)
        .eventListener(
            object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) {
                    host = domainName
                }

                override fun connectionAcquired(call: Call, connection: Connection) {
                    host = connection.route().address.url.host
                }
            },
        )
        .build()

    // The 302 from huggingface.co carries the pins; following it would read the CDN's headers instead.
    private val head = client.newBuilder().followRedirects(false).build()

    private val final = File(dir, pin.file)
    private val marker = File(dir, "${pin.file}.sha256")

    // Keyed by revision so a re-pin never resumes bytes from the old revision. Other revisions' parts are stale;
    // make push-models streams into "<file>.part", which this pattern leaves alone.
    private val part = File(dir, "${pin.file}.${pin.revision}.part")
    private val stalePart = Regex("${Regex.escape(pin.file)}\\.[^.]+\\.part")

    /**
     * Returns the verified file, downloading or resuming first when needed.
     *
     * [onProgress] gets (bytes on disk, total bytes) and may throw to stop; the partial file then stays for a resume.
     * Throws [DownloadFailure], or a plain [IOException] when the local disk fails.
     */
    fun download(onProgress: (Long, Long) -> Unit): File {
        ready()?.let { return it }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        dir.listFiles { f -> f != part && stalePart.matches(f.name) }?.forEach(File::delete)
        if (part.length() > pin.bytes) part.deleteOrThrow()
        preflight()
        requireSpace()
        if (part.length() < pin.bytes) fetch(onProgress)
        if (sha256(part) != pin.sha256) part.discardMismatch()
        // A stopped run still finishing its last chunk can verify the same part; whichever renames second finds
        // the file already in place.
        if (!part.renameTo(final) && ready() == null) throw IOException("can't rename $part")
        // A crash before the marker lands costs one re-hash on the next launch, never a re-download.
        marker.writeText(pin.sha256)
        return final
    }

    /**
     * The verified file, or null; deletes a file that fails the pin, throwing [IOException] if it can't. Never touches
     * the network.
     */
    fun ready(): File? {
        // Sideloaded by make push-models, or left by an older pin of the same size, the file has no matching marker
        // and is hashed once.
        val verified = final.length() == pin.bytes &&
            ((marker.isFile && marker.readText() == pin.sha256) || sha256(final) == pin.sha256)
        if (verified) {
            marker.writeText(pin.sha256)
        } else {
            final.deleteOrThrow()
            marker.deleteOrThrow()
        }
        return final.takeIf { verified }
    }

    private fun requireSpace() {
        val needed = pin.bytes - part.length()
        if (freeBytes() < needed) throw DownloadFailure.NotEnoughStorage(needed)
    }

    private fun preflight() {
        execute(head, Request.Builder().url(url).head().build()).use {
            if (it.code ==
                HTTP_NOT_FOUND
            ) {
                throw DownloadFailure.PinMismatch("$host has no ${pin.file} at ${pin.revision}")
            }
            if (it.code !in HTTP_OK..LAST_REDIRECT) throw hostFailed("HTTP ${it.code}", it.code)
            checkPins(it)
        }
    }

    private fun checkPins(response: Response) {
        val size = response.header("x-linked-size")?.toLongOrNull()
        val etag = response.header("x-linked-etag")?.removePrefix("W/")?.trim('"')
        // A captive portal or filtering proxy answers without the pin headers; that's the network, not the pin.
        if (size == null || etag == null) throw hostFailed("no pin headers in HTTP ${response.code}")
        if (size != pin.bytes || etag != pin.sha256) {
            throw DownloadFailure.PinMismatch("$host serves $size bytes with SHA-256 $etag, not the pin")
        }
    }

    private fun fetch(onProgress: (Long, Long) -> Unit) {
        val start = part.length()
        val request = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
        val restart = execute(client, request).use { response ->
            val resumes = response.code == HTTP_PARTIAL &&
                response.header("Content-Range")?.startsWith("bytes $start-") == true
            when {
                start > 0 && resumes -> {
                    write(response, start, onProgress)
                    false
                }

                // A full body: start over from byte zero. A proxy's block page is a 200 too, and must not cost the
                // bytes already on disk.
                response.code == HTTP_OK -> {
                    val length = response.body.contentLength()
                    if (length != pin.bytes) throw hostFailed("HTTP 200 with $length bytes", response.code)
                    part.deleteOrThrow()
                    write(response, 0, onProgress)
                    false
                }

                // A partial body at another offset, or a range the server rejects, can't extend the part.
                start > 0 && response.code in RANGE_RESTART -> true

                else -> throw hostFailed("HTTP ${response.code}", response.code)
            }
        }
        if (restart) {
            part.deleteOrThrow()
            fetch(onProgress)
        }
    }

    // Writes by position, not append: a stopped run can still land its last chunk after the next run read the part
    // length, and both then write the same bytes at the same offsets instead of shifting the file.
    private fun write(response: Response, start: Long, onProgress: (Long, Long) -> Unit) {
        val source = response.body.byteStream()
        val buffer = ByteArray(CHUNK)
        var written = start
        RandomAccessFile(part, "rw").use { out ->
            out.seek(start)
            onProgress(written, pin.bytes)
            var count = read(source, buffer)
            while (count >= 0) {
                if (written + count > pin.bytes) part.discardMismatch()
                out.write(buffer, 0, count)
                written += count
                onProgress(written, pin.bytes)
                count = read(source, buffer)
            }
        }
        // A body that ends cleanly but early keeps its bytes, so the next attempt resumes instead of re-pulling.
        if (written < pin.bytes) throw hostFailed("body ended at $written of ${pin.bytes} bytes")
    }

    private fun read(source: InputStream, buffer: ByteArray): Int = try {
        source.read(buffer)
    } catch (e: IOException) {
        throw hostFailed(e.message ?: e.javaClass.simpleName, cause = e)
    }

    private fun execute(http: OkHttpClient, request: Request): Response {
        host = request.url.host
        return try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw hostFailed(e.message ?: e.javaClass.simpleName, cause = e)
        }
    }

    private fun hostFailed(detail: String, status: Int? = null, cause: Throwable? = null) =
        DownloadFailure.HostFailed(host, detail, status, cause)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val LAST_REDIRECT = 399
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val RANGE_RESTART = setOf(HTTP_PARTIAL, HTTP_RANGE_NOT_SATISFIABLE)
        const val CHUNK = 256 * 1024
    }
}

private fun sha256(file: File): String = HashingSink.sha256(blackholeSink()).use { sink ->
    file.source().buffer().use { it.readAll(sink) }
    sink.hash.hex()
}

// A partial file that survives a delete would get a fresh body appended after its stale bytes.
private fun File.deleteOrThrow() {
    if (!delete() && exists()) throw IOException("can't delete $this")
}

private fun File.discardMismatch(): Nothing {
    deleteOrThrow()
    throw DownloadFailure.HashMismatch()
}
