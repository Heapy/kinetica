import { render } from "preact";
import { useCallback, useState } from "preact/hooks";
import { buildTree, updateLeaves } from "../shared/tree-data.mjs";

// No memo on TreeNode by contract: noop measures a full re-render of all 1555
// components with unchanged data.
function TreeNode({ node, depth }) {
  return (
    <div className="tree-node" data-id={node.id} data-depth={depth}>
      {node.children.length === 0
        ? <span className="tree-leaf">{node.label}</span>
        : (
          <>
            <span className="tree-label">{node.label}</span>
            {node.children.map((c) => <TreeNode key={c.id} node={c} depth={depth + 1} />)}
          </>
        )}
    </div>
  );
}

function App() {
  const [tree, setTree] = useState(null);
  const [tick, setTick] = useState(0);

  const run = useCallback(() => setTree(buildTree()), []);
  const update = () => {
    const next = tick + 1;
    setTick(next);
    setTree(updateLeaves(tree, next));
  };
  const reverse = () => setTree({ ...tree, children: tree.children.slice().reverse() });
  const noop = () => setTick(tick + 1);

  return (
    <div>
      <div className="jumbotron">
        <h1>Preact tree</h1>
        <div className="toolbar">
          <button id="run" onClick={run}>Create tree</button>
          <button id="update" onClick={update}>Update leaves</button>
          <button id="reverse" onClick={reverse}>Reverse</button>
          <button id="noop" onClick={noop}>No-op render</button>
          <span id="status">{tick}</span>
        </div>
      </div>
      <div className="tree-root">
        {tree && <TreeNode node={tree} depth={0} />}
      </div>
    </div>
  );
}

render(<App />, document.getElementById("main"));
