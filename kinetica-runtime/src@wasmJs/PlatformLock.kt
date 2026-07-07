package io.heapy.kinetica

internal actual fun platformLock(): Any? = null

internal actual inline fun <R> synchronizedOn(lock: Any?, block: () -> R): R = block()
