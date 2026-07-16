import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
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

const NORMAL_SPEED = SIMULATION_SPEEDS.find((speed) => speed.id === "normal");
const PULSAR = LIFE_PRESETS.find((preset) => preset.id === "pulsar");
const TOTAL_CELLS = BOARD_COLUMNS * BOARD_ROWS;

function initialBoard() {
  return loadPreset(createBoard(), PULSAR);
}

function centerBoardViewport() {
  requestAnimationFrame(() => {
    const viewport = document.querySelector(".board-scroll");
    if (viewport) viewport.scrollLeft = (viewport.scrollWidth - viewport.clientWidth) / 2;
  });
}

const LifeCell = memo(function LifeCell({ index, alive, onToggle }) {
  const { column, row } = cellCoordinates(index);
  return (
    <button
      type="button"
      className={alive ? "life-cell is-alive" : "life-cell"}
      aria-label={`Column ${column + 1}, row ${row + 1}`}
      aria-pressed={alive}
      data-state-description={alive ? "Alive" : "Dead"}
      data-testid={`cell-${column}-${row}`}
      onClick={() => onToggle(index)}
    />
  );
});

const PresetOption = memo(function PresetOption({ preset, selected, onLoad }) {
  return (
    <div className={selected ? "preset-option is-selected" : "preset-option"}>
      <button
        type="button"
        aria-label={`Load ${preset.displayName}`}
        aria-pressed={selected}
        data-testid={`preset-${preset.id}`}
        onClick={() => onLoad(preset)}
      >
        <span className="preset-title">
          {preset.displayName}
          <span className="preset-size">{preset.columns} × {preset.rows}</span>
        </span>
        <span className="preset-category">{preset.category}</span>
        <span className="preset-description">{preset.description}</span>
      </button>
    </div>
  );
});

function GameOfLifeApp() {
  const [board, setBoard] = useState(initialBoard);
  const [running, setRunning] = useState(false);
  const [speed, setSpeed] = useState(NORMAL_SPEED);
  const [selectedPresetId, setSelectedPresetId] = useState(PULSAR.id);
  const randomSeeds = useRef(new LifeSeedSequence());

  useEffect(centerBoardViewport, []);

  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setTimeout(() => {
      setBoard((current) => {
        const next = stepBoard(current);
        if (next.population === 0) setRunning(false);
        return next;
      });
    }, speed.delayMs);
    return () => window.clearTimeout(timer);
  }, [board, running, speed.delayMs]);

  const selectedPreset = useMemo(
    () => LIFE_PRESETS.find((preset) => preset.id === selectedPresetId) ?? null,
    [selectedPresetId],
  );

  const toggleRunning = useCallback(() => {
    if (board.population > 0) setRunning((current) => !current);
  }, [board.population]);

  const stepOnce = useCallback(() => {
    setRunning(false);
    setBoard((current) => stepBoard(current));
  }, []);

  const randomize = useCallback(() => {
    setRunning(false);
    setSelectedPresetId(null);
    setBoard((current) => randomizeBoardSeeded(current, randomSeeds.current.take()));
  }, []);

  const clear = useCallback(() => {
    setRunning(false);
    setSelectedPresetId(null);
    setBoard((current) => clearBoard(current));
  }, []);

  const selectPreset = useCallback((preset) => {
    setRunning(false);
    setSelectedPresetId(preset.id);
    setBoard((current) => loadPreset(current, preset));
    centerBoardViewport();
  }, []);

  const toggle = useCallback((index) => {
    setRunning(false);
    setSelectedPresetId(null);
    setBoard((current) => toggleCell(current, index));
  }, []);

  return (
    <div className="life-app" data-testid="game-of-life-app">
      <header className="hero">
        <div className="brand-line">
          <span className="brand-mark" aria-hidden="true">R</span>
          <span className="eyebrow">React playground</span>
          <span className="rule-chip">B3 / S23</span>
        </div>
        <div className="hero-copy">
          <h1>Conway’s Game of Life</h1>
          <p>Seed a world, set it in motion, and watch four simple rules create complex behavior.</p>
        </div>
      </header>

      <div className="workspace">
        <aside className="preset-panel" aria-label="Pattern presets">
          <div className="section-heading">
            <span className="section-kicker">Start here</span>
            <h2>Classic patterns</h2>
            <p>Load a known form, then run it or reshape it cell by cell.</p>
          </div>

          <div className="preset-list">
            {LIFE_PRESETS.map((preset) => (
              <PresetOption
                key={preset.id}
                preset={preset}
                selected={preset.id === selectedPresetId}
                onLoad={selectPreset}
              />
            ))}
          </div>

          <div className="tip-card">
            <span className="tip-icon" aria-hidden="true">✦</span>
            <p>Tip: select any square on the board to toggle it. Editing pauses the simulation.</p>
          </div>
        </aside>

        <main className="simulation-panel">
          <div className="control-bar">
            <div className="primary-actions">
              <button
                type="button"
                data-testid="toggle-running"
                aria-label={running ? "Pause simulation" : "Run simulation"}
                disabled={board.population === 0}
                onClick={toggleRunning}
              >
                <span aria-hidden="true" className="button-icon">{running ? "Ⅱ" : "▶"}</span>
                {running ? "Pause" : "Run"}
              </button>
              <button
                type="button"
                data-testid="step"
                aria-label="Advance one generation"
                onClick={stepOnce}
              >
                <span aria-hidden="true" className="button-icon">↦</span>
                Step
              </button>
            </div>

            <div className="secondary-actions">
              <button type="button" data-testid="randomize" onClick={randomize}>Randomize</button>
              <button
                type="button"
                data-testid="clear"
                disabled={board.population === 0}
                onClick={clear}
              >
                Clear
              </button>
            </div>

            <div className="speed-control" aria-label="Simulation speed">
              <span className="speed-label">Speed</span>
              <div className="speed-options">
                {SIMULATION_SPEEDS.map((option) => (
                  <button
                    type="button"
                    key={option.id}
                    data-testid={`speed-${option.id}`}
                    aria-label={`${option.label} speed`}
                    aria-pressed={speed.id === option.id}
                    disabled={speed.id === option.id}
                    onClick={() => setSpeed(option)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="status-strip" aria-live="polite">
            <div className="status-item">
              <span className="status-label">Generation</span>
              <strong data-testid="generation-value">{board.generation}</strong>
            </div>
            <div className="status-item">
              <span className="status-label">Population</span>
              <strong data-testid="population-value">{board.population}</strong>
            </div>
            <div className="status-item pattern-status">
              <span className="status-label">Pattern</span>
              <strong>{selectedPreset?.displayName ?? "Custom field"}</strong>
            </div>
            <div className="run-state">
              <span
                className={running ? "state-dot is-running" : "state-dot"}
                data-testid="run-state-indicator"
              />
              {running ? "Evolving" : "Paused"}
            </div>
          </div>

          <div className="board-frame">
            <div className="board-chrome">
              <div className="board-caption">
                <span>{BOARD_COLUMNS} × {BOARD_ROWS} universe</span>
                <span className="coordinate-hint">Finite boundary · outside cells stay dead</span>
              </div>
              <div className="board-scroll">
                <div
                  className="life-grid"
                  role="grid"
                  aria-label="Editable Game of Life grid"
                  data-testid="life-grid"
                >
                  {Array.from({ length: TOTAL_CELLS }, (_, index) => (
                    <LifeCell
                      key={index}
                      index={index}
                      alive={board.livingCells.has(index)}
                      onToggle={toggle}
                    />
                  ))}
                </div>
              </div>
            </div>
          </div>

          <div className="rule-legend">
            <div>
              <span className="legend-number">3</span>
              <p>A dead cell with three neighbors is born.</p>
            </div>
            <div>
              <span className="legend-number">2–3</span>
              <p>A live cell with two or three neighbors survives.</p>
            </div>
            <div>
              <span className="legend-number muted-number">0–1 / 4+</span>
              <p>Every other live cell fades from the field.</p>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

let root = null;

function mountApp() {
  root = createRoot(document.getElementById("app"));
  root.render(<GameOfLifeApp />);
}

mountApp();
window.__mount = mountApp;
window.__unmount = () => {
  root?.unmount();
  root = null;
};
