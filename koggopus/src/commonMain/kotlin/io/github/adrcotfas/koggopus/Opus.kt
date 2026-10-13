package io.github.adrcotfas.koggopus

/** What the encoder should optimise for. */
public enum class OpusApplication(internal val value: Int) {
    /** Speech; favours intelligibility. */
    VOIP(2048),

    /** Music and general audio; favours faithfulness to the input. */
    AUDIO(2049),

    /** Lowest algorithmic delay, at the cost of speech-specific coding modes. */
    RESTRICTED_LOWDELAY(2051),
}

/** Audio bandwidth, used to cap what the encoder spends bits on. */
public enum class OpusBandwidth(internal val value: Int) {
    /** 4 kHz passband. */
    NARROWBAND(1101),

    /** 6 kHz passband. */
    MEDIUMBAND(1102),

    /** 8 kHz passband. */
    WIDEBAND(1103),

    /** 12 kHz passband. */
    SUPERWIDEBAND(1104),

    /** 20 kHz passband. */
    FULLBAND(1105),
}

/**
 * The identification header of an Ogg Opus stream.
 *
 * @property channels 1 for mono, 2 for stereo. This describes the stream; the decoded audio
 * has [OggOpusDecoder.channels] channels.
 * @property preSkip samples at 48 kHz that the decoder drops from the start of the stream.
 * @property inputSampleRate sample rate of the original audio in Hz. Informational only.
 * @property outputGain gain in Q7.8 dB that the decoder has already applied.
 */
public data class OpusHead(
    val channels: Int,
    val preSkip: Int,
    val inputSampleRate: Int,
    val outputGain: Int,
)

/**
 * The comment header of an Ogg Opus stream.
 *
 * @property vendor the encoder's identification string.
 * @property comments tag and value pairs, in stream order. Tags may repeat.
 */
public data class OpusTags(
    val vendor: String,
    val comments: List<Pair<String, String>>,
)

/**
 * Thrown when the native codec or container layer reports a failure.
 *
 * @property code the native error code.
 */
public class OggOpusException(public val code: Int, message: String) : RuntimeException(message)

/** Parses an OpusTags packet (RFC 7845 section 5.2). Returns null if it is malformed. */
internal fun parseOpusTags(packet: ByteArray): OpusTags? {
    var pos = 8 // "OpusTags"
    fun readLength(): Int? {
        if (pos + 4 > packet.size) return null
        val value = (packet[pos].toInt() and 0xFF) or
            ((packet[pos + 1].toInt() and 0xFF) shl 8) or
            ((packet[pos + 2].toInt() and 0xFF) shl 16) or
            ((packet[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return value.takeIf { it >= 0 && it <= packet.size - pos }
    }

    fun readString(): String? {
        val length = readLength() ?: return null
        return packet.decodeToString(pos, pos + length).also { pos += length }
    }

    if (packet.size < 8) return null
    val vendor = readString() ?: return null
    val count = readLength() ?: return null
    val comments = ArrayList<Pair<String, String>>()
    repeat(count) {
        val comment = readString() ?: return OpusTags(vendor, comments)
        val separator = comment.indexOf('=')
        if (separator > 0) comments += comment.substring(0, separator) to comment.substring(separator + 1)
    }
    return OpusTags(vendor, comments)
}
