package io.github.adrcotfas.koggopus

import platform.Foundation.NSLock

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
