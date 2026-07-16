import assert from "node:assert/strict";
import {
  LIFE_PRESETS,
  LifeSeedSequence,
  afterGenerations,
  cellIndex,
  clearBoard,
  createBoard,
  loadPreset,
  randomizeBoard,
  randomizeBoardSeeded,
  stepBoard,
  toggleCell,
} from "./life-core.mjs";

const points = (columns, ...coordinates) =>
  new Set(coordinates.map(([column, row]) => cellIndex(column, row, columns)));

const blockCells = points(10, [4, 4], [5, 4], [4, 5], [5, 5]);
const block = createBoard({ columns: 10, rows: 10, livingCells: blockCells });
assert.deepEqual(stepBoard(block).livingCells, blockCells);

const horizontal = createBoard({
  columns: 7,
  rows: 7,
  livingCells: points(7, [2, 3], [3, 3], [4, 3]),
});
assert.deepEqual(stepBoard(horizontal).livingCells, points(7, [3, 2], [3, 3], [3, 4]));
assert.deepEqual(afterGenerations(horizontal, 2).livingCells, horizontal.livingCells);

const glider = loadPreset(createBoard({ columns: 24, rows: 20 }), "glider");
const shiftedGlider = new Set([...glider.livingCells].map((index) => index + 24 + 1));
assert.deepEqual(afterGenerations(glider, 4).livingCells, shiftedGlider);

const lightweightSpaceship = loadPreset(createBoard({ columns: 24, rows: 20 }), "lightweight-spaceship");
const shiftedSpaceship = new Set([...lightweightSpaceship.livingCells].map((index) => index - 2));
assert.deepEqual(afterGenerations(lightweightSpaceship, 4).livingCells, shiftedSpaceship);

for (const [id, period] of [["beacon", 2], ["pulsar", 3]]) {
  const board = loadPreset(createBoard({ columns: 24, rows: 20 }), id);
  assert.deepEqual(afterGenerations(board, period).livingCells, board.livingCells);
}

const edgeBlinker = createBoard({
  columns: 4,
  rows: 4,
  livingCells: points(4, [0, 0], [0, 1], [0, 2]),
});
assert.deepEqual(stepBoard(edgeBlinker).livingCells, points(4, [0, 1], [1, 1]));

const edited = toggleCell(glider, cellIndex(0, 0, glider.columns));
assert.equal(glider.livingCells.has(0), false);
assert.equal(edited.livingCells.has(0), true);
assert.equal(clearBoard(edited).population, 0);

assert.deepEqual(
  Object.fromEntries(LIFE_PRESETS.map((preset) => [preset.id, preset.points.length])),
  { glider: 5, "lightweight-spaceship": 9, beacon: 8, pulsar: 48 },
);
assert.equal(randomizeBoard(createBoard({ columns: 5, rows: 4 }), 0, () => 0.5).population, 0);
assert.equal(randomizeBoard(createBoard({ columns: 5, rows: 4 }), 1, () => 0.5).population, 20);

const seeds = new LifeSeedSequence();
const seeded = randomizeBoardSeeded(createBoard(), seeds.take());
assert.equal(seeded.population, 847);
assert.equal([...seeded.livingCells].reduce((sum, index) => sum + index, 0), 1_482_807);
const seededNext = stepBoard(seeded);
assert.equal(seededNext.population, 963);
assert.equal([...seededNext.livingCells].reduce((sum, index) => sum + index, 0), 1_709_984);
assert.equal(seeds.take(), 2_978_944_408);

for (const [columns, rows, density, seed] of [
  [1, 1, 1, 1],
  [2, 3, 0.5, 2],
  [17, 11, 0.05, 3],
  [17, 11, 0.8, 4],
  [72, 48, 0.24, 5],
]) {
  let actual = randomizeBoardSeeded(createBoard({ columns, rows }), seed, density);
  let expected = actual;
  for (let generation = 0; generation < 8; generation++) {
    actual = stepBoard(actual);
    expected = referenceStepBoard(expected);
    assert.deepEqual(
      actual,
      expected,
      `optimized step diverged for ${columns}x${rows} density=${density} at generation ${generation + 1}`,
    );
  }
}

assert.throws(() => createBoard({ columns: 0 }));
assert.throws(() => toggleCell(createBoard({ columns: 3, rows: 3 }), -1));
assert.throws(() => randomizeBoard(createBoard(), 1.1));

console.log("Game of Life JavaScript model parity tests passed");

function referenceStepBoard(board) {
  const neighborCounts = new Map();
  for (const index of board.livingCells) {
    const column = index % board.columns;
    const row = Math.floor(index / board.columns);
    for (let rowDelta = -1; rowDelta <= 1; rowDelta++) {
      for (let columnDelta = -1; columnDelta <= 1; columnDelta++) {
        if (columnDelta === 0 && rowDelta === 0) continue;
        const neighborColumn = column + columnDelta;
        const neighborRow = row + rowDelta;
        if (
          neighborColumn >= 0 && neighborColumn < board.columns &&
          neighborRow >= 0 && neighborRow < board.rows
        ) {
          const neighbor = cellIndex(neighborColumn, neighborRow, board.columns);
          neighborCounts.set(neighbor, (neighborCounts.get(neighbor) ?? 0) + 1);
        }
      }
    }
  }
  const livingCells = new Set();
  for (const [index, neighbors] of neighborCounts) {
    if (neighbors === 3 || (neighbors === 2 && board.livingCells.has(index))) {
      livingCells.add(index);
    }
  }
  return createBoard({ ...board, livingCells, generation: board.generation + 1 });
}
