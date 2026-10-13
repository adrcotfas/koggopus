package io.github.adrcotfas.koggopus

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Encodes 16-bit PCM into an Ogg Opus stream, one page at a time.
 *
 * Every call returns the Ogg pages it completed, possibly none. Concatenating everything
 * returned by [headers], [write] and [finish] yields a valid `.opus` stream. Pages are
 * always returned whole, so each result can be sent as one network message.
 *
 * Calls on one instance are serialised, so [close] may be called from another thread
 * while a [write] is in progress.
 *
 * @param sampleRate rate of the PCM passed to [write], in Hz. Any rate is accepted; rates
 * other than 48000 are resampled internally.
 * @param channels 1 for mono, 2 for stereo (interleaved).
 * @param bitrate target bitrate in bits per second, or null to let Opus choose.
 * @param application what to optimise for.
 * @param complexity 0 (fastest) to 10 (best quality), or null for the Opus default.
 * @param vbr variable bitrate when true, constant bitrate when false.
 * @param maxBandwidth upper limit on coded audio bandwidth. The default, null, limits it to
 * what [sampleRate] can carry, so no bits are spent on frequencies the input cannot contain.
 * @param pageDurationMs the most audio held back before a page is returned, 0 to 1000. Lower
 * values reduce latency and increase container overhead.
 * @param comments tag and value pairs written to the comment header.
 * @param serialNumber the Ogg stream serial number. The default is random, which is what
 * streams that may later be concatenated need.
 */
public class OggOpusEncoder(
    public val sampleRate: Int,
    public val channels: Int = 1,
    bitrate: Int? = null,
    application: OpusApplication = OpusApplication.AUDIO,
    complexity: Int? = null,
    vbr: Boolean = true,
    maxBandwidth: OpusBandwidth? = null,
    pageDurationMs: Int = 100,
    comments: List<Pair<String, String>> = emptyList(),
    serialNumber: Int = Random.nextInt(),
) : AutoCloseable {

    // Fair, so close() gets in as soon as the call in progress returns, even if another
    // thread is calling write() in a tight loop.
    private val lock = ReentrantLock(true)
    private var handle: Long

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        require(bitrate == null || bitrate > 0) { "bitrate must be positive" }
        require(complexity == null || complexity in 0..10) { "complexity must be in 0..10" }
        require(pageDurationMs in 0..1000) { "pageDurationMs must be in 0..1000" }
        val encodedComments = comments.map { (tag, value) ->
            require(tag.isNotEmpty() && '=' !in tag) { "invalid comment tag: $tag" }
            require('\u0000' !in tag && '\u0000' !in value) { "comments must not contain NUL" }
            "$tag=$value".toByteArray(Charsets.UTF_8)
        }
        NativeLibrary.ensureLoaded()
        handle = nativeCreate(
            sampleRate,
            channels,
            bitrate ?: 0,
            application.value,
            complexity ?: -1,
            vbr,
            (maxBandwidth ?: bandwidthFor(sampleRate)).value,
            pageDurationMs,
            serialNumber,
            encodedComments.toTypedArray(),
        )
    }

    /**
     * Returns the two header pages. Calling this is optional: if it is skipped, the first
     * [write] returns the headers ahead of the audio. Once the headers have been returned,
     * by either route, this returns an empty array.
     */
    public fun headers(): ByteArray = lock.withLock { nativeHeaders(open()) }

    /**
     * Encodes interleaved samples and returns the pages completed so far.
     *
     * @param length number of samples (not frames) to read, a multiple of [channels].
     */
    public fun write(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        checkRange(pcm.size, offset, length, channels)
        return lock.withLock { nativeWriteShorts(open(), pcm, offset, length / channels) }
    }

    /**
     * Encodes interleaved little-endian 16-bit samples and returns the pages completed so far.
     *
     * @param length number of bytes to read, a multiple of `2 * channels`.
     */
    public fun write(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset): ByteArray {
        checkRange(pcm16le.size, offset, length, 2 * channels)
        return lock.withLock { nativeWriteBytes(open(), pcm16le, offset, length, length / (2 * channels)) }
    }

    /**
     * Encodes the audio still buffered and returns the remaining pages, the last of which is
     * marked end-of-stream. The encoder accepts no more audio afterwards.
     */
    public fun finish(): ByteArray = lock.withLock { nativeFinish(open()) }

    /** Releases native resources. Safe to call more than once. */
    override fun close(): Unit = lock.withLock {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private fun open(): Long {
        check(handle != 0L) { "encoder is closed" }
        return handle
    }

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        application: Int,
        complexity: Int,
        vbr: Boolean,
        maxBandwidth: Int,
        pageMs: Int,
        serial: Int,
        comments: Array<ByteArray>,
    ): Long

    private external fun nativeHeaders(handle: Long): ByteArray
    private external fun nativeWriteShorts(handle: Long, pcm: ShortArray, offset: Int, frames: Int): ByteArray
    private external fun nativeWriteBytes(handle: Long, pcm: ByteArray, offset: Int, length: Int, frames: Int): ByteArray
    private external fun nativeFinish(handle: Long): ByteArray
    private external fun nativeDestroy(handle: Long)
}

/** The widest Opus bandwidth that audio sampled at [sampleRate] can fill. */
internal fun bandwidthFor(sampleRate: Int): OpusBandwidth = when {
    sampleRate <= 8000 -> OpusBandwidth.NARROWBAND
    sampleRate <= 12000 -> OpusBandwidth.MEDIUMBAND
    sampleRate <= 16000 -> OpusBandwidth.WIDEBAND
    sampleRate <= 24000 -> OpusBandwidth.SUPERWIDEBAND
    else -> OpusBandwidth.FULLBAND
}

internal fun checkRange(size: Int, offset: Int, length: Int, unit: Int) {
    require(offset >= 0 && length >= 0 && offset <= size - length) {
        "offset $offset and length $length are outside an array of size $size"
    }
    require(length % unit == 0) { "length $length is not a multiple of $unit" }
}
