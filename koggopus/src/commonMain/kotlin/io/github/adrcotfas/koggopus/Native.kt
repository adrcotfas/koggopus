package io.github.adrcotfas.koggopus

/*
 * What each platform provides: a binding to the C core in native/core, and a lock. The
 * bindings are not thread-safe and do not check their arguments; OggOpusEncoder and
 * OggOpusDecoder do both.
 */

internal interface NativeEncoder {
    fun headers(): ByteArray
    fun write(pcm: ShortArray, offset: Int, frames: Int): ByteArray
    fun finish(): ByteArray
    fun destroy()
}

internal interface NativeDecoder {
    fun feed(data: ByteArray, offset: Int, length: Int): ShortArray
    fun push(data: ByteArray, offset: Int, length: Int)
    fun read(pcm: ShortArray, offset: Int, length: Int): Int

    /** [feed] and [read] with the samples as little-endian bytes; lengths and results are in bytes. */
    fun feedBytes(data: ByteArray, offset: Int, length: Int): ByteArray
    fun readBytes(pcm: ByteArray, offset: Int, length: Int): Int

    /** 0 while the output layout still depends on a stream that has not arrived. */
    fun channels(): Int

    /** {channels, preSkip, inputSampleRate, outputGain}, or null before OpusHead arrived. */
    fun head(): IntArray?

    /** The raw OpusTags packet, or null. */
    fun tags(): ByteArray?
    fun streamCount(): Int
    fun destroy()
}

internal interface Lock {
    fun <T> withLock(block: () -> T): T
}

/** @param comments "TAG=value" strings in UTF-8, without a terminator. */
internal expect fun createNativeEncoder(
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
): NativeEncoder

internal expect fun createNativeDecoder(sampleRate: Int, channels: Int): NativeDecoder

internal expect fun createLock(): Lock
