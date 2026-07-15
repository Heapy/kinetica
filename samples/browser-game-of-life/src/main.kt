package app.browser.gameoflife

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.browser.BrowserKineticaApp
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.watch
import kotlinx.coroutines.delay

private const val BoardColumns = 72
private const val BoardRows = 48

private enum class SimulationSpeed(
    val label: String,
    val delayMillis: Long,
) {
    SLOW("Slow", 420),
    NORMAL("Normal", 180),
    FAST("Fast", 80),
}

private data class CellView(
    val index: Int,
    val point: GridPoint,
    val alive: Boolean,
)

@UiComponent
fun ComponentScope.GameOfLifeApp(requestRender: () -> Unit = {}) {
    var board by state { LifeBoard(BoardColumns, BoardRows).load(LifePreset.PULSAR) }
    var running by state { false }
    var speed by state { SimulationSpeed.NORMAL }
    var selectedPreset: LifePreset? by state { LifePreset.PULSAR }

    val population by derived { board.population }
    val cells by derived {
        List(BoardColumns * BoardRows) { index ->
            val point = GridPoint(
                column = index % BoardColumns,
                row = index / BoardColumns,
            )
            CellView(index = index, point = point, alive = board[point.column, point.row])
        }
    }

    watch(source = { running to speed.delayMillis }) { (isRunning, tickDelay) ->
        if (isRunning) {
            while (true) {
                delay(tickDelay)
                val next = board.step()
                board = next
                if (next.population == 0) {
                    running = false
                }
                requestRender()
                if (next.population == 0) break
            }
        }
    }

    val toggleRunning = event {
        if (population > 0) {
            running = !running
        }
    }
    val stepOnce = event {
        running = false
        board = board.step()
    }
    val randomize = event {
        running = false
        selectedPreset = null
        board = board.randomized()
    }
    val clear = event {
        running = false
        selectedPreset = null
        board = board.clear()
    }

    host("div", props = propsOf("class", "life-app"), semantics = Semantics(testTag = "game-of-life-app")) {
        host("header", props = propsOf("class", "hero")) {
            host("div", props = propsOf("class", "brand-line")) {
                host("span", props = propsOf("class", "brand-mark", "aria-hidden", "true")) {
                    text("K", semantics = null)
                }
                host("span", props = propsOf("class", "eyebrow")) {
                    text("Kinetica playground", semantics = null)
                }
                host("span", props = propsOf("class", "rule-chip")) {
                    text("B3 / S23", semantics = null)
                }
            }
            host("div", props = propsOf("class", "hero-copy")) {
                host("h1") { text("Conway’s Game of Life", semantics = null) }
                host("p") {
                    text(
                        "Seed a world, set it in motion, and watch four simple rules create complex behavior.",
                        semantics = null,
                    )
                }
            }
        }

        host("div", props = propsOf("class", "workspace")) {
            host("aside", props = propsOf("class", "preset-panel", "aria-label", "Pattern presets")) {
                host("div", props = propsOf("class", "section-heading")) {
                    host("span", props = propsOf("class", "section-kicker")) {
                        text("Start here", semantics = null)
                    }
                    host("h2") { text("Classic patterns", semantics = null) }
                    host("p") {
                        text("Load a known form, then run it or reshape it cell by cell.", semantics = null)
                    }
                }

                host("div", props = propsOf("class", "preset-list")) {
                    each(LifePreset.entries, key = { it.id }) { preset ->
                        val selected = preset == selectedPreset
                        host(
                            "div",
                            props = propsOf("class", if (selected) "preset-option is-selected" else "preset-option"),
                            key = preset.id,
                        ) {
                            button(
                                onClick = event {
                                    running = false
                                    selectedPreset = preset
                                    board = board.load(preset)
                                    centerBoardViewport()
                                },
                                semantics = Semantics(
                                    role = Role.Button,
                                    label = "Load ${preset.displayName}",
                                    stateDescription = if (selected) "Selected preset" else null,
                                    testTag = "preset-${preset.id}",
                                    focusable = true,
                                ),
                            ) {
                                host("span", props = propsOf("class", "preset-title")) {
                                    text(preset.displayName, semantics = null)
                                    host("span", props = propsOf("class", "preset-size")) {
                                        text("${preset.columns} × ${preset.rows}", semantics = null)
                                    }
                                }
                                host("span", props = propsOf("class", "preset-category")) {
                                    text(preset.category, semantics = null)
                                }
                                host("span", props = propsOf("class", "preset-description")) {
                                    text(preset.description, semantics = null)
                                }
                            }
                        }
                    }
                }

                host("div", props = propsOf("class", "tip-card")) {
                    host("span", props = propsOf("class", "tip-icon", "aria-hidden", "true")) {
                        text("✦", semantics = null)
                    }
                    host("p") {
                        text("Tip: select any square on the board to toggle it. Editing pauses the simulation.", semantics = null)
                    }
                }
            }

            host("main", props = propsOf("class", "simulation-panel")) {
                host("div", props = propsOf("class", "control-bar")) {
                    host("div", props = propsOf("class", "primary-actions")) {
                        button(
                            onClick = toggleRunning,
                            enabled = population > 0,
                            semantics = Semantics(
                                role = Role.Button,
                                label = if (running) "Pause simulation" else "Run simulation",
                                testTag = "toggle-running",
                                focusable = true,
                            ),
                        ) {
                            host("span", props = propsOf("aria-hidden", "true", "class", "button-icon")) {
                                text(if (running) "Ⅱ" else "▶", semantics = null)
                            }
                            text(if (running) "Pause" else "Run", semantics = null)
                        }
                        button(
                            onClick = stepOnce,
                            semantics = Semantics(
                                role = Role.Button,
                                label = "Advance one generation",
                                testTag = "step",
                                focusable = true,
                            ),
                        ) {
                            host("span", props = propsOf("aria-hidden", "true", "class", "button-icon")) {
                                text("↦", semantics = null)
                            }
                            text("Step", semantics = null)
                        }
                    }

                    host("div", props = propsOf("class", "secondary-actions")) {
                        button(
                            onClick = randomize,
                            semantics = Semantics(role = Role.Button, testTag = "randomize", focusable = true),
                        ) {
                            text("Randomize", semantics = null)
                        }
                        button(
                            onClick = clear,
                            enabled = population > 0,
                            semantics = Semantics(role = Role.Button, testTag = "clear", focusable = true),
                        ) {
                            text("Clear", semantics = null)
                        }
                    }

                    host("div", props = propsOf("class", "speed-control", "aria-label", "Simulation speed")) {
                        host("span", props = propsOf("class", "speed-label")) { text("Speed", semantics = null) }
                        host("div", props = propsOf("class", "speed-options")) {
                            each(SimulationSpeed.entries, key = { it.name }) { option ->
                                button(
                                    onClick = event { speed = option },
                                    enabled = speed != option,
                                    semantics = Semantics(
                                        role = Role.Button,
                                        label = "${option.label} speed",
                                        stateDescription = if (speed == option) "Selected speed" else null,
                                        testTag = "speed-${option.name.lowercase()}",
                                        focusable = true,
                                    ),
                                ) {
                                    text(option.label, semantics = null)
                                }
                            }
                        }
                    }
                }

                host("div", props = propsOf("class", "status-strip", "aria-live", "polite")) {
                    host("div", props = propsOf("class", "status-item")) {
                        host("span", props = propsOf("class", "status-label")) { text("Generation", semantics = null) }
                        host("strong", semantics = Semantics(testTag = "generation-value")) {
                            text(board.generation.toString(), semantics = null)
                        }
                    }
                    host("div", props = propsOf("class", "status-item")) {
                        host("span", props = propsOf("class", "status-label")) { text("Population", semantics = null) }
                        host("strong", semantics = Semantics(testTag = "population-value")) {
                            text(population.toString(), semantics = null)
                        }
                    }
                    host("div", props = propsOf("class", "status-item pattern-status")) {
                        host("span", props = propsOf("class", "status-label")) { text("Pattern", semantics = null) }
                        host("strong") { text(selectedPreset?.displayName ?: "Custom field", semantics = null) }
                    }
                    host("div", props = propsOf("class", "run-state")) {
                        host(
                            "span",
                            props = propsOf("class", if (running) "state-dot is-running" else "state-dot"),
                            semantics = Semantics(testTag = "run-state-indicator"),
                        )
                        text(if (running) "Evolving" else "Paused", semantics = null)
                    }
                }

                host("div", props = propsOf("class", "board-frame")) {
                    host("div", props = propsOf("class", "board-chrome")) {
                        host("div", props = propsOf("class", "board-caption")) {
                            host("span") { text("$BoardColumns × $BoardRows universe", semantics = null) }
                            host("span", props = propsOf("class", "coordinate-hint")) {
                                text("Finite boundary · outside cells stay dead", semantics = null)
                            }
                        }
                        host("div", props = propsOf("class", "board-scroll")) {
                            host(
                                "div",
                                props = propsOf(
                                    "class", "life-grid",
                                    "role", "grid",
                                    "aria-label", "Editable Game of Life grid",
                                ),
                                semantics = Semantics(testTag = "life-grid"),
                            ) {
                                each(cells, key = { it.index }) { cell ->
                                    val click = hostEvent(onEvent = event {
                                        running = false
                                        selectedPreset = null
                                        board = board.toggle(cell.point)
                                    })
                                    host(
                                        "button",
                                        props = propsOf(
                                            "type" to "button",
                                            "class" to if (cell.alive) "life-cell is-alive" else "life-cell",
                                            "aria-pressed" to cell.alive.toString(),
                                            "event:onClick" to click,
                                        ),
                                        semantics = Semantics(
                                            role = Role.Button,
                                            label = "Column ${cell.point.column + 1}, row ${cell.point.row + 1}",
                                            stateDescription = if (cell.alive) "Alive" else "Dead",
                                            testTag = "cell-${cell.point.column}-${cell.point.row}",
                                            focusable = true,
                                        ),
                                        key = cell.index,
                                    )
                                }
                            }
                        }
                    }
                }

                host("div", props = propsOf("class", "rule-legend")) {
                    host("div") {
                        host("span", props = propsOf("class", "legend-number")) { text("3", semantics = null) }
                        host("p") { text("A dead cell with three neighbors is born.", semantics = null) }
                    }
                    host("div") {
                        host("span", props = propsOf("class", "legend-number")) { text("2–3", semantics = null) }
                        host("p") { text("A live cell with two or three neighbors survives.", semantics = null) }
                    }
                    host("div") {
                        host("span", props = propsOf("class", "legend-number muted-number")) { text("0–1 / 4+", semantics = null) }
                        host("p") { text("Every other live cell fades from the field.", semantics = null) }
                    }
                }
            }
        }
    }
}

fun main() {
    var app: BrowserKineticaApp? = null
    app = mountKineticaApp("#app", runtime = KineticaRuntime(debug = false)) {
        GameOfLifeApp { app?.render() }
    }
    centerBoardViewport()
}

private fun centerBoardViewport() {
    js(
        """
        requestAnimationFrame(function () {
            var viewport = document.querySelector('.board-scroll');
            if (viewport) {
                viewport.scrollLeft = (viewport.scrollWidth - viewport.clientWidth) / 2;
            }
        });
        """,
    )
}
