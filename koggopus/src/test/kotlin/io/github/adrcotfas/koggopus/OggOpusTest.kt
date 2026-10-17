package io.github.adrcotfas.koggopus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class OggOpusTest {

    @Test
    fun roundTripKeepsLengthAndSignal() {
        val pcm = sine(rate = 16000, seconds = 1.0)
        val decoded = decode(encode(pcm, rate = 16000), rate = 16000)

        assertEquals(pcm.size, decoded.size)
        assertTrue("decoded audio should match the input", correlation(pcm, decoded) > 0.9)
    }

    @Test
    fun roundTripStereo() {
        val pcm = sine(rate = 48000, seconds = 0.5, channels = 2)
        val decoded = decode(encode(pcm, rate = 48000, channels = 2), rate = 48000)

        assertEquals(pcm.size, decoded.size)
        assertTrue(correlation(pcm, decoded) > 0.9)
    }

    @Test
    fun decodesAtARateOtherThanTheInputRate() {
        val stream = encode(sine(rate = 16000, seconds = 1.0), rate = 16000)

        assertEquals(48000, decode(stream, rate = 48000).size)
        assertEquals(8000, decode(stream, rate = 8000).size)
    }

    @Test
    fun byteInputProducesTheSameStreamAsShortInput() {
        val pcm = sine(rate = 16000, seconds = 0.3)
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { asShortBuffer().put(pcm) }.array()

        val fromShorts = OggOpusEncoder(16000, serialNumber = 7).use { it.write(pcm) + it.finish() }
        val fromBytes = OggOpusEncoder(16000, serialNumber = 7).use { it.write(bytes) + it.finish() }

        assertArrayEquals(fromShorts, fromBytes)
    }

    @Test
    fun chunkingDoesNotChangeTheDecodedAudio() {
        val stream = encode(sine(rate = 24000, seconds = 1.0), rate = 24000)
        val whole = decode(stream, rate = 24000)

        assertArrayEquals(whole, decode(stream, rate = 24000) { 1 })
        val random = Random(1)
        assertArrayEquals(whole, decode(stream, rate = 24000) { random.nextInt(1, 700) })
    }

    @Test
    fun everyResultIsAWholeNumberOfPages() {
        val pcm = sine(rate = 16000, seconds = 1.0)
        OggOpusEncoder(16000, pageDurationMs = 60).use { encoder ->
            val results = listOf(encoder.headers()) +
                pcm.asList().chunked(512).map { encoder.write(it.toShortArray()) } +
                listOf(encoder.finish())
            // pages() throws if a result ends in the middle of a page.
            results.forEach { pages(it) }
            assertEquals(2, pages(results.first()).size)
        }
    }

    @Test
    fun consecutiveStreamsAreIndependent() {
        val pcm = sine(rate = 16000, seconds = 0.5)
        val streams = List(3) { pages(encode(pcm, rate = 16000)) }

        for (stream in streams) {
            assertTrue("first page begins the stream", stream.first().flags and BOS != 0)
            assertTrue("last page ends the stream", stream.last().flags and EOS != 0)
            assertEquals(stream.indices.toList(), stream.map { it.sequence })
            assertEquals(1, stream.map { it.serial }.distinct().size)
        }
        assertEquals(3, streams.map { it.first().serial }.distinct().size)
    }

    @Test
    fun chainedStreamsDecodeBackToBack() {
        val first = sine(rate = 16000, seconds = 0.5)
        val second = sine(rate = 16000, seconds = 0.25, hz = 880.0)
        val chained = encode(first, rate = 16000) + encode(second, rate = 16000)

        OggOpusDecoder(16000).use { decoder ->
            val decoded = decoder.feed(chained)
            assertEquals(first.size + second.size, decoded.size)
            assertEquals(2, decoder.streamCount)
        }
    }

    @Test
    fun streamCutShortAfterItsFirstPageDoesNotBlockTheNextOne() {
        val pcm = sine(rate = 16000, seconds = 0.5)
        val cut = encode(pcm, rate = 16000)
        val headPage = cut.copyOf(28 + pages(cut).first().body.size) // 27-byte header, one segment

        OggOpusDecoder(16000).use { decoder ->
            assertEquals(0, decoder.feed(headPage).size)
            assertEquals(pcm.size, decoder.feed(encode(pcm, rate = 16000)).size)
            assertEquals(2, decoder.streamCount)
        }
    }

    @Test
    fun headersAreReturnedOnce() {
        val pcm = sine(rate = 16000, seconds = 0.1)
        OggOpusEncoder(16000).use { encoder ->
            assertEquals(2, pages(encoder.headers()).size)
            assertEquals(0, encoder.headers().size)
        }
        OggOpusEncoder(16000).use { encoder ->
            val implicit = encoder.write(pcm)
            assertTrue(pages(implicit).first().flags and BOS != 0)
            assertEquals(0, encoder.headers().size)
        }
    }

    @Test
    fun pushAndReadProduceTheSameAudioAsFeed() {
        val stream = encode(sine(rate = 48000, seconds = 1.0, channels = 2), rate = 48000, channels = 2)
        val expected = decode(stream, rate = 48000)

        OggOpusDecoder(48000).use { decoder ->
            val buffer = ShortArray(999) // odd, and smaller than a packet: only whole frames may be written
            var actual = ShortArray(0)
            for (chunk in stream.asList().chunked(1000)) {
                decoder.push(chunk.toByteArray())
                while (true) {
                    val count = decoder.read(buffer)
                    if (count == 0) break
                    assertEquals(0, count % 2)
                    actual += buffer.copyOf(count)
                }
            }
            assertArrayEquals(expected, actual)
        }
    }

    @Test
    fun byteOutputIsTheSameAudioAsShortOutput() {
        val stream = encode(sine(rate = 48000, seconds = 0.5, channels = 2), rate = 48000, channels = 2)
        val samples = decode(stream, rate = 48000)
        val expected = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { asShortBuffer().put(samples) }.array()

        OggOpusDecoder(48000).use { assertArrayEquals(expected, it.feedBytes(stream)) }
        OggOpusDecoder(48000).use { decoder ->
            // An odd offset and a length that is not a whole number of frames.
            val buffer = ByteArray(1 + 1001)
            val actual = ByteArrayOutputStream()
            decoder.push(stream)
            while (true) {
                val count = decoder.read(buffer, offset = 1)
                if (count == 0) break
                assertEquals(0, count % 4)
                actual.write(buffer, 1, count)
            }
            assertArrayEquals(expected, actual.toByteArray())
        }
    }

    @Test
    fun outputLayoutStaysTheSameAcrossChainedStreams() {
        val mono = sine(rate = 16000, seconds = 0.5)
        val stereo = sine(rate = 16000, seconds = 0.25, channels = 2)
        val chained = encode(mono, rate = 16000) + encode(stereo, rate = 16000, channels = 2)
        val frames = mono.size + stereo.size / 2

        OggOpusDecoder(16000, channels = 2).use { decoder ->
            assertEquals(2, decoder.channels)
            val decoded = decoder.feed(chained)
            assertEquals(frames * 2, decoded.size)
            // The mono stream comes out on both channels.
            assertTrue((0 until mono.size).all { decoded[2 * it] == decoded[2 * it + 1] })
            assertEquals(2, decoder.head!!.channels)
        }
        OggOpusDecoder(16000, channels = 1).use { assertEquals(frames, it.feed(chained).size) }
        // Left to the stream, the first one decides.
        OggOpusDecoder(16000).use { decoder ->
            assertNull(decoder.channels)
            assertEquals(frames, decoder.feed(chained).size)
            assertEquals(1, decoder.channels)
        }
    }

    @Test
    fun streamThatDoesNotStartAtZeroIsStillTrimmedAtTheEnd() {
        // What a listener gets who joins a live stream part-way: the headers, then pages
        // whose granule positions carry on from wherever the broadcast had got to.
        val pcm = sine(rate = 16000, seconds = 1.0)
        val joined = shiftGranules(encode(pcm, rate = 16000), by = 48000L * 3600)

        assertEquals(pcm.size, decode(joined, rate = 16000).size)
        assertEquals(pcm.size / 2, decode(joined, rate = 8000).size)
    }

    @Test
    fun firstAudioPageArrivesWithinThePageDuration() {
        val frame = ShortArray(320) // 20 ms at 16 kHz
        OggOpusEncoder(16000, pageDurationMs = 80).use { encoder ->
            encoder.headers()
            var writtenMs = 0
            while (encoder.write(frame).isEmpty()) {
                writtenMs += 20
                assertTrue("no audio page after $writtenMs ms of input", writtenMs < 80 + 60)
            }
        }
    }

    @Test
    fun headersAreExposed() {
        val comments = listOf("TITLE" to "Señal", "ARTIST" to "one", "ARTIST" to "two 🎧")
        val stream = OggOpusEncoder(16000, comments = comments).use {
            it.write(sine(rate = 16000, seconds = 0.1)) + it.finish()
        }

        OggOpusDecoder(16000).use { decoder ->
            assertNull(decoder.head)
            assertNull(decoder.tags)
            decoder.feed(stream)

            val head = assertNotNull(decoder.head).let { decoder.head!! }
            assertEquals(1, head.channels)
            assertEquals(16000, head.inputSampleRate)
            assertTrue(head.preSkip > 0)
            val tags = decoder.tags!!
            // The versions come from the submodules' git tags at build time. A checkout
            // without those tags would silently produce "libopus 0, libopusenc unknown".
            assertEquals("libopus 1.6.1, libopusenc 0.3", tags.vendor)
            assertTrue(tags.comments.containsAll(comments))
        }
    }

    @Test
    fun garbageDecodesToNothing() {
        OggOpusDecoder().use { decoder ->
            assertEquals(0, decoder.feed(Random(2).nextBytes(200_000)).size)
            assertEquals(0, decoder.streamCount)
        }
    }

    @Test
    fun truncatedStreamDecodesWhatArrived() {
        val pcm = sine(rate = 16000, seconds = 1.0)
        val stream = encode(pcm, rate = 16000)
        val decoded = decode(stream.copyOf(stream.size / 2), rate = 16000)

        assertTrue(decoded.isNotEmpty() && decoded.size < pcm.size)
    }

    @Test
    fun unsupportedStreamIsRejectedAndTheDecoderRecovers() {
        // An OpusHead declaring 6 channels with channel mapping family 1 (surround).
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 6, 0, 0, 0, 0, 0, 0, 0, 0, 1, 4, 2, 0, 4, 1, 2, 3, 5)
        val surround = page(flags = BOS, serial = 99, sequence = 0, packet = head)
        val pcm = sine(rate = 16000, seconds = 0.2)

        OggOpusDecoder(16000).use { decoder ->
            val error = assertThrows(OggOpusException::class.java) { decoder.feed(surround) }
            assertEquals(-103, error.code)
            assertEquals(pcm.size, decoder.feed(encode(pcm, rate = 16000)).size)
        }
    }

    @Test
    fun codedBandwidthFollowsTheInputRate() {
        // The Opus configuration number, in the top five bits of each packet, encodes the bandwidth.
        fun configurations(rate: Int, maxBandwidth: OpusBandwidth? = null): Set<Int> {
            val noise = Random(3).let { random -> ShortArray(rate) { random.nextInt(-8000, 8000).toShort() } }
            val stream = OggOpusEncoder(rate, bitrate = 64000, maxBandwidth = maxBandwidth)
                .use { it.write(noise) + it.finish() }
            return pages(stream).drop(2).map { (it.body[0].toInt() and 0xFF) shr 3 }.toSet()
        }
        val widebandOrLess = (0..11) + (16..23)
        val fullband = listOf(14, 15) + (28..31)

        assertTrue(widebandOrLess.containsAll(configurations(16000)))
        // Without the default limit the encoder, which runs at 48 kHz, would code the empty upper band.
        assertTrue(fullband.containsAll(configurations(16000, OpusBandwidth.FULLBAND)))
        assertTrue(fullband.containsAll(configurations(48000)))
        assertTrue(widebandOrLess.containsAll(configurations(48000, OpusBandwidth.WIDEBAND)))
    }

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

    @Test
    fun misuseFailsWithExceptions() {
        assertThrows(IllegalArgumentException::class.java) { OggOpusEncoder(16000, channels = 3) }
        assertThrows(IllegalArgumentException::class.java) { OggOpusDecoder(22050) }

        val encoder = OggOpusEncoder(16000, channels = 2)
        assertThrows(IllegalArgumentException::class.java) { encoder.write(ShortArray(3)) }
        encoder.finish()
        assertThrows(OggOpusException::class.java) { encoder.write(ShortArray(4)) }
        encoder.close()
        encoder.close()
        assertThrows(IllegalStateException::class.java) { encoder.write(ShortArray(4)) }

        val decoder = OggOpusDecoder()
        decoder.close()
        decoder.close()
        assertThrows(IllegalStateException::class.java) { decoder.feed(ByteArray(1)) }
    }
}

internal fun sine(rate: Int, seconds: Double, channels: Int = 1, hz: Double = 440.0): ShortArray {
    val frames = (rate * seconds).toInt()
    return ShortArray(frames * channels) { i ->
        (sin(2 * PI * hz * (i / channels) / rate) * 12000).toInt().toShort()
    }
}

/** Encodes in 512-frame writes, the way a capture callback would deliver audio. */
internal fun encode(pcm: ShortArray, rate: Int, channels: Int = 1): ByteArray =
    OggOpusEncoder(rate, channels).use {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < pcm.size) {
            val length = minOf(512 * channels, pcm.size - pos)
            out.write(it.write(pcm, pos, length))
            pos += length
        }
        out.write(it.finish())
        out.toByteArray()
    }

internal fun decode(stream: ByteArray, rate: Int, chunkSize: () -> Int = { stream.size }): ShortArray =
    OggOpusDecoder(rate).use { decoder ->
        var out = ShortArray(0)
        var pos = 0
        while (pos < stream.size) {
            val length = minOf(maxOf(chunkSize(), 1), stream.size - pos)
            out += decoder.feed(stream, pos, length)
            pos += length
        }
        out
    }

internal fun correlation(a: ShortArray, b: ShortArray): Double {
    var dot = 0.0
    var energyA = 0.0
    var energyB = 0.0
    for (i in 0 until minOf(a.size, b.size)) {
        dot += a[i].toDouble() * b[i]
        energyA += a[i].toDouble() * a[i]
        energyB += b[i].toDouble() * b[i]
    }
    return dot / sqrt(energyA * energyB)
}

// A minimal Ogg page reader and writer, independent of the library under test.

internal const val BOS = 0x02
internal const val EOS = 0x04

internal class Page(val flags: Int, val serial: Int, val sequence: Int, val body: ByteArray)

internal fun pages(stream: ByteArray): List<Page> {
    val buffer = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
    val result = ArrayList<Page>()
    var pos = 0
    while (pos < stream.size) {
        check(String(stream, pos, 4, Charsets.US_ASCII) == "OggS") { "no page at offset $pos" }
        val segments = stream[pos + 26].toInt() and 0xFF
        val body = (0 until segments).sumOf { stream[pos + 27 + it].toInt() and 0xFF }
        val bodyStart = pos + 27 + segments
        result += Page(
            stream[pos + 5].toInt(), buffer.getInt(pos + 14), buffer.getInt(pos + 18),
            stream.copyOfRange(bodyStart, bodyStart + body),
        )
        pos = bodyStart + body
    }
    check(pos == stream.size) { "stream ends in the middle of a page" }
    return result
}

/** Builds a page holding one packet shorter than 255 bytes. */
internal fun page(flags: Int, serial: Int, sequence: Int, packet: ByteArray): ByteArray {
    val page = ByteBuffer.allocate(28 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
    page.put("OggS".toByteArray()).put(0).put(flags.toByte())
    page.putLong(0).putInt(serial).putInt(sequence).putInt(0)
    page.put(1).put(packet.size.toByte()).put(packet)
    val bytes = page.array()
    page.putInt(22, crc(bytes, 0, bytes.size))
    return bytes
}

/** The Ogg page checksum of a page whose checksum field is zero. */
internal fun crc(bytes: ByteArray, start: Int, length: Int): Int {
    var crc = 0
    for (i in start until start + length) {
        crc = crc xor ((bytes[i].toInt() and 0xFF) shl 24)
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
    }
    return crc
}

/** Adds [by] to the granule position of every audio page, leaving the two header pages alone. */
internal fun shiftGranules(stream: ByteArray, by: Long): ByteArray {
    val result = stream.copyOf()
    val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 0
    var index = 0
    while (pos < result.size) {
        val segments = result[pos + 26].toInt() and 0xFF
        val length = 27 + segments + (0 until segments).sumOf { result[pos + 27 + it].toInt() and 0xFF }
        val granule = buffer.getLong(pos + 6)
        if (index >= 2 && granule != -1L) {
            buffer.putLong(pos + 6, granule + by)
            buffer.putInt(pos + 22, 0)
            buffer.putInt(pos + 22, crc(result, pos, length))
        }
        pos += length
        index++
    }
    return result
}
