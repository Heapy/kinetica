package app.browser.gameoflife

import kotlin.random.Random

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
        require(generation >= 0) { "Generation must not be negative." }
        require(livingCells.all { it in this }) { "Every living cell must be inside the board." }
    }

    val population: Int
        get() = livingCells.size

    operator fun contains(point: GridPoint): Boolean =
        point.column in 0 until columns && point.row in 0 until rows

    operator fun get(column: Int, row: Int): Boolean =
        GridPoint(column, row) in livingCells

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
        val neighborCounts = HashMap<GridPoint, Int>()
        for (cell in livingCells) {
            for (rowDelta in -1..1) {
                for (columnDelta in -1..1) {
                    if (columnDelta == 0 && rowDelta == 0) continue
                    val neighbor = GridPoint(
                        column = cell.column + columnDelta,
                        row = cell.row + rowDelta,
                    )
                    if (neighbor in this) {
                        neighborCounts[neighbor] = (neighborCounts[neighbor] ?: 0) + 1
                    }
                }
            }
        }

        val nextCells = buildSet {
            for ((point, neighbors) in neighborCounts) {
                if (neighbors == 3 || neighbors == 2 && point in livingCells) {
                    add(point)
                }
            }
        }
        return copy(livingCells = nextCells, generation = generation + 1)
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
