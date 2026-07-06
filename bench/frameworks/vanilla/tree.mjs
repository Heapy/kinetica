import { buildTree, updateLeaves } from "../shared/tree-data.mjs";

const main = document.getElementById("main");
main.innerHTML = `
  <div>
    <div class="jumbotron">
      <h1>Vanilla JS tree</h1>
      <div class="toolbar">
        <button id="run">Create tree</button>
        <button id="update">Update leaves</button>
        <button id="reverse">Reverse</button>
        <button id="noop">No-op render</button>
        <span id="status">0</span>
      </div>
    </div>
    <div class="tree-root"></div>
  </div>
`;

const treeRoot = main.querySelector(".tree-root");
const status = main.querySelector("#status");

let tree = null;
let tick = 0;
let leafSpans = [];

function renderNode(node, depth) {
  const el = document.createElement("div");
  el.className = "tree-node";
  el.dataset.id = node.id;
  el.dataset.depth = depth;
  const span = document.createElement("span");
  span.textContent = node.label;
  if (node.children.length === 0) {
    span.className = "tree-leaf";
    leafSpans.push(span);
    el.appendChild(span);
  } else {
    span.className = "tree-label";
    el.appendChild(span);
    for (const child of node.children) el.appendChild(renderNode(child, depth + 1));
  }
  return el;
}

document.getElementById("run").addEventListener("click", () => {
  tree = buildTree();
  leafSpans = [];
  treeRoot.textContent = "";
  treeRoot.appendChild(renderNode(tree, 0));
});
document.getElementById("update").addEventListener("click", () => {
  if (!tree) return;
  tick++;
  tree = updateLeaves(tree, tick);
  for (let i = 0; i < leafSpans.length; i += 10) {
    const span = leafSpans[i];
    span.textContent = span.textContent.split(" !")[0] + " !" + tick;
  }
  status.textContent = tick;
});
document.getElementById("reverse").addEventListener("click", () => {
  if (!tree) return;
  tree = { ...tree, children: tree.children.slice().reverse() };
  const rootEl = treeRoot.firstChild;
  const subtrees = Array.from(rootEl.querySelectorAll(":scope > .tree-node"));
  for (const subtree of subtrees.reverse()) rootEl.appendChild(subtree);
});
document.getElementById("noop").addEventListener("click", () => {
  tick++;
  status.textContent = tick;
});
