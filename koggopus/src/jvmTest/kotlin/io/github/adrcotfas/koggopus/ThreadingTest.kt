package io.github.adrcotfas.koggopus

import org.junit.Test
import kotlin.concurrent.thread

class ThreadingTest {

    @Test
    fun closingFromAnotherThreadIsSafe() {
        val pcm = sine(rate = 16000, seconds = 0.5)
        val stream = encode(pcm, rate = 16000)
        repeat(100) {
            val decoder = OggOpusDecoder(16000)
            val encoder = OggOpusEncoder(16000)
            val workers = listOf(
                thread { try { while (true) decoder.feed(stream) } catch (_: IllegalStateException) {} },
                thread { try { while (true) encoder.write(pcm) } catch (_: IllegalStateException) {} },
            )
            Thread.sleep(2)
            decoder.close()
            encoder.close()
            workers.forEach { it.join() }
        }
    }
}
