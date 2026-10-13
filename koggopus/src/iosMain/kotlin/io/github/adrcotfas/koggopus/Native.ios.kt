@file:OptIn(ExperimentalForeignApi::class)

package io.github.adrcotfas.koggopus

import io.github.adrcotfas.koggopus.cinterop.KOGG_OK
import cnames.structs.kogg_decoder
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_channels
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_create
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_destroy
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_feed
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_head
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_push
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_read
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_stream_count
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_tags
import io.github.adrcotfas.koggopus.cinterop.kogg_decoder_take
import cnames.structs.kogg_encoder
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_config
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_create
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_destroy
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_finish
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_headers
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_take
import io.github.adrcotfas.koggopus.cinterop.kogg_encoder_write
import io.github.adrcotfas.koggopus.cinterop.kogg_head
import io.github.adrcotfas.koggopus.cinterop.kogg_strerror
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock
import platform.posix.memcpy
import platform.posix.size_tVar

private fun check(status: Int) {
    if (status != KOGG_OK) throw OggOpusException(status, kogg_strerror(status)?.toKString() ?: "error $status")
}

private class CEncoder(private val encoder: CPointer<kogg_encoder>) : NativeEncoder {
    override fun headers() = takePages(kogg_encoder_headers(encoder))

    override fun write(pcm: ShortArray, offset: Int, frames: Int): ByteArray {
        if (frames == 0) return ByteArray(0) // nothing to pin
        return takePages(pcm.usePinned { kogg_encoder_write(encoder, it.addressOf(offset), frames) })
    }

    override fun finish() = takePages(kogg_encoder_finish(encoder))

    override fun destroy() = kogg_encoder_destroy(encoder)

    /** Hands over the pages produced by the last call, or throws if that call failed. */
    private fun takePages(status: Int): ByteArray = memScoped {
        val length = alloc<size_tVar>()
        val pages = kogg_encoder_take(encoder, length.ptr)
        check(status)
        if (pages == null || length.value == 0uL) ByteArray(0) else pages.readBytes(length.value.toInt())
    }
}

private class CDecoder(private val decoder: CPointer<kogg_decoder>) : NativeDecoder {
    override fun feed(data: ByteArray, offset: Int, length: Int): ShortArray = memScoped {
        // Audio decoded before an error stays buffered and is returned by the next feed.
        check(withInput(data, offset, length) { input -> kogg_decoder_feed(decoder, input, length.convert()) })
        val count = alloc<size_tVar>()
        val samples = kogg_decoder_take(decoder, count.ptr)
        ShortArray(count.value.toInt()).also { pcm ->
            if (pcm.isNotEmpty()) pcm.usePinned { memcpy(it.addressOf(0), samples, (pcm.size * Short.SIZE_BYTES).convert()) }
        }
    }

    override fun push(data: ByteArray, offset: Int, length: Int) =
        check(withInput(data, offset, length) { input -> kogg_decoder_push(decoder, input, length.convert()) })

    override fun read(pcm: ShortArray, offset: Int, length: Int): Int = memScoped {
        val count = alloc<size_tVar>()
        check(pcm.usePinned { kogg_decoder_read(decoder, it.addressOf(offset), length.convert(), count.ptr) })
        count.value.toInt()
    }

    override fun channels() = kogg_decoder_channels(decoder)

    override fun head(): IntArray? = memScoped {
        val head = alloc<kogg_head>()
        if (kogg_decoder_head(decoder, head.ptr) == 0) return null
        intArrayOf(head.channels, head.pre_skip, head.input_sample_rate.toInt(), head.output_gain)
    }

    override fun tags(): ByteArray? = memScoped {
        val length = alloc<size_tVar>()
        kogg_decoder_tags(decoder, length.ptr)?.readBytes(length.value.toInt())
    }

    override fun streamCount() = kogg_decoder_stream_count(decoder)

    override fun destroy() = kogg_decoder_destroy(decoder)

    private inline fun withInput(
        data: ByteArray,
        offset: Int,
        length: Int,
        call: (CPointer<kotlinx.cinterop.UByteVar>?) -> Int,
    ): Int = if (length == 0) call(null) else data.usePinned { call(it.addressOf(offset).reinterpret()) }
}

internal actual fun createNativeEncoder(
    sampleRate: Int,
    channels: Int,
    bitrate: Int,
    application: Int,
    complexity: Int,
    vbr: Boolean,
    maxBandwidth: Int,
    pageMs: Int,
    serial: Int,
    comments: List<ByteArray>,
): NativeEncoder = memScoped {
    val config = alloc<kogg_encoder_config>()
    config.sample_rate = sampleRate
    config.channels = channels
    config.bitrate = bitrate
    config.application = application
    config.complexity = complexity
    config.vbr = if (vbr) 1 else 0
    config.max_bandwidth = maxBandwidth
    config.page_ms = pageMs
    config.serial = serial
    config.comment_count = comments.size
    if (comments.isNotEmpty()) {
        // The C core wants NUL-terminated strings.
        val strings = allocArray<CPointerVar<ByteVar>>(comments.size)
        comments.forEachIndexed { index, comment ->
            val string = allocArray<ByteVar>(comment.size + 1)
            comment.forEachIndexed { i, byte -> string[i] = byte }
            strings[index] = string
        }
        config.comments = strings
    }
    val error = alloc<IntVar>()
    val encoder = kogg_encoder_create(config.ptr, error.ptr)
    check(error.value)
    CEncoder(checkNotNull(encoder))
}

internal actual fun createNativeDecoder(sampleRate: Int, channels: Int): NativeDecoder = memScoped {
    val error = alloc<IntVar>()
    val decoder = kogg_decoder_create(sampleRate, channels, error.ptr)
    check(error.value)
    CDecoder(checkNotNull(decoder))
}

internal actual fun createLock(): Lock = object : Lock {
    private val lock = NSLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
