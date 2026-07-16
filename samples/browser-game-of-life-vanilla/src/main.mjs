import {
  BOARD_COLUMNS,
  BOARD_ROWS,
  LIFE_PRESETS,
  LifeSeedSequence,
  SIMULATION_SPEEDS,
  cellCoordinates,
  clearBoard,
  createBoard,
  loadPreset,
  randomizeBoardSeeded,
  stepBoard,
  toggleCell,
} from "../../game-of-life-shared/life-core.mjs";

const main = document.getElementById("app");
const NORMAL_SPEED = SIMULATION_SPEEDS.find((speed) => speed.id === "normal");
const PULSAR = LIFE_PRESETS.find((preset) => preset.id === "pulsar");
const TOTAL_CELLS = BOARD_COLUMNS * BOARD_ROWS;

let cleanup = null;

function centerBoardViewport() {
  requestAnimationFrame(() => {
    const viewport = document.querySelector(".board-scroll");
    if (viewport) viewport.scrollLeft = (viewport.scrollWidth - viewport.clientWidth) / 2;
  });
}

function presetMarkup(preset, selected) {
  return `
    <div class="preset-option${selected ? " is-selected" : ""}" data-preset-option="${preset.id}">
      <button
        type="button"
        aria-label="Load ${preset.displayName}"
        aria-pressed="${selected}"
        data-preset="${preset.id}"
        data-testid="preset-${preset.id}"
      >
        <span class="preset-title">
          ${preset.displayName}
          <span class="preset-size">${preset.columns} × ${preset.rows}</span>
        </span>
        <span class="preset-category">${preset.category}</span>
        <span class="preset-description">${preset.description}</span>
      </button>
    </div>`;
}

function cellMarkup(index, alive) {
  const { column, row } = cellCoordinates(index);
  return `<button
    type="button"
    class="life-cell${alive ? " is-alive" : ""}"
    aria-label="Column ${column + 1}, row ${row + 1}"
    aria-pressed="${alive}"
    data-state-description="${alive ? "Alive" : "Dead"}"
    data-cell-index="${index}"
    data-testid="cell-${column}-${row}"
  ></button>`;
}

function appMarkup(board) {
  return `
    <div class="life-app" data-testid="game-of-life-app">
      <header class="hero">
        <div class="brand-line">
          <span class="brand-mark" aria-hidden="true">V</span>
          <span class="eyebrow">Vanilla playground</span>
          <span class="rule-chip">B3 / S23</span>
        </div>
        <div class="hero-copy">
          <h1>Conway’s Game of Life</h1>
          <p>Seed a world, set it in motion, and watch four simple rules create complex behavior.</p>
        </div>
      </header>

      <div class="workspace">
        <aside class="preset-panel" aria-label="Pattern presets">
          <div class="section-heading">
            <span class="section-kicker">Start here</span>
            <h2>Classic patterns</h2>
            <p>Load a known form, then run it or reshape it cell by cell.</p>
          </div>
          <div class="preset-list">
            ${LIFE_PRESETS.map((preset) => presetMarkup(preset, preset.id === PULSAR.id)).join("")}
          </div>
          <div class="tip-card">
            <span class="tip-icon" aria-hidden="true">✦</span>
            <p>Tip: select any square on the board to toggle it. Editing pauses the simulation.</p>
          </div>
        </aside>

        <main class="simulation-panel">
          <div class="control-bar">
            <div class="primary-actions">
              <button type="button" data-testid="toggle-running" aria-label="Run simulation">
                <span aria-hidden="true" class="button-icon">▶</span><span data-role="run-label">Run</span>
              </button>
              <button type="button" data-testid="step" aria-label="Advance one generation">
                <span aria-hidden="true" class="button-icon">↦</span>Step
              </button>
            </div>
            <div class="secondary-actions">
              <button type="button" data-testid="randomize">Randomize</button>
              <button type="button" data-testid="clear">Clear</button>
            </div>
            <div class="speed-control" aria-label="Simulation speed">
              <span class="speed-label">Speed</span>
              <div class="speed-options">
                ${SIMULATION_SPEEDS.map((speed) => `
                  <button
                    type="button"
                    data-speed="${speed.id}"
                    data-testid="speed-${speed.id}"
                    aria-label="${speed.label} speed"
                    aria-pressed="${speed.id === NORMAL_SPEED.id}"
                    ${speed.id === NORMAL_SPEED.id ? "disabled" : ""}
                  >${speed.label}</button>`).join("")}
              </div>
            </div>
          </div>

          <div class="status-strip" aria-live="polite">
            <div class="status-item">
              <span class="status-label">Generation</span>
              <strong data-testid="generation-value">${board.generation}</strong>
            </div>
            <div class="status-item">
              <span class="status-label">Population</span>
              <strong data-testid="population-value">${board.population}</strong>
            </div>
            <div class="status-item pattern-status">
              <span class="status-label">Pattern</span>
              <strong data-role="pattern-name">${PULSAR.displayName}</strong>
            </div>
            <div class="run-state">
              <span class="state-dot" data-testid="run-state-indicator"></span>
              <span data-role="run-state-label">Paused</span>
            </div>
          </div>

          <div class="board-frame">
            <div class="board-chrome">
              <div class="board-caption">
                <span>${BOARD_COLUMNS} × ${BOARD_ROWS} universe</span>
                <span class="coordinate-hint">Finite boundary · outside cells stay dead</span>
              </div>
              <div class="board-scroll">
                <div class="life-grid" role="grid" aria-label="Editable Game of Life grid" data-testid="life-grid">
                  ${Array.from({ length: TOTAL_CELLS }, (_, index) =>
                    cellMarkup(index, board.livingCells.has(index))).join("")}
                </div>
              </div>
            </div>
          </div>

          <div class="rule-legend">
            <div><span class="legend-number">3</span><p>A dead cell with three neighbors is born.</p></div>
            <div><span class="legend-number">2–3</span><p>A live cell with two or three neighbors survives.</p></div>
            <div><span class="legend-number muted-number">0–1 / 4+</span><p>Every other live cell fades from the field.</p></div>
          </div>
        </main>
      </div>
    </div>`;
}

function mountApp() {
  cleanup?.();

  let board = loadPreset(createBoard(), PULSAR);
  let running = false;
  let speed = NORMAL_SPEED;
  let selectedPresetId = PULSAR.id;
  const randomSeeds = new LifeSeedSequence();
  let timer = 0;

  main.innerHTML = appMarkup(board);
  const cells = [...main.querySelectorAll("[data-cell-index]")];
  const generationValue = main.querySelector('[data-testid="generation-value"]');
  const populationValue = main.querySelector('[data-testid="population-value"]');
  const patternName = main.querySelector('[data-role="pattern-name"]');
  const runButton = main.querySelector('[data-testid="toggle-running"]');
  const runLabel = main.querySelector('[data-role="run-label"]');
  const runIcon = runButton.querySelector(".button-icon");
  const clearButton = main.querySelector('[data-testid="clear"]');
  const runStateIndicator = main.querySelector('[data-testid="run-state-indicator"]');
  const runStateLabel = main.querySelector('[data-role="run-state-label"]');

  function stopTimer() {
    window.clearTimeout(timer);
    timer = 0;
  }

  function render(previousBoard = null) {
    generationValue.textContent = String(board.generation);
    populationValue.textContent = String(board.population);
    const selectedPreset = LIFE_PRESETS.find((preset) => preset.id === selectedPresetId);
    patternName.textContent = selectedPreset?.displayName ?? "Custom field";

    runButton.disabled = board.population === 0;
    runButton.setAttribute("aria-label", running ? "Pause simulation" : "Run simulation");
    runLabel.textContent = running ? "Pause" : "Run";
    runIcon.textContent = running ? "Ⅱ" : "▶";
    clearButton.disabled = board.population === 0;
    runStateIndicator.className = running ? "state-dot is-running" : "state-dot";
    runStateLabel.textContent = running ? "Evolving" : "Paused";

    for (const preset of LIFE_PRESETS) {
      const option = main.querySelector(`[data-preset-option="${preset.id}"]`);
      const button = option.querySelector("button");
      const selected = preset.id === selectedPresetId;
      option.classList.toggle("is-selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    }

    for (const option of SIMULATION_SPEEDS) {
      const button = main.querySelector(`[data-speed="${option.id}"]`);
      const selected = option.id === speed.id;
      button.disabled = selected;
      button.setAttribute("aria-pressed", String(selected));
    }

    for (let index = 0; index < cells.length; index++) {
      const alive = board.livingCells.has(index);
      if (previousBoard && previousBoard.livingCells.has(index) === alive) continue;
      cells[index].classList.toggle("is-alive", alive);
      cells[index].setAttribute("aria-pressed", String(alive));
      cells[index].setAttribute("data-state-description", alive ? "Alive" : "Dead");
    }
  }

  function replaceBoard(nextBoard) {
    const previousBoard = board;
    board = nextBoard;
    render(previousBoard);
  }

  function scheduleTick() {
    stopTimer();
    if (!running) return;
    timer = window.setTimeout(() => {
      const next = stepBoard(board);
      if (next.population === 0) running = false;
      replaceBoard(next);
      scheduleTick();
    }, speed.delayMs);
  }

  function pause() {
    running = false;
    stopTimer();
  }

  function handleClick(event) {
    const button = event.target.closest("button");
    if (!button || !main.contains(button)) return;

    const presetId = button.dataset.preset;
    if (presetId) {
      pause();
      selectedPresetId = presetId;
      replaceBoard(loadPreset(board, presetId));
      centerBoardViewport();
      return;
    }

    const speedId = button.dataset.speed;
    if (speedId) {
      speed = SIMULATION_SPEEDS.find((option) => option.id === speedId);
      render(board);
      scheduleTick();
      return;
    }

    if (button.dataset.cellIndex !== undefined) {
      pause();
      selectedPresetId = null;
      replaceBoard(toggleCell(board, Number(button.dataset.cellIndex)));
      return;
    }

    switch (button.dataset.testid) {
      case "toggle-running":
        if (board.population > 0) {
          running = !running;
          render(board);
          scheduleTick();
        }
        break;
      case "step":
        pause();
        replaceBoard(stepBoard(board));
        break;
      case "randomize":
        pause();
        selectedPresetId = null;
        replaceBoard(randomizeBoardSeeded(board, randomSeeds.take()));
        break;
      case "clear":
        pause();
        selectedPresetId = null;
        replaceBoard(clearBoard(board));
        break;
    }
  }

  main.addEventListener("click", handleClick);
  centerBoardViewport();
  cleanup = () => {
    stopTimer();
    main.removeEventListener("click", handleClick);
    main.textContent = "";
  };
}

mountApp();
window.__mount = mountApp;
window.__unmount = () => {
  cleanup?.();
  cleanup = null;
};
