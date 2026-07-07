package io.heapy.kinetica

internal actual fun platformLock(): Any? = Any()

internal actual inline fun <R> synchronizedOn(lock: Any?, block: () -> R): R =
    kotlin.synchronized(lock ?: error("Platform lock is missing."), block)
