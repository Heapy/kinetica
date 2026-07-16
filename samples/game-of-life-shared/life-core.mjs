export const BOARD_COLUMNS = 72;
export const BOARD_ROWS = 48;
export const RANDOM_DENSITY = 0.24;
const UINT32_RANGE = 4_294_967_296;
const SEED_INCREMENT = 0x9e3779b9;
const LIVE_CELL_MASK = 0x10;
const NEIGHBOR_COUNT_MASK = 0x0f;

export const SIMULATION_SPEEDS = Object.freeze([
  Object.freeze({ id: "slow", label: "Slow", delayMs: 420 }),
  Object.freeze({ id: "normal", label: "Normal", delayMs: 180 }),
  Object.freeze({ id: "fast", label: "Fast", delayMs: 80 }),
]);

const PRESET_DEFINITIONS = [
  {
    id: "glider",
    displayName: "Glider",
    category: "Spaceship · period 4",
    description: "The smallest pattern that travels across the field.",
    patternRows: [".O.", "..O", "OOO"],
  },
  {
    id: "lightweight-spaceship",
    displayName: "Lightweight spaceship",
    category: "Spaceship · period 4",
    description: "A nine-cell craft moving horizontally at half light speed.",
    patternRows: [".O..O", "O....", "O...O", "OOOO."],
  },
  {
    id: "beacon",
    displayName: "Beacon",
    category: "Oscillator · period 2",
    description: "Two blocks blink between connected and separated phases.",
    patternRows: ["OO..", "OO..", "..OO", "..OO"],
  },
  {
    id: "pulsar",
    displayName: "Pulsar",
    category: "Oscillator · period 3",
    description: "A symmetric 48-cell classic with a dramatic three-step pulse.",
    patternRows: [
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
    ],
  },
];

export const LIFE_PRESETS = Object.freeze(PRESET_DEFINITIONS.map((definition) => {
  const rows = definition.patternRows.length;
  const columns = definition.patternRows[0]?.length ?? 0;
  if (rows === 0 || columns === 0 || definition.patternRows.some((row) => row.length !== columns)) {
    throw new Error(`${definition.displayName} must be a non-empty rectangular pattern.`);
  }
  const points = [];
  definition.patternRows.forEach((row, rowIndex) => {
    [...row].forEach((marker, columnIndex) => {
      if (marker !== "." && marker !== "O") {
        throw new Error(`${definition.displayName} contains unsupported marker ${marker}.`);
      }
      if (marker === "O") points.push(Object.freeze({ column: columnIndex, row: rowIndex }));
    });
  });
  return Object.freeze({
    ...definition,
    patternRows: Object.freeze([...definition.patternRows]),
    columns,
    rows,
    points: Object.freeze(points),
  });
}));

export function presetById(id) {
  const preset = LIFE_PRESETS.find((candidate) => candidate.id === id);
  if (!preset) throw new Error(`Unknown Life preset: ${id}`);
  return preset;
}

export function cellIndex(column, row, columns = BOARD_COLUMNS) {
  return row * columns + column;
}

export function cellCoordinates(index, columns = BOARD_COLUMNS) {
  return { column: index % columns, row: Math.floor(index / columns) };
}

export function createBoard({
  columns = BOARD_COLUMNS,
  rows = BOARD_ROWS,
  livingCells = new Set(),
  generation = 0,
} = {}) {
  if (!Number.isInteger(columns) || columns <= 0) throw new Error("Board columns must be positive.");
  if (!Number.isInteger(rows) || rows <= 0) throw new Error("Board rows must be positive.");
  if (!Number.isInteger(generation) || generation < 0) throw new Error("Generation must not be negative.");
  const maxIndex = columns * rows;
  const cells = new Set(livingCells);
  for (const index of cells) {
    if (!Number.isInteger(index) || index < 0 || index >= maxIndex) {
      throw new Error(`Living cell ${index} is outside the board.`);
    }
  }
  return freezeBoard(columns, rows, cells, generation);
}

function freezeBoard(columns, rows, livingCells, generation) {
  return Object.freeze({ columns, rows, livingCells, generation, population: livingCells.size });
}

export function loadPreset(board, presetOrId) {
  const preset = typeof presetOrId === "string" ? presetById(presetOrId) : presetOrId;
  if (preset.columns > board.columns || preset.rows > board.rows) {
    throw new Error(`${preset.displayName} does not fit on a ${board.columns}x${board.rows} board.`);
  }
  const columnOffset = Math.floor((board.columns - preset.columns) / 2);
  const rowOffset = Math.floor((board.rows - preset.rows) / 2);
  const livingCells = new Set(preset.points.map(({ column, row }) =>
    cellIndex(column + columnOffset, row + rowOffset, board.columns)
  ));
  return createBoard({ ...board, livingCells, generation: 0 });
}

export function toggleCell(board, index) {
  if (!Number.isInteger(index) || index < 0 || index >= board.columns * board.rows) {
    throw new Error(`Cannot toggle a cell outside the board: ${index}`);
  }
  const livingCells = new Set(board.livingCells);
  if (livingCells.has(index)) livingCells.delete(index);
  else livingCells.add(index);
  return freezeBoard(board.columns, board.rows, livingCells, board.generation);
}

export function clearBoard(board) {
  return freezeBoard(board.columns, board.rows, new Set(), 0);
}

export function randomizeBoard(board, density = RANDOM_DENSITY, random = Math.random) {
  if (density < 0 || density > 1) throw new Error("Density must be between 0 and 1.");
  const livingCells = new Set();
  for (let index = 0; index < board.columns * board.rows; index++) {
    if (random() < density) livingCells.add(index);
  }
  return freezeBoard(board.columns, board.rows, livingCells, 0);
}

export function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1_664_525) + 1_013_904_223) >>> 0;
    return state / UINT32_RANGE;
  };
}

export class LifeSeedSequence {
  constructor(seed = 0x13579bdf) {
    this.nextSeed = seed >>> 0;
  }

  take() {
    const current = this.nextSeed;
    this.nextSeed = (this.nextSeed + SEED_INCREMENT) >>> 0;
    return current;
  }
}

export function randomizeBoardSeeded(board, seed, density = RANDOM_DENSITY) {
  return randomizeBoard(board, density, seededRandom(seed));
}

export function stepBoard(board) {
  if (board.population === 0) {
    return freezeBoard(board.columns, board.rows, new Set(), board.generation + 1);
  }

  // One byte stores the live bit plus the 0..8 neighbor count. The candidate list
  // lets us inspect only cells touched by a live neighbor instead of scanning the board.
  const cellStates = new Uint8Array(board.columns * board.rows);
  const candidates = new Uint32Array(cellStates.length);
  let candidateCount = 0;
  for (const index of board.livingCells) {
    cellStates[index] |= LIVE_CELL_MASK;
    const row = Math.floor(index / board.columns);
    const column = index - row * board.columns;
    const firstRow = Math.max(0, row - 1);
    const lastRow = Math.min(board.rows - 1, row + 1);
    const firstColumn = Math.max(0, column - 1);
    const lastColumn = Math.min(board.columns - 1, column + 1);
    for (let neighborRow = firstRow; neighborRow <= lastRow; neighborRow++) {
      for (let neighborColumn = firstColumn; neighborColumn <= lastColumn; neighborColumn++) {
        const neighborIndex = neighborRow * board.columns + neighborColumn;
        if (neighborIndex === index) continue;
        if ((cellStates[neighborIndex] & NEIGHBOR_COUNT_MASK) === 0) {
          candidates[candidateCount++] = neighborIndex;
        }
        cellStates[neighborIndex]++;
      }
    }
  }

  const livingCells = new Set();
  for (let candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
    const index = candidates[candidateIndex];
    const state = cellStates[index];
    const neighbors = state & NEIGHBOR_COUNT_MASK;
    if (neighbors === 3 || (neighbors === 2 && (state & LIVE_CELL_MASK) !== 0)) {
      livingCells.add(index);
    }
  }
  return freezeBoard(board.columns, board.rows, livingCells, board.generation + 1);
}

export function afterGenerations(board, generations) {
  let next = board;
  for (let index = 0; index < generations; index++) next = stepBoard(next);
  return next;
}
