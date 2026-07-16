package app.browser.gameoflife.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.browser.gameoflife.GridPoint
import app.browser.gameoflife.LifeBoard
import app.browser.gameoflife.LifePreset
import app.browser.gameoflife.LifeSeedSequence
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

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

private fun centerBoardViewport() {
    window.requestAnimationFrame {
        val viewport = document.querySelector(".board-scroll") as? HTMLElement
        if (viewport != null) {
            viewport.scrollLeft = ((viewport.scrollWidth - viewport.clientWidth) / 2).toDouble()
        }
    }
}

@Composable
private fun PresetOption(
    preset: LifePreset,
    selected: Boolean,
    onLoad: (LifePreset) -> Unit,
) {
    Div(attrs = {
        classes("preset-option")
        if (selected) classes("is-selected")
    }) {
        Button(attrs = {
            attr("type", "button")
            attr("aria-label", "Load ${preset.displayName}")
            attr("aria-pressed", selected.toString())
            attr("data-testid", "preset-${preset.id}")
            onClick { onLoad(preset) }
        }) {
            Span(attrs = { classes("preset-title") }) {
                Text(preset.displayName)
                Span(attrs = { classes("preset-size") }) {
                    Text("${preset.columns} × ${preset.rows}")
                }
            }
            Span(attrs = { classes("preset-category") }) { Text(preset.category) }
            Span(attrs = { classes("preset-description") }) { Text(preset.description) }
        }
    }
}

@Composable
private fun LifeCell(
    index: Int,
    alive: Boolean,
    onToggle: (Int) -> Unit,
) {
    val column = index % BoardColumns
    val row = index / BoardColumns
    Button(attrs = {
        attr("type", "button")
        classes("life-cell")
        if (alive) classes("is-alive")
        attr("aria-label", "Column ${column + 1}, row ${row + 1}")
        attr("aria-pressed", alive.toString())
        attr("data-state-description", if (alive) "Alive" else "Dead")
        attr("data-testid", "cell-$column-$row")
        onClick { onToggle(index) }
    }) {}
}

@Composable
fun GameOfLifeApp() {
    var board by remember {
        mutableStateOf(
            LifeBoard(BoardColumns, BoardRows).load(LifePreset.PULSAR),
            referentialEqualityPolicy(),
        )
    }
    var running by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(SimulationSpeed.NORMAL) }
    var selectedPreset by remember { mutableStateOf<LifePreset?>(LifePreset.PULSAR) }
    val randomSeeds = remember { LifeSeedSequence() }

    LaunchedEffect(Unit) { centerBoardViewport() }
    LaunchedEffect(running, speed) {
        if (running) {
            while (true) {
                delay(speed.delayMillis)
                val next = board.step()
                board = next
                if (next.population == 0) {
                    running = false
                    break
                }
            }
        }
    }

    val loadPreset = remember {
        { preset: LifePreset ->
            running = false
            selectedPreset = preset
            board = board.load(preset)
            centerBoardViewport()
        }
    }
    val toggleCell = remember {
        { index: Int ->
            running = false
            selectedPreset = null
            board = board.toggle(GridPoint(index % BoardColumns, index / BoardColumns))
        }
    }

    Div(attrs = {
        classes("life-app")
        attr("data-testid", "game-of-life-app")
    }) {
        Div(attrs = { classes("hero") }) {
            Div(attrs = { classes("brand-line") }) {
                Span(attrs = {
                    classes("brand-mark")
                    attr("aria-hidden", "true")
                }) { Text("C") }
                Span(attrs = { classes("eyebrow") }) { Text("Compose HTML playground") }
                Span(attrs = { classes("rule-chip") }) { Text("B3 / S23") }
            }
            Div(attrs = { classes("hero-copy") }) {
                H1 { Text("Conway’s Game of Life") }
                P { Text("Seed a world, set it in motion, and watch four simple rules create complex behavior.") }
            }
        }

        Div(attrs = { classes("workspace") }) {
            Div(attrs = {
                classes("preset-panel")
                attr("aria-label", "Pattern presets")
            }) {
                Div(attrs = { classes("section-heading") }) {
                    Span(attrs = { classes("section-kicker") }) { Text("Start here") }
                    H2 { Text("Classic patterns") }
                    P { Text("Load a known form, then run it or reshape it cell by cell.") }
                }
                Div(attrs = { classes("preset-list") }) {
                    for (preset in LifePreset.entries) {
                        key(preset.id) {
                            PresetOption(preset, preset == selectedPreset, loadPreset)
                        }
                    }
                }
                Div(attrs = { classes("tip-card") }) {
                    Span(attrs = {
                        classes("tip-icon")
                        attr("aria-hidden", "true")
                    }) { Text("✦") }
                    P { Text("Tip: select any square on the board to toggle it. Editing pauses the simulation.") }
                }
            }

            Div(attrs = { classes("simulation-panel") }) {
                Div(attrs = { classes("control-bar") }) {
                    Div(attrs = { classes("primary-actions") }) {
                        Button(attrs = {
                            attr("type", "button")
                            attr("data-testid", "toggle-running")
                            attr("aria-label", if (running) "Pause simulation" else "Run simulation")
                            if (board.population == 0) attr("disabled", "")
                            onClick {
                                if (board.population > 0) running = !running
                            }
                        }) {
                            Span(attrs = {
                                classes("button-icon")
                                attr("aria-hidden", "true")
                            }) { Text(if (running) "Ⅱ" else "▶") }
                            Text(if (running) "Pause" else "Run")
                        }
                        Button(attrs = {
                            attr("type", "button")
                            attr("data-testid", "step")
                            attr("aria-label", "Advance one generation")
                            onClick {
                                running = false
                                board = board.step()
                            }
                        }) {
                            Span(attrs = {
                                classes("button-icon")
                                attr("aria-hidden", "true")
                            }) { Text("↦") }
                            Text("Step")
                        }
                    }

                    Div(attrs = { classes("secondary-actions") }) {
                        Button(attrs = {
                            attr("type", "button")
                            attr("data-testid", "randomize")
                            onClick {
                                running = false
                                selectedPreset = null
                                board = board.randomized(seed = randomSeeds.take())
                            }
                        }) { Text("Randomize") }
                        Button(attrs = {
                            attr("type", "button")
                            attr("data-testid", "clear")
                            if (board.population == 0) attr("disabled", "")
                            onClick {
                                running = false
                                selectedPreset = null
                                board = board.clear()
                            }
                        }) { Text("Clear") }
                    }

                    Div(attrs = {
                        classes("speed-control")
                        attr("aria-label", "Simulation speed")
                    }) {
                        Span(attrs = { classes("speed-label") }) { Text("Speed") }
                        Div(attrs = { classes("speed-options") }) {
                            for (option in SimulationSpeed.entries) {
                                key(option.name) {
                                    Button(attrs = {
                                        attr("type", "button")
                                        attr("data-testid", "speed-${option.name.lowercase()}")
                                        attr("aria-label", "${option.label} speed")
                                        attr("aria-pressed", (speed == option).toString())
                                        if (speed == option) attr("disabled", "")
                                        onClick { speed = option }
                                    }) { Text(option.label) }
                                }
                            }
                        }
                    }
                }

                Div(attrs = {
                    classes("status-strip")
                    attr("aria-live", "polite")
                }) {
                    Div(attrs = { classes("status-item") }) {
                        Span(attrs = { classes("status-label") }) { Text("Generation") }
                        Span(attrs = {
                            classes("status-value")
                            attr("data-testid", "generation-value")
                        }) {
                            Text(board.generation.toString())
                        }
                    }
                    Div(attrs = { classes("status-item") }) {
                        Span(attrs = { classes("status-label") }) { Text("Population") }
                        Span(attrs = {
                            classes("status-value")
                            attr("data-testid", "population-value")
                        }) {
                            Text(board.population.toString())
                        }
                    }
                    Div(attrs = { classes("status-item", "pattern-status") }) {
                        Span(attrs = { classes("status-label") }) { Text("Pattern") }
                        Span(attrs = { classes("status-value") }) {
                            Text(selectedPreset?.displayName ?: "Custom field")
                        }
                    }
                    Div(attrs = { classes("run-state") }) {
                        Span(attrs = {
                            classes("state-dot")
                            if (running) classes("is-running")
                            attr("data-testid", "run-state-indicator")
                        }) {}
                        Text(if (running) "Evolving" else "Paused")
                    }
                }

                Div(attrs = { classes("board-frame") }) {
                    Div(attrs = { classes("board-chrome") }) {
                        Div(attrs = { classes("board-caption") }) {
                            Span { Text("$BoardColumns × $BoardRows universe") }
                            Span(attrs = { classes("coordinate-hint") }) {
                                Text("Finite boundary · outside cells stay dead")
                            }
                        }
                        Div(attrs = { classes("board-scroll") }) {
                            Div(attrs = {
                                classes("life-grid")
                                attr("role", "grid")
                                attr("aria-label", "Editable Game of Life grid")
                                attr("data-testid", "life-grid")
                            }) {
                                for (index in 0 until BoardColumns * BoardRows) {
                                    key(index) {
                                        LifeCell(
                                            index = index,
                                            alive = board[index % BoardColumns, index / BoardColumns],
                                            onToggle = toggleCell,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Div(attrs = { classes("rule-legend") }) {
                    Div {
                        Span(attrs = { classes("legend-number") }) { Text("3") }
                        P { Text("A dead cell with three neighbors is born.") }
                    }
                    Div {
                        Span(attrs = { classes("legend-number") }) { Text("2–3") }
                        P { Text("A live cell with two or three neighbors survives.") }
                    }
                    Div {
                        Span(attrs = { classes("legend-number", "muted-number") }) { Text("0–1 / 4+") }
                        P { Text("Every other live cell fades from the field.") }
                    }
                }
            }
        }
    }
}

fun main() {
    var composition: Composition? = null

    fun mountApp() {
        composition = renderComposable(rootElementId = "app") { GameOfLifeApp() }
    }

    mountApp()
    window.asDynamic().__mount = { mountApp() }
    window.asDynamic().__unmount = {
        composition?.dispose()
        composition = null
    }
}
