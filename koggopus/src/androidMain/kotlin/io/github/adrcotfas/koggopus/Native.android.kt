package io.github.adrcotfas.koggopus

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The functions of native/jni/koggopus_jni.c. Handles are pointers to the C objects. */
internal object Jni {
    init {
        System.loadLibrary("koggopus")
    }

    external fun encoderCreate(
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

    external fun encoderHeaders(handle: Long): ByteArray
    external fun encoderWrite(handle: Long, pcm: ShortArray, offset: Int, frames: Int): ByteArray
    external fun encoderFinish(handle: Long): ByteArray
    external fun encoderDestroy(handle: Long)

    external fun decoderCreate(sampleRate: Int, channels: Int): Long
    external fun decoderFeed(handle: Long, data: ByteArray, offset: Int, length: Int): ShortArray
    external fun decoderPush(handle: Long, data: ByteArray, offset: Int, length: Int)
    external fun decoderRead(handle: Long, pcm: ShortArray, offset: Int, length: Int): Int
    external fun decoderChannels(handle: Long): Int
    external fun decoderHead(handle: Long): IntArray?
    external fun decoderTags(handle: Long): ByteArray?
    external fun decoderStreamCount(handle: Long): Int
    external fun decoderDestroy(handle: Long)
}

private class JniEncoder(private val handle: Long) : NativeEncoder {
    override fun headers() = Jni.encoderHeaders(handle)
    override fun write(pcm: ShortArray, offset: Int, frames: Int) = Jni.encoderWrite(handle, pcm, offset, frames)
    override fun finish() = Jni.encoderFinish(handle)
    override fun destroy() = Jni.encoderDestroy(handle)
}

private class JniDecoder(private val handle: Long) : NativeDecoder {
    override fun feed(data: ByteArray, offset: Int, length: Int) = Jni.decoderFeed(handle, data, offset, length)
    override fun push(data: ByteArray, offset: Int, length: Int) = Jni.decoderPush(handle, data, offset, length)
    override fun read(pcm: ShortArray, offset: Int, length: Int) = Jni.decoderRead(handle, pcm, offset, length)
    override fun channels() = Jni.decoderChannels(handle)
    override fun head() = Jni.decoderHead(handle)
    override fun tags() = Jni.decoderTags(handle)
    override fun streamCount() = Jni.decoderStreamCount(handle)
    override fun destroy() = Jni.decoderDestroy(handle)
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
): NativeEncoder = JniEncoder(
    Jni.encoderCreate(
        sampleRate, channels, bitrate, application, complexity, vbr, maxBandwidth, pageMs, serial,
        comments.toTypedArray(),
    ),
)

internal actual fun createNativeDecoder(sampleRate: Int, channels: Int): NativeDecoder =
    JniDecoder(Jni.decoderCreate(sampleRate, channels))

// Fair, so close() gets in as soon as the call in progress returns, even if another
// thread is calling write() or feed() in a tight loop.
internal actual fun createLock(): Lock = object : Lock {
    private val lock = ReentrantLock(true)
    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
