import { render } from "preact";
import { memo } from "preact/compat";
import { useCallback, useState } from "preact/hooks";
import { buildData } from "../shared/data.mjs";

const Row = memo(function Row({ item, selected, onSelect, onRemove }) {
  return (
    <tr className={selected ? "danger" : ""} data-id={item.id}>
      <td className="col-id">{item.id}</td>
      <td className="col-label">
        <a className="lbl" onClick={() => onSelect(item.id)}>{item.label}</a>
      </td>
      <td className="col-remove">
        <a className="remove" onClick={() => onRemove(item.id)}>
          <span className="remove-icon" aria-hidden="true"></span>
        </a>
      </td>
      <td className="col-rest"></td>
    </tr>
  );
});

function App() {
  const [rows, setRows] = useState([]);
  const [selected, setSelected] = useState(0);

  const run = useCallback(() => { setRows(buildData(1000)); setSelected(0); }, []);
  const runLots = useCallback(() => { setRows(buildData(10000)); setSelected(0); }, []);
  const add = useCallback(() => { setRows((r) => r.concat(buildData(1000))); }, []);
  const update = useCallback(() => {
    setRows((r) => {
      const next = r.slice();
      for (let i = 0; i < next.length; i += 10) {
        next[i] = { ...next[i], label: next[i].label + " !!!" };
      }
      return next;
    });
  }, []);
  const clear = useCallback(() => { setRows([]); setSelected(0); }, []);
  const swapRows = useCallback(() => {
    setRows((r) => {
      if (r.length <= 998) return r;
      const next = r.slice();
      const tmp = next[1];
      next[1] = next[998];
      next[998] = tmp;
      return next;
    });
  }, []);
  const select = useCallback((id) => setSelected(id), []);
  const remove = useCallback((id) => {
    setRows((r) => r.filter((item) => item.id !== id));
  }, []);

  return (
    <div>
      <div className="jumbotron">
        <h1>Preact (keyed)</h1>
        <div className="toolbar">
          <button id="run" onClick={run}>Create 1,000 rows</button>
          <button id="runlots" onClick={runLots}>Create 10,000 rows</button>
          <button id="add" onClick={add}>Append 1,000 rows</button>
          <button id="update" onClick={update}>Update every 10th row</button>
          <button id="clear" onClick={clear}>Clear</button>
          <button id="swaprows" onClick={swapRows}>Swap Rows</button>
        </div>
      </div>
      <table className="test-data">
        <tbody>
          {rows.map((item) => (
            <Row
              key={item.id}
              item={item}
              selected={selected === item.id}
              onSelect={select}
              onRemove={remove}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

render(<App />, document.getElementById("main"));
