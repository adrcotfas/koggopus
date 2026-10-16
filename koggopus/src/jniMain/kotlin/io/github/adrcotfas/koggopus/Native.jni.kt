package io.github.adrcotfas.koggopus

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The functions of native/jni/koggopus_jni.c. Handles are pointers to the C objects. */
internal object Jni {
    init {
        try {
            System.loadLibrary("koggopus")
        } catch (notFound: UnsatisfiedLinkError) {
            loadFromJar(notFound)
        }
    }

    /**
     * Desktop JVM: the jar holds a library for each supported OS and architecture. The JVM
     * can only load one from a file, so it is copied out first.
     */
    private fun loadFromJar(notFound: UnsatisfiedLinkError) {
        val name = System.mapLibraryName("koggopus")
        val platform = jvmPlatform()
        val input = Jni::class.java.getResourceAsStream("/io/github/adrcotfas/koggopus/native/$platform/$name")
            ?: throw UnsatisfiedLinkError("koggopus has no native library for $platform").apply { addSuppressed(notFound) }
        // Windows cannot delete a loaded library, so its copies go in a directory of their
        // own, and each process deletes the ones that earlier processes left. A copy that is
        // still loaded cannot be deleted; a recent one may be about to load.
        val windows = platform.startsWith("windows")
        val dir = if (!windows) null else File(System.getProperty("java.io.tmpdir"), "koggopus").apply {
            mkdirs()
            val stale = System.currentTimeMillis() - 60_000
            listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        }
        // A new file per process, so concurrent processes and library versions never clash.
        val file = File.createTempFile("koggopus", name, dir)
        try {
            input.use { from -> file.outputStream().use { from.copyTo(it) } }
            System.load(file.path)
        } finally {
            // Elsewhere the loaded library no longer needs its file, even if the JVM is killed.
            if (!windows) file.delete()
        }
    }

    /** The names of the jar's native directories, such as linux-x64 or darwin-arm64. */
    private fun jvmPlatform(): String {
        val os = System.getProperty("os.name")!!.lowercase()
        val arch = System.getProperty("os.arch")!!.lowercase()
        return when {
            os.startsWith("mac") -> "darwin"
            os.startsWith("linux") -> "linux"
            os.startsWith("windows") -> "windows"
            else -> os
        } + "-" + when (arch) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> arch
        }
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
    external fun decoderFeedBytes(handle: Long, data: ByteArray, offset: Int, length: Int): ByteArray
    external fun decoderPush(handle: Long, data: ByteArray, offset: Int, length: Int)
    external fun decoderRead(handle: Long, pcm: ShortArray, offset: Int, length: Int): Int
    external fun decoderReadBytes(handle: Long, pcm: ByteArray, offset: Int, length: Int): Int
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
    override fun feedBytes(data: ByteArray, offset: Int, length: Int) = Jni.decoderFeedBytes(handle, data, offset, length)
    override fun readBytes(pcm: ByteArray, offset: Int, length: Int) = Jni.decoderReadBytes(handle, pcm, offset, length)
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
