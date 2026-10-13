package io.github.adrcotfas.koggopus.check

import android.app.Activity
import android.os.Bundle
import io.github.adrcotfas.koggopus.OggOpusDecoder
import io.github.adrcotfas.koggopus.OggOpusEncoder
import io.github.adrcotfas.koggopus.OggOpusException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exercises the library from shrunk and obfuscated app code and reports the outcome in the
 * activity title, which the test can read without knowing any renamed symbol.
 */
class CheckActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = try {
            check()
            PASSED
        } catch (e: Throwable) {
            "$e"
        }
    }

    private fun check() {
        val pcm = ShortArray(8000) { ((it % 80) * 200 - 8000).toShort() }
        val stream = OggOpusEncoder(16000, comments = listOf("TITLE" to "check")).use {
            it.headers() + it.write(pcm) + it.finish()
        }
        OggOpusDecoder(16000).use { decoder ->
            check(decoder.feed(stream).size == pcm.size) { "unexpected decoded length" }
            check(decoder.head?.channels == 1) { "missing header" }
            check(decoder.tags?.comments == listOf("TITLE" to "check")) { "missing tags" }

            // The native layer constructs this exception by name and signature.
            try {
                decoder.feed(surroundHeaderPage())
                error("a surround stream was accepted")
            } catch (e: OggOpusException) {
                check(e.code == -103 && !e.message.isNullOrEmpty()) { "unexpected error $e" }
            }
        }
    }

    /** An Ogg page whose OpusHead declares six channels, which the decoder must reject. */
    private fun surroundHeaderPage(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 6, 0, 0, 0, 0, 0, 0, 0, 0, 1, 4, 2, 0, 4, 1, 2, 3, 5)
        val page = ByteBuffer.allocate(28 + head.size).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray()).put(0).put(0x02)
        page.putLong(0).putInt(99).putInt(0).putInt(0)
        page.put(1).put(head.size.toByte()).put(head)
        val bytes = page.array()
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        page.putInt(22, crc)
        return bytes
    }

    companion object {
        const val PASSED = "passed"
    }
}
