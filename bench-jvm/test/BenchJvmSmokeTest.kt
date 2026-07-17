package bench.jvm

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchJvmSmokeTest {
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
