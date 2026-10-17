package io.github.adrcotfas.koggopus

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decodes an Ogg Opus stream that arrives in arbitrary chunks into 16-bit PCM.
 *
 * Chunks do not need to line up with Ogg pages or Opus packets. The decoder drops the
 * stream's pre-skip samples, trims the end as the final page directs, applies the header
 * gain, and carries on across chained streams.
 *
 * There are two ways to use it. [feed] takes a chunk and returns all the audio it
 * completes, which is the simplest. [push] and [read] separate the two steps, so the
 * caller decides how much audio is decoded at a time; use them when the stream is not
 * trusted, or to decode straight into a playback buffer. Either way the audio comes as
 * samples in a `ShortArray` or, with [feedBytes] and the `ByteArray` form of [read], as
 * little-endian bytes.
 *
 * Calls on one instance are serialised, so [close] may be called from another thread
 * while another call is in progress.
 *
 * @param sampleRate output rate in Hz: 8000, 12000, 16000, 24000 or 48000. It does not have
 * to match the rate the stream was encoded from.
 * @param channels output layout: 1 for mono, 2 for stereo (interleaved), or null to use
 * that of the first stream. The layout never changes afterwards: a chained stream with a
 * different channel count is converted to it.
 */
public class OggOpusDecoder(public val sampleRate: Int = 48000, channels: Int? = null) : AutoCloseable {

    // Fair, so close() gets in as soon as the call in progress returns, even if another
    // thread is calling feed() in a tight loop.
    private val lock = ReentrantLock(true)
    private var handle: Long

    init {
        require(sampleRate in SUPPORTED_RATES) { "sampleRate must be one of $SUPPORTED_RATES" }
        require(channels == null || channels == 1 || channels == 2) { "channels must be 1, 2 or null" }
        NativeLibrary.ensureLoaded()
        handle = nativeCreate(sampleRate, channels ?: 0)
    }

    /**
     * Consumes the next bytes of the stream and returns the audio they complete, interleaved
     * when [channels] is 2. The result is empty when more data is needed.
     *
     * Corrupt pages and packets are skipped. A stream this library cannot decode makes this
     * throw [OggOpusException]; the decoder stays usable for whatever follows.
     *
     * A few bytes of Opus can expand to a lot of silence: each kilobyte fed can produce up
     * to about 3 MB of mono or 6 MB of stereo PCM at 48 kHz. When the stream is untrusted,
     * size the chunks accordingly or use [push] and [read]. A call that would return more
     * than 2^30 samples throws [OggOpusException] and discards that audio.
     */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray {
        checkRange(bytes.size, offset, length, 1)
        return lock.withLock { nativeFeed(open(), bytes, offset, length) }
    }

    /**
     * Like [feed], but returns the audio as little-endian 16-bit samples, two bytes each:
     * the form the encoder's `write(ByteArray)` takes, and what a socket or a file carries.
     */
    public fun feedBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        checkRange(bytes.size, offset, length, 1)
        return lock.withLock { nativeFeedBytes(open(), bytes, offset, length) }
    }

    /**
     * Queues the next bytes of the stream without decoding them. Follow it with [read]
     * until that returns 0; the bytes stay in memory until then.
     */
    public fun push(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        checkRange(bytes.size, offset, length, 1)
        lock.withLock { nativePush(open(), bytes, offset, length) }
    }

    /**
     * Decodes queued data into [pcm] and returns the number of samples (not frames) written,
     * interleaved when [channels] is 2. Returns 0 when everything queued has been decoded
     * and more data is needed. Each call does a bounded amount of work, however the input
     * is crafted.
     *
     * Corrupt pages and packets are skipped. A stream this library cannot decode makes this
     * throw [OggOpusException], once the audio ahead of it has been read; the decoder stays
     * usable, so keep reading.
     *
     * @param length room in [pcm], at least 2 samples. Only whole frames are written.
     */
    public fun read(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Int {
        checkRange(pcm.size, offset, length, 1)
        require(length >= 2) { "length must be at least 2" }
        return lock.withLock { nativeRead(open(), pcm, offset, length) }
    }

    /**
     * Like [read], but writes the audio as little-endian 16-bit samples, two bytes each, and
     * returns the number of bytes written.
     *
     * @param length room in [pcm16le] in bytes, at least 4. Only whole frames are written.
     */
    public fun read(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset): Int {
        checkRange(pcm16le.size, offset, length, 1)
        require(length >= 4) { "length must be at least 4" }
        return lock.withLock { nativeReadBytes(open(), pcm16le, offset, length) }
    }

    /**
     * Channels in the decoded audio: the value given to the constructor, or else the
     * channel count of the first stream, null until its header has arrived.
     */
    public val channels: Int?
        get() = lock.withLock { nativeChannels(open()) }.takeIf { it != 0 }

    /** Header of the stream being decoded, or null until it has arrived. */
    public val head: OpusHead?
        get() = lock.withLock { nativeHead(open()) }?.let { OpusHead(it[0], it[1], it[2], it[3]) }

    /**
     * Comment header of the stream being decoded. Null until it has arrived, and also when
     * the stream's comment header is larger than 16 MB, which is skipped. Only the first
     * 1024 comments are kept.
     */
    public val tags: OpusTags?
        get() = lock.withLock { nativeTags(open()) }?.let(::parseOpusTags)

    /** Number of Opus streams seen so far; more than 1 means the input is chained. */
    public val streamCount: Int
        get() = lock.withLock { nativeStreamCount(open()) }

    /** Releases native resources. Safe to call more than once. */
    override fun close(): Unit = lock.withLock {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private fun open(): Long {
        check(handle != 0L) { "decoder is closed" }
        return handle
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeFeed(handle: Long, data: ByteArray, offset: Int, length: Int): ShortArray
    private external fun nativeFeedBytes(handle: Long, data: ByteArray, offset: Int, length: Int): ByteArray
    private external fun nativePush(handle: Long, data: ByteArray, offset: Int, length: Int)
    private external fun nativeRead(handle: Long, pcm: ShortArray, offset: Int, length: Int): Int
    private external fun nativeReadBytes(handle: Long, pcm: ByteArray, offset: Int, length: Int): Int
    private external fun nativeChannels(handle: Long): Int
    private external fun nativeHead(handle: Long): IntArray?
    private external fun nativeTags(handle: Long): ByteArray?
    private external fun nativeStreamCount(handle: Long): Int
    private external fun nativeDestroy(handle: Long)

    private companion object {
        val SUPPORTED_RATES = listOf(8000, 12000, 16000, 24000, 48000)
    }
}
