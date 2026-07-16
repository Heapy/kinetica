package app.browser.gameoflife

import kotlin.random.Random

// Shared by the Kinetica and Compose HTML browser implementations.

data class GridPoint(
    val column: Int,
    val row: Int,
)

data class LifeBoard(
    val columns: Int,
    val rows: Int,
    val livingCells: Set<GridPoint> = emptySet(),
    val generation: Long = 0,
) {
    init {
        require(columns > 0) { "Board columns must be positive." }
        require(rows > 0) { "Board rows must be positive." }
        require(columns.toLong() * rows <= Int.MAX_VALUE) { "Board is too large to index." }
        require(generation >= 0) { "Generation must not be negative." }
        require(livingCells.all { it in this }) { "Every living cell must be inside the board." }
    }

    private val livingMask: BooleanArray = BooleanArray(columns * rows).also { mask ->
        for (cell in livingCells) {
            mask[cell.row * columns + cell.column] = true
        }
    }

    val population: Int
        get() = livingCells.size

    operator fun contains(point: GridPoint): Boolean =
        point.column in 0 until columns && point.row in 0 until rows

    operator fun get(column: Int, row: Int): Boolean =
        column in 0 until columns && row in 0 until rows && livingMask[row * columns + column]

    fun toggle(point: GridPoint): LifeBoard {
        require(point in this) { "Cannot toggle a cell outside the board: $point" }
        val nextCells = livingCells.toMutableSet()
        if (!nextCells.add(point)) {
            nextCells.remove(point)
        }
        return copy(livingCells = nextCells)
    }

    fun clear(): LifeBoard =
        copy(livingCells = emptySet(), generation = 0)

    fun randomized(
        density: Double = 0.24,
        random: Random = Random.Default,
    ): LifeBoard {
        require(density in 0.0..1.0) { "Density must be between 0 and 1." }
        val nextCells = buildSet {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    if (random.nextDouble() < density) {
                        add(GridPoint(column, row))
                    }
                }
            }
        }
        return copy(livingCells = nextCells, generation = 0)
    }

    /** Cross-language deterministic variant used by every browser implementation and benchmark. */
    fun randomized(
        seed: Int,
        density: Double = 0.24,
    ): LifeBoard {
        require(density in 0.0..1.0) { "Density must be between 0 and 1." }
        var state = seed
        fun nextDouble(): Double {
            state = state * 1_664_525 + 1_013_904_223
            return state.toUInt().toDouble() / 4_294_967_296.0
        }
        val nextCells = buildSet {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    if (nextDouble() < density) {
                        add(GridPoint(column, row))
                    }
                }
            }
        }
        return copy(livingCells = nextCells, generation = 0)
    }

    fun load(preset: LifePreset): LifeBoard {
        require(preset.columns <= columns && preset.rows <= rows) {
            "${preset.displayName} (${preset.columns}x${preset.rows}) does not fit on a ${columns}x$rows board."
        }
        val columnOffset = (columns - preset.columns) / 2
        val rowOffset = (rows - preset.rows) / 2
        return copy(
            livingCells = preset.cells.mapTo(linkedSetOf()) { point ->
                GridPoint(point.column + columnOffset, point.row + rowOffset)
            },
            generation = 0,
        )
    }

    fun step(): LifeBoard {
        // The browser board is small and indexable: typed arrays avoid allocating a GridPoint
        // and hashing it for every neighbor while the candidate list keeps sparse steps sparse.
        val neighborCounts = IntArray(columns * rows)
        val candidates = IntArray(neighborCounts.size)
        var candidateCount = 0
        for (cell in livingCells) {
            val cellIndex = cell.row * columns + cell.column
            val firstRow = maxOf(0, cell.row - 1)
            val lastRow = minOf(rows - 1, cell.row + 1)
            val firstColumn = maxOf(0, cell.column - 1)
            val lastColumn = minOf(columns - 1, cell.column + 1)
            for (neighborRow in firstRow..lastRow) {
                for (neighborColumn in firstColumn..lastColumn) {
                    val neighborIndex = neighborRow * columns + neighborColumn
                    if (neighborIndex == cellIndex) continue
                    if (neighborCounts[neighborIndex] == 0) {
                        candidates[candidateCount++] = neighborIndex
                    }
                    neighborCounts[neighborIndex] += 1
                }
            }
        }

        val nextCells = buildSet {
            for (candidateIndex in 0 until candidateCount) {
                val cellIndex = candidates[candidateIndex]
                val neighbors = neighborCounts[cellIndex]
                if (neighbors == 3 || neighbors == 2 && livingMask[cellIndex]) {
                    add(GridPoint(column = cellIndex % columns, row = cellIndex / columns))
                }
            }
        }
        return copy(livingCells = nextCells, generation = generation + 1)
    }
}

/** Produces a different, reproducible board seed for each Randomize click. */
class LifeSeedSequence(
    seed: Int = 0x13579BDF,
) {
    private var nextSeed = seed

    fun take(): Int {
        val current = nextSeed
        nextSeed += -1_640_531_527 // 0x9E3779B9, the 32-bit golden-ratio increment.
        return current
    }
}

enum class LifePreset(
    val displayName: String,
    val category: String,
    val description: String,
    patternRows: List<String>,
) {
    GLIDER(
        displayName = "Glider",
        category = "Spaceship · period 4",
        description = "The smallest pattern that travels across the field.",
        patternRows = listOf(
            ".O.",
            "..O",
            "OOO",
        ),
    ),
    LIGHTWEIGHT_SPACESHIP(
        displayName = "Lightweight spaceship",
        category = "Spaceship · period 4",
        description = "A nine-cell craft moving horizontally at half light speed.",
        patternRows = listOf(
            ".O..O",
            "O....",
            "O...O",
            "OOOO.",
        ),
    ),
    BEACON(
        displayName = "Beacon",
        category = "Oscillator · period 2",
        description = "Two blocks blink between connected and separated phases.",
        patternRows = listOf(
            "OO..",
            "OO..",
            "..OO",
            "..OO",
        ),
    ),
    PULSAR(
        displayName = "Pulsar",
        category = "Oscillator · period 3",
        description = "A symmetric 48-cell classic with a dramatic three-step pulse.",
        patternRows = listOf(
            "..OOO...OOO..",
            ".............",
            "O....O.O....O",
            "O....O.O....O",
            "O....O.O....O",
            "..OOO...OOO..",
            ".............",
            "..OOO...OOO..",
            "O....O.O....O",
            "O....O.O....O",
            "O....O.O....O",
            ".............",
            "..OOO...OOO..",
        ),
    ),
    ;

    val id: String = name.lowercase().replace('_', '-')
    val rows: Int = patternRows.size
    val columns: Int = patternRows.firstOrNull()?.length ?: 0
    val cells: Set<GridPoint> = buildSet {
        require(patternRows.isNotEmpty()) { "$displayName must contain at least one row." }
        patternRows.forEachIndexed { row, patternRow ->
            require(patternRow.length == columns) { "$displayName must be rectangular." }
            patternRow.forEachIndexed { column, value ->
                require(value == '.' || value == 'O') { "$displayName contains an unsupported cell marker: $value" }
                if (value == 'O') {
                    add(GridPoint(column, row))
                }
            }
        }
    }
}
