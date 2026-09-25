package io.heapy.kinetica.samples.harmon

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Subset of Harmon's v2/v3 wire payload consumed by this app. */
@Serializable
data class LivePayload(
    val schemaVersion: Int,
    val sequence: String,
    val status: String,
    val capturedAt: String? = null,
    val sampleIntervalSeconds: Double? = null,
    val retrySeconds: Double? = null,
    val processTree: LiveProcessTree? = null,
    val alerts: List<LiveAlert> = emptyList(),
    val attributionWarning: String? = null,
) {
    val pollMillis: Long get() = ((retrySeconds ?: sampleIntervalSeconds ?: 1.0)
        .takeIf { it.isFinite() && it > 0.0 } ?: 1.0).coerceIn(0.25, 15.0).times(1_000).toLong()

    fun snapshot(): ProcessSnapshot? {
        val tree = processTree ?: return null
        val keys = HashSet<String>()
        fun convert(node: LiveProcess, depth: Int): ProcessRecord {
            require(depth <= 256 && keys.size < 100_000) { "Process tree exceeds client limits" }
            require(node.key.isNotBlank() && keys.add(node.key)) { "Invalid process identity" }
            val fields = mapOf(
                Metric.Cpu to ("cpuPercent" to 1.0),
                Metric.Memory to ("physicalFootprintBytes" to 1_048_576.0),
                Metric.Read to ("diskReadBytesPerSecond" to 1_024.0),
                Metric.Write to ("diskWriteBytesPerSecond" to 1_024.0),
                Metric.Wakeups to ("wakeupsPerSecond" to 1.0),
                Metric.Threads to ("threadCount" to 1.0),
                Metric.Power to ("energyWatts" to 1.0),
            )
            fun readings(total: Boolean) = fields.mapValues { (_, field) ->
                val wire = node.metrics[field.first]
                val available = if (total) wire?.totalAvailable == true else wire?.selfAvailable == true
                val raw = if (total) wire?.total else wire?.self
                val number = if (available) raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } else null
                Measurement(number?.div(field.second), total && wire?.totalPartial == true)
            }
            return ProcessRecord(node.key, node.pid, node.name, readings(false),
                children = node.children.map { convert(it, depth + 1) },
                alert = alerts.filter { node.pid in it.pids }.joinToString("; ") { it.title }.ifEmpty { null },
                totals = readings(true))
        }
        return ProcessSnapshot(
            sequence = 0,
            roots = tree.roots.map { convert(it, 0) },
            status = when (status) { "READY" -> SampleStatus.Ready; "WARMING" -> SampleStatus.Warming; else -> SampleStatus.Stale },
            message = (alerts.map { it.title } + listOfNotNull(attributionWarning)).joinToString(" · ").ifEmpty { null },
            sourceSequence = sequence,
            capturedAt = capturedAt ?: tree.capturedAt,
            totalProcessCount = tree.totalProcessCount,
            inaccessibleProcessCount = tree.inaccessibleProcessCount,
        )
    }
}

@Serializable
data class LiveProcessTree(
    val capturedAt: String,
    val totalProcessCount: Int,
    val inaccessibleProcessCount: Int,
    val roots: List<LiveProcess>,
)

@Serializable
data class LiveProcess(
    val key: String,
    val pid: Int,
    val name: String,
    val metrics: Map<String, LiveMetric>,
    val children: List<LiveProcess>,
)

@Serializable
data class LiveMetric(
    val self: String? = null,
    val total: String? = null,
    val selfAvailable: Boolean,
    val totalAvailable: Boolean,
    val totalPartial: Boolean,
)

@Serializable
data class LiveAlert(val title: String, val pids: List<Int> = emptyList())

private val liveJson = Json { ignoreUnknownKeys = true }

fun decodeLivePayload(text: String): LivePayload {
    try {
        val root = liveJson.parseToJsonElement(text)
        val schema = root.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (schema !in setOf(2, 3)) throw LiveFailure(ConnectionStatus.Incompatible, "Unsupported Harmon data format. Update Harmon or this app.")
        val payload = liveJson.decodeFromJsonElement(LivePayload.serializer(), root)
        require(payload.status in setOf("READY", "WARMING", "STALE"))
        require(payload.status != "READY" || payload.processTree != null)
        return payload
    } catch (failure: LiveFailure) {
        throw failure
    } catch (_: SerializationException) {
        throw LiveFailure(ConnectionStatus.Disconnected, "Harmon returned an invalid response. Retrying…")
    } catch (_: IllegalArgumentException) {
        throw LiveFailure(ConnectionStatus.Disconnected, "Harmon returned an invalid response. Retrying…")
    }
}
