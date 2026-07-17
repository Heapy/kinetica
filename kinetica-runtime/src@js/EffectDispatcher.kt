package io.heapy.kinetica

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The single-threaded event loop already starts coroutines in FIFO dispatch order;
// skipping limitedParallelism keeps LimitedDispatcher out of the bundle.
internal actual fun serialEffectDispatcher(): CoroutineDispatcher = Dispatchers.Default
