package io.heapy.kinetica

internal expect fun platformLock(): Any?

internal expect inline fun <R> synchronizedOn(lock: Any?, block: () -> R): R
