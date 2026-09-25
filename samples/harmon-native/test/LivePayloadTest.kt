package io.heapy.kinetica.samples.harmon

import kotlin.test.*

class LivePayloadTest {
    @Test
    fun versionsTwoAndThreeKeepServerTotalsAvailabilityAndStableIdentity() {
        for (version in listOf(2, 3)) {
            val payload = decodeLivePayload(wireFixture(version))
            val snapshot = requireNotNull(payload.snapshot())
            assertEquals("18446744073709551615", snapshot.sourceSequence)
            val process = snapshot.roots.single()
            assertEquals("process:42:123456789012345", process.key)
            assertEquals(1.0, process.metrics.getValue(Metric.Memory).value)
            assertEquals(2.0, process.metrics.getValue(Metric.Read).value)
            assertNull(process.metrics.getValue(Metric.Power).value)
            assertNull(process.totals!!.getValue(Metric.Power).value)
            val projection = projectMonitor(MonitorState(snapshot, query = "parent"))
            assertEquals("≥ 99.0%", projection.table.rows.single().cells.getValue("Cpu.total").text)
            assertEquals("1.0%", projection.table.rows.single().cells.getValue("Cpu.self").text)
            assertEquals(10, projection.processCount)
            assertEquals(if (version == 3) "High CPU" else null, process.alert)
        }
    }

    @Test
    fun invalidSchemaPayloadAndMetricNumbersCannotBecomePlausibleReadings() {
        assertEquals(ConnectionStatus.Incompatible, assertFailsWith<LiveFailure> { decodeLivePayload(wireFixture(99)) }.status)
        assertFailsWith<LiveFailure> { decodeLivePayload("{}") }
        assertFailsWith<LiveFailure> { decodeLivePayload("{") }
        assertFailsWith<LiveFailure> { decodeLivePayload(wireFixture().replace("READY", "FUTURE_STATE")) }
        assertFailsWith<LiveFailure> { decodeLivePayload("""{"schemaVersion":3,"sequence":"1","status":"READY"}""") }
        val invalid = wireFixture().replace("\"self\":\"1.0\"", "\"self\":\"NaN\"")
        assertNull(decodeLivePayload(invalid).snapshot()!!.roots.single().metrics.getValue(Metric.Cpu).value)
    }

    @Test
    fun warmingWithoutTreeAndPollingCadenceAreExplicit() {
        val warming = decodeLivePayload("""{"schemaVersion":3,"sequence":"0","status":"WARMING","retrySeconds":2.5}""")
        assertNull(warming.snapshot())
        assertEquals(2_500, warming.pollMillis)
        assertEquals(250, warming.copy(retrySeconds = 0.01).pollMillis)
        assertEquals(1_000, warming.copy(retrySeconds = Double.NaN).pollMillis)
        assertEquals(15_000, warming.copy(retrySeconds = 1e99).pollMillis)
    }

    @Test
    fun endpointAcceptsOnlyTheLocalDescriptorAndNeverPrintsItsToken() {
        val token = "a".repeat(64)
        val endpoint = LocalEndpoint.parse("port=12345\ntoken=$token\n")
        assertEquals("http://127.0.0.1:12345/api/live?watch=1", endpoint.watchUrl)
        assertEquals("Bearer $token", endpoint.authorization())
        assertFalse(endpoint.toString().contains(token))
        for (invalid in listOf("port=0\ntoken=$token", "port=1\ntoken=bad", "port=1\ntoken=$token\nhost=example.com", "port=1\nport=2\ntoken=$token")) {
            val error = assertFailsWith<IllegalArgumentException> { LocalEndpoint.parse(invalid) }
            assertFalse(error.message.orEmpty().contains(token))
        }
    }
}

fun wireFixture(version: Int = 3): String = """
{
  "schemaVersion":$version,"sequence":"18446744073709551615","status":"READY",
  "sampleIntervalSeconds":1.0,"capturedAt":"2026-09-25T18:00:00Z",
  "alerts":[{"title":"High CPU"${if (version == 3) ",\"category\":\"cpu\",\"pids\":[42]" else ""}}],
  "processTree":{"capturedAt":"2026-09-25T18:00:00Z","totalProcessCount":10,"inaccessibleProcessCount":2,"roots":[
    {"key":"process:42:123456789012345","pid":42,"name":"parent","children":[],"metrics":{
      "cpuPercent":{"self":"1.0","total":"99.0","selfAvailable":true,"totalAvailable":true,"totalPartial":true},
      "physicalFootprintBytes":{"self":"1048576","total":"2097152","selfAvailable":true,"totalAvailable":true,"totalPartial":false},
      "diskReadBytesPerSecond":{"self":"2048","total":"4096","selfAvailable":true,"totalAvailable":true,"totalPartial":false},
      "energyWatts":{"self":"0","total":"0","selfAvailable":false,"totalAvailable":false,"totalPartial":false}
    }}
  ]}
}
""".trimIndent()
