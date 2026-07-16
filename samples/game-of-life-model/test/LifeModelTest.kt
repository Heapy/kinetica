package app.browser.gameoflife

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeModelTest {
    @Test
    fun blockIsStableAndBlinkerHasPeriodTwo() {
        val blockCells = points(4 to 4, 5 to 4, 4 to 5, 5 to 5)
        val block = LifeBoard(columns = 10, rows = 10, livingCells = blockCells)
        assertEquals(blockCells, block.step().livingCells)
        assertEquals(1, block.step().generation)

        val horizontal = LifeBoard(
            columns = 7,
            rows = 7,
            livingCells = points(2 to 3, 3 to 3, 4 to 3),
        )
        val vertical = points(3 to 2, 3 to 3, 3 to 4)
        assertEquals(vertical, horizontal.step().livingCells)
        assertEquals(horizontal.livingCells, horizontal.step().step().livingCells)
    }

    @Test
    fun spaceshipsTranslateAfterFourGenerations() {
        val glider = LifeBoard(24, 20).load(LifePreset.GLIDER)
        assertEquals(
            glider.livingCells.shifted(columnDelta = 1, rowDelta = 1),
            glider.after(4).livingCells,
        )

        val lightweightSpaceship = LifeBoard(24, 20).load(LifePreset.LIGHTWEIGHT_SPACESHIP)
        assertEquals(
            lightweightSpaceship.livingCells.shifted(columnDelta = -2, rowDelta = 0),
            lightweightSpaceship.after(4).livingCells,
        )
    }

    @Test
    fun beaconAndPulsarReturnToTheirStartingPhases() {
        val beacon = LifeBoard(24, 20).load(LifePreset.BEACON)
        assertEquals(beacon.livingCells, beacon.after(2).livingCells)

        val pulsar = LifeBoard(24, 20).load(LifePreset.PULSAR)
        assertEquals(pulsar.livingCells, pulsar.after(3).livingCells)
    }

    @Test
    fun finiteBoundaryDoesNotWrapCellsAroundTheBoard() {
        val edgeBlinker = LifeBoard(
            columns = 4,
            rows = 4,
            livingCells = points(0 to 0, 0 to 1, 0 to 2),
        )

        assertEquals(points(0 to 1, 1 to 1), edgeBlinker.step().livingCells)
    }

    @Test
    fun presetsCenterAndBoardEditingStaysImmutable() {
        val original = LifeBoard(columns = 11, rows = 9, generation = 12)
        val loaded = original.load(LifePreset.GLIDER)
        assertEquals(0, loaded.generation)
        assertEquals(points(5 to 3, 6 to 4, 4 to 5, 5 to 5, 6 to 5), loaded.livingCells)

        val toggled = loaded.toggle(GridPoint(0, 0))
        assertFalse(GridPoint(0, 0) in loaded.livingCells)
        assertTrue(GridPoint(0, 0) in toggled.livingCells)
        assertEquals(loaded.generation, toggled.generation)
        assertEquals(LifeBoard(11, 9), toggled.clear())
    }

    @Test
    fun presetsAndRandomBoardsHaveExpectedPopulations() {
        assertEquals(
            mapOf(
                LifePreset.GLIDER to 5,
                LifePreset.LIGHTWEIGHT_SPACESHIP to 9,
                LifePreset.BEACON to 8,
                LifePreset.PULSAR to 48,
            ),
            LifePreset.entries.associateWith { it.cells.size },
        )

        val board = LifeBoard(5, 4, generation = 9)
        assertEquals(0, board.randomized(density = 0.0, random = Random(7)).population)
        val full = board.randomized(density = 1.0, random = Random(7))
        assertEquals(20, full.population)
        assertEquals(0, full.generation)

        val seeds = LifeSeedSequence()
        val seeded = LifeBoard(72, 48).randomized(seed = seeds.take())
        assertEquals(847, seeded.population)
        assertEquals(1_482_807, seeded.livingCells.sumOf { it.row * 72 + it.column })
        val evolved = seeded.step()
        assertEquals(963, evolved.population)
        assertEquals(1_709_984, evolved.livingCells.sumOf { it.row * 72 + it.column })
        assertEquals(-1_316_022_888, seeds.take())
    }

    @Test
    fun invalidBoardsAndOperationsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LifeBoard(columns = 0, rows = 3) }
        assertFailsWith<IllegalArgumentException> { LifeBoard(columns = Int.MAX_VALUE, rows = 2) }
        assertFailsWith<IllegalArgumentException> {
            LifeBoard(columns = 3, rows = 3, livingCells = points(3 to 0))
        }
        assertFailsWith<IllegalArgumentException> { LifeBoard(2, 2).load(LifePreset.GLIDER) }
        assertFailsWith<IllegalArgumentException> { LifeBoard(3, 3).toggle(GridPoint(-1, 0)) }
        assertFailsWith<IllegalArgumentException> { LifeBoard(3, 3).randomized(density = 1.1) }
    }
}

private fun points(vararg coordinates: Pair<Int, Int>): Set<GridPoint> =
    coordinates.mapTo(linkedSetOf()) { (column, row) -> GridPoint(column, row) }

private fun Set<GridPoint>.shifted(columnDelta: Int, rowDelta: Int): Set<GridPoint> =
    mapTo(linkedSetOf()) { point ->
        GridPoint(point.column + columnDelta, point.row + rowDelta)
    }

private fun LifeBoard.after(generations: Int): LifeBoard =
    (0 until generations).fold(this) { board, _ -> board.step() }
