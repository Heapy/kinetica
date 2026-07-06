package bench.jvm

import io.heapy.kinetica.Cell
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.markdown.MdBlock
import io.heapy.kinetica.markdown.markdownBlocks
import io.heapy.kinetica.markdown.parseMarkdown
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.toSafeHtml
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.math.sqrt

/**
 * JVM microbenchmark suite for the parts of Kinetica the browser suite can't isolate:
 * reactive propagation (cells, derived chains, diamonds), render-pipeline Node
 * construction, and markdown SSR throughput. Hand-rolled harness (warmup + timed
 * invocations, medians, a volatile blackhole) — coarser than JMH but plugin-free and
 * fast enough for per-PR regression tracking. Results are only comparable on one
 * machine/JVM, like the browser suite.
 *
 *   ./kotlin run -m bench-jvm                          # all benchmarks
 *   ./kotlin run -m bench-jvm -- --filter=render       # substring filter
 *   ./kotlin run -m bench-jvm -- --warmup=10 --samples=30 --out=custom.json
 */

object Blackhole {
    @Volatile
    var sink: Any? = null
}

class Benchmark(
    val id: String,
    val label: String,
    /** ops per timed invocation; per-op time = invocation time / batch */
    val batch: Int = 1,
    val setup: () -> Unit = {},
    val op: () -> Any?,
)

data class Stats(
    val medianMs: Double,
    val meanMs: Double,
    val stddevMs: Double,
    val minMs: Double,
    val maxMs: Double,
)

fun stats(samples: List<Double>): Stats {
    val sorted = samples.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    val mean = samples.average()
    val stddev = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
    return Stats(median, mean, stddev, sorted.first(), sorted.last())
}

// --- shared fixtures ---

data class Row(val id: Int, val label: String)

private fun buildRows(count: Int, from: Int = 1): List<Row> =
    List(count) { Row(id = from + it, label = "row label ${from + it}") }

// The browser-bench table structure, minus DOM: measures host-DSL walking, Node
// allocation and event registration — the create-op cost that P3 targets.
// @UiComponent enables the IR hoisting/interning transforms (rows: List is unstable, so no
// skippable wrap — which also keeps each-row memoization intact).
@UiComponent
private fun ComponentScope.benchTable(rows: List<Row>) {
    host("div") {
        host("table", props = propsOf("class", "test-data")) {
            host("tbody") {
                each(rows, key = { it.id }) { row ->
                    host("tr", props = propsOf("class", "", "data-id", row.id.toString()), key = row.id) {
                        host("td", props = propsOf("class", "col-id")) {
                            text(row.id.toString(), semantics = null)
                        }
                        host("td", props = propsOf("class", "col-label")) {
                            button(onClick = event { }, semantics = null) {
                                text(row.label, semantics = null)
                            }
                        }
                        host("td", props = propsOf("class", "col-remove")) {
                            button(onClick = event { }, semantics = null) {
                                host("span", props = propsOf("class", "remove-icon", "aria-hidden", "true"))
                            }
                        }
                        host("td", props = propsOf("class", "col-rest"))
                    }
                }
            }
        }
    }
}

private fun findRepoRoot(): Path {
    var dir = Path.of("").toAbsolutePath()
    while (true) {
        if (dir.resolve("project.yaml").exists()) return dir
        dir = dir.parent ?: error("Could not locate the repository root (project.yaml) from the working directory.")
    }
}

fun main(args: Array<String>) {
    val options = args.filter { it.startsWith("--") }.associate {
        val (k, v) = (it.removePrefix("--").split("=", limit = 2) + "true").take(2).let { p -> p[0] to p[1] }
        k to v
    }
    val warmup = options["warmup"]?.toInt() ?: 5
    val samples = options["samples"]?.toInt() ?: 20
    val filter = options["filter"] ?: ""
    val repoRoot = findRepoRoot()
    val outPath = options["out"]?.let(Path::of)
        ?: repoRoot.resolve("bench/results/jvm/results.json")

    // --- fixtures shared between setup and op closures ---

    lateinit var fanoutSource: MutableCell<Int>
    lateinit var fanoutJoin: Cell<Int>

    lateinit var chainSource: MutableCell<Int>
    lateinit var chainTail: Cell<Int>

    lateinit var chain2kSource: MutableCell<Int>
    lateinit var chain2kTail: Cell<Int>

    lateinit var diamondSource: MutableCell<Int>
    lateinit var diamondJoin: Cell<Int>

    val rows1k = buildRows(1000)
    lateinit var updRuntime: KineticaRuntime
    lateinit var updScope: ComponentScope
    lateinit var updRows: MutableCell<List<Row>>
    var updTick = 0

    val docsDir = repoRoot.resolve("docs/docs-site/resources/docs")
    val docsSource = docsDir.listDirectoryEntries()
        .filter { it.extension == "md" }
        .sorted()
        .joinToString("\n\n") { Files.readString(it) }
    lateinit var docsBlocks: List<MdBlock>

    val benchmarks = listOf(
        Benchmark(
            id = "derived_fanout_10k",
            label = "write + recompute through 10,000-wide derived fan",
            batch = 5,
            setup = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                fanoutSource = store(0)
                runtime.render(scope) {
                    val mids = List(10_000) { index ->
                        derived { fanoutSource.value + index }
                    }
                    fanoutJoin = derived { mids.sumOf { it.value } }
                    fanoutJoin.value
                }
                fanoutSource.value = 1
                val expected = 10_000 + (0 until 10_000).sum()
                check(fanoutJoin.value == expected) { "fanout sanity: expected $expected, got ${fanoutJoin.value}" }
            },
            op = {
                repeat(5) {
                    fanoutSource.value = fanoutSource.value + 1
                    Blackhole.sink = fanoutJoin.value
                }
                fanoutJoin.value
            },
        ),
        Benchmark(
            id = "derived_fanout_cached_read",
            label = "cached read of a join over 10,000 unchanged dependencies",
            batch = 100,
            setup = {
                // reuses the graph built by derived_fanout_10k's setup ordering: rebuilt
                // here so the benchmark stands alone under --filter
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                fanoutSource = store(0)
                runtime.render(scope) {
                    val mids = List(10_000) { index ->
                        derived { fanoutSource.value + index }
                    }
                    fanoutJoin = derived { mids.sumOf { it.value } }
                    fanoutJoin.value
                }
            },
            op = {
                repeat(100) { Blackhole.sink = fanoutJoin.value }
                fanoutJoin.value
            },
        ),
        Benchmark(
            id = "derived_chain_lazy_1k",
            label = "write + lazy read through 1,000-deep derived chain",
            batch = 10,
            setup = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                chainSource = store(0)
                runtime.render(scope) {
                    var prev: Cell<Int> = chainSource
                    repeat(1_000) {
                        val upstream = prev
                        prev = derived { upstream.value + 1 }
                    }
                    chainTail = prev
                    chainTail.value
                }
                chainSource.value = 1
                check(chainTail.value == 1_001) { "chain sanity: expected 1001, got ${chainTail.value}" }
            },
            op = {
                repeat(10) {
                    chainSource.value = chainSource.value + 1
                    Blackhole.sink = chainTail.value
                }
                chainTail.value
            },
        ),
        Benchmark(
            id = "derived_chain_lazy_2k",
            label = "write + lazy read through 2,000-deep derived chain (complexity probe: ~2x the 1k cost = linear)",
            batch = 10,
            setup = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                chain2kSource = store(0)
                runtime.render(scope) {
                    var prev: Cell<Int> = chain2kSource
                    repeat(2_000) {
                        val upstream = prev
                        prev = derived { upstream.value + 1 }
                    }
                    chain2kTail = prev
                    chain2kTail.value
                }
                chain2kSource.value = 1
                check(chain2kTail.value == 2_001) { "chain2k sanity: expected 2001, got ${chain2kTail.value}" }
            },
            op = {
                repeat(10) {
                    chain2kSource.value = chain2kSource.value + 1
                    Blackhole.sink = chain2kTail.value
                }
                chain2kTail.value
            },
        ),
        Benchmark(
            id = "derived_diamond_100",
            label = "write + recompute through 100-wide diamond",
            batch = 10,
            setup = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                diamondSource = store(0)
                runtime.render(scope) {
                    val mids = List(100) {
                        derived { diamondSource.value + it }
                    }
                    diamondJoin = derived { mids.sumOf { it.value } }
                    diamondJoin.value
                }
                diamondSource.value = 1
                check(diamondJoin.value == 100 + (0 until 100).sum()) {
                    "diamond sanity: unexpected join value ${diamondJoin.value}"
                }
            },
            op = {
                repeat(10) {
                    diamondSource.value = diamondSource.value + 1
                    Blackhole.sink = diamondJoin.value
                }
                diamondJoin.value
            },
        ),
        Benchmark(
            id = "render_create_1k",
            label = "render 1,000-row table Node tree (fresh runtime)",
            op = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                runtime.render(scope) { benchTable(rows1k) }.tree
            },
        ),
        Benchmark(
            id = "render_update_1k",
            label = "re-render 1,000-row table after updating every 10th row",
            setup = {
                updRuntime = KineticaRuntime(debug = false)
                updScope = ComponentScope(updRuntime)
                updRows = store(buildRows(1000, from = 1_000_000))
                updTick = 0
                updRuntime.render(updScope) { benchTable(updRows.value) }
            },
            op = {
                updTick++
                updRows.value = updRows.value.mapIndexed { index, row ->
                    if (index % 10 == 0) row.copy(label = row.label.substringBefore(" !") + " !$updTick") else row
                }
                updRuntime.render(updScope) { benchTable(updRows.value) }.tree
            },
        ),
        Benchmark(
            id = "markdown_parse_docs",
            label = "parse all docs-site markdown (${docsSource.length / 1024} KB)",
            op = { parseMarkdown(docsSource) },
        ),
        Benchmark(
            id = "ssr_render_docs",
            label = "SSR: render parsed docs markdown to HTML string",
            setup = {
                docsBlocks = parseMarkdown(docsSource)
                check(docsBlocks.isNotEmpty()) { "ssr sanity: no markdown blocks parsed" }
            },
            op = {
                val runtime = KineticaRuntime(debug = false)
                val scope = ComponentScope(runtime)
                runtime.render(scope) { markdownBlocks(docsBlocks) }.tree.toSafeHtml()
            },
        ),
    ).filter { filter.isEmpty() || it.id.contains(filter) }

    println("bench-jvm: ${benchmarks.size} benchmarks, $warmup warmup + $samples samples each")
    println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n")

    val results = linkedMapOf<String, Pair<Benchmark, Stats>>()
    for (bench in benchmarks) {
        bench.setup()
        repeat(warmup) { Blackhole.sink = bench.op() }
        val timings = ArrayList<Double>(samples)
        repeat(samples) {
            val start = System.nanoTime()
            Blackhole.sink = bench.op()
            timings.add((System.nanoTime() - start) / 1e6 / bench.batch)
        }
        val s = stats(timings)
        results[bench.id] = bench to s
        val opsPerSec = 1000.0 / s.medianMs
        println(
            "  %-24s median %8.3f ms  (mean %8.3f ± %6.3f, %,.0f ops/s)  %s"
                .format(bench.id, s.medianMs, s.meanMs, s.stddevMs, opsPerSec, bench.label),
        )
    }

    val json = buildJsonObject {
        putJsonObject("meta") {
            put("date", java.time.Instant.now().toString())
            put("jvm", System.getProperty("java.version"))
            put("os", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            put("warmup", warmup)
            put("samples", samples)
        }
        putJsonObject("benchmarks") {
            for ((id, entry) in results) {
                val (bench, s) = entry
                putJsonObject(id) {
                    put("label", bench.label)
                    put("batch", bench.batch)
                    put("medianMs", s.medianMs)
                    put("meanMs", s.meanMs)
                    put("stddevMs", s.stddevMs)
                    put("minMs", s.minMs)
                    put("maxMs", s.maxMs)
                    put("opsPerSec", 1000.0 / s.medianMs)
                }
            }
        }
    }
    Files.createDirectories(outPath.parent)
    Files.writeString(outPath, json.toString())
    println("\nresults written to $outPath")
    Blackhole.sink = JsonPrimitive("done")
}
