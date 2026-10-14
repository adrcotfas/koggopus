package io.github.adrcotfas.koggopus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Checks the library against the reference tools from opus-tools (opusenc, opusdec, opusinfo).
 * Skipped when they are not installed.
 */
class InteropTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun referenceToolsAcceptOurStream() {
        val pcm = sine(rate = 16000, seconds = 1.0)
        val ours = temp.newFile("ours.opus").apply { writeBytes(encode(pcm, rate = 16000)) }

        val info = run("opusinfo", ours.path)
        assertFalse(info, info.contains("WARNING", ignoreCase = true))
        assertTrue(info, info.contains("Playback length: 0m:01.000s"))

        val raw = temp.newFile("ours.raw")
        run("opusdec", "--quiet", "--rate", "48000", "--no-dither", ours.path, raw.path)
        assertNear("ours", readPcm(raw), decode(ours.readBytes(), rate = 48000))
    }

    @Test
    fun weDecodeTheReferenceEncodersStreams() {
        // Encoder settings that change how packets and pages are laid out.
        val variants = listOf(
            Variant("defaults"),
            Variant("60 ms frames", "--framesize", "60"),
            Variant("2.5 ms frames", "--framesize", "2.5"),
            Variant("one packet per page", "--max-delay", "0"),
            Variant("constant bitrate", "--hard-cbr", "--bitrate", "32"),
            Variant("speech at low bitrate", "--speech", "--bitrate", "12"),
            // 640-byte packets, which need more than one lacing value each.
            Variant("large packets, stereo", "--hard-cbr", "--bitrate", "256", rate = 48000, channels = 2),
            Variant("44.1 kHz input", rate = 44100, channels = 2),
            // A comment header too big for one page, so it continues onto the next.
            Variant("header spanning pages", "--padding", "70000"),
        )
        for (variant in variants) {
            val pcm = sine(variant.rate, seconds = 1.0, channels = variant.channels)
            val input = temp.newFile().apply { writePcm(pcm) }
            val reference = temp.newFile()
            run(
                "opusenc", "--quiet", "--raw", "--raw-rate", "${variant.rate}", "--raw-chan", "${variant.channels}",
                *variant.options, input.path, reference.path,
            )
            val raw = temp.newFile()
            run("opusdec", "--quiet", "--rate", "48000", "--no-dither", reference.path, raw.path)

            val expected = readPcm(raw)
            assertEquals(variant.name, 48000 * variant.channels, expected.size)
            OggOpusDecoder(48000).use { decoder ->
                val actual = reference.readBytes().asList().chunked(333)
                    .fold(ShortArray(0)) { out, chunk -> out + decoder.feed(chunk.toByteArray()) }
                assertNear(variant.name, expected, actual)
                assertEquals(variant.name, variant.channels, decoder.head!!.channels)
                assertTrue(variant.name, decoder.tags!!.vendor.isNotEmpty())
            }
        }
    }

    private class Variant(val name: String, vararg val options: String, val rate: Int = 16000, val channels: Int = 1)

    /** Same length, and samples within rounding of each other. */
    private fun assertNear(name: String, expected: ShortArray, actual: ShortArray) {
        assertEquals(name, expected.size, actual.size)
        val worst = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        assertTrue("$name: largest sample difference is $worst", worst <= 2)
    }

    private fun run(tool: String, vararg args: String): String {
        val executable = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, tool) }.firstOrNull { it.canExecute() }
        assumeTrue("$tool is not installed", executable != null)
        val process = ProcessBuilder(listOf(executable!!.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    private fun File.writePcm(pcm: ShortArray) =
        writeBytes(ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array())

    private fun readPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }
}
