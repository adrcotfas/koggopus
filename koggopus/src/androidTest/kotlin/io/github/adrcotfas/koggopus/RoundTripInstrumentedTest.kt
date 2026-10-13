package io.github.adrcotfas.koggopus

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** Proves the packaged native library loads and works on a device. Behaviour is covered by the unit tests. */
@RunWith(AndroidJUnit4::class)
class RoundTripInstrumentedTest {

    @Test
    fun encodesAndDecodesOnDevice() {
        val pcm = ShortArray(16000) { (sin(2 * PI * 440 * it / 16000) * 12000).toInt().toShort() }

        val stream = OggOpusEncoder(16000, bitrate = 24000, application = OpusApplication.VOIP).use {
            it.headers() + it.write(pcm) + it.finish()
        }
        val decoded = OggOpusDecoder(16000).use { decoder ->
            // Feed in small pieces, as a network stream would arrive.
            stream.asList().chunked(100).fold(ShortArray(0)) { out, chunk -> out + decoder.feed(chunk.toByteArray()) }
        }

        assertEquals(pcm.size, decoded.size)
        assertTrue(stream.size < pcm.size * 2 / 4)
    }
}
