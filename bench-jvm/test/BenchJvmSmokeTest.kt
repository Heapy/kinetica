package bench.jvm

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class BenchJvmSmokeTest {
    @Test
    fun rowSizesRunAllFourScenariosWithoutTheDefaultSuite() {
        val out = createTempFile(prefix = "kinetica-row-bench", suffix = ".json")
        try {
            main(arrayOf("--row-sizes=10,20", "--filter=row_", "--warmup=1", "--samples=2", "--out=$out"))
            val benchmarks = Json.parseToJsonElement(out.readText()).jsonObject.getValue("benchmarks").jsonObject
            val expected = listOf(10, 20).flatMap { size ->
                listOf("row_memo_hit", "row_update10th", "row_no_memo_hit", "row_no_memo_update10th")
                    .map { "${it}_$size" }
            }.toSet()
            assertEquals(expected, benchmarks.keys)
        } finally {
            out.deleteIfExists()
        }
    }

    @Test
    fun rowSizesAcceptSuffixesAndRejectInvalidOrOverflowingCounts() {
        assertEquals(10_000, parseRowCount(" 10K "))
        assertEquals(1_000_000, parseRowCount("1m"))
        assertEquals(Int.MAX_VALUE, parseRowCount(Int.MAX_VALUE.toString()))
        for (invalid in listOf("", "0", "-1", "1.5k", "1x", "2147483648", "2147484k", "999999999999999999999m")) {
            assertFailsWith<IllegalArgumentException>(invalid) { parseRowCount(invalid) }
        }
    }

    @Test
    fun runWritesBenchmarkJson() {
        val out = createTempFile(prefix = "kinetica-jvm-bench", suffix = ".json")
        try {
            main(
                arrayOf(
                    "--warmup=0",
                    "--samples=1",
                    "--out=$out",
                ),
            )

            val json = out.readText()
            assertTrue("\"meta\"" in json)
            assertTrue("\"derived_fanout_10k\"" in json)
            assertTrue("\"derived_chain_lazy_500\"" in json)
            assertTrue("\"render_create_1k\"" in json)
            assertTrue("\"markdown_parse_docs\"" in json)
            assertTrue("\"opsPerSec\"" in json)
            assertFalse("\"row_memo_hit_" in json)
        } finally {
            out.deleteIfExists()
        }
    }

    @Test
    fun commaSeparatedFilterRunsAllRequestedBenchmarks() {
        val out = createTempFile(prefix = "kinetica-jvm-bench-filter", suffix = ".json")
        try {
            main(
                arrayOf(
                    "--warmup=0",
                    "--samples=1",
                    "--filter=render_create_1k,markdown_parse_docs",
                    "--out=$out",
                ),
            )

            val json = out.readText()
            assertTrue("\"render_create_1k\"" in json)
            assertTrue("\"markdown_parse_docs\"" in json)
            assertFalse("\"derived_fanout_10k\"" in json)
        } finally {
            out.deleteIfExists()
        }
    }
}
