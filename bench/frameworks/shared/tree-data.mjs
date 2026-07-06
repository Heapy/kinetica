// Shared tree generator for the deep-tree benchmark. Contract (mirrored in Kotlin in
// samples/browser-bench): depth 4, fanout 6 => 1555 nodes, 1296 leaves; ids assigned
// in preorder from a global counter; "update" re-labels every 10th leaf (preorder)
// with " !<tick>", replacing any previous suffix so per-frame work stays constant.

export const TREE_DEPTH = 4;
export const TREE_FANOUT = 6;

let nextId = 1;

export function buildTree(depth = TREE_DEPTH, fanout = TREE_FANOUT) {
  const id = nextId++;
  return {
    id,
    label: "node " + id,
    children: depth === 0 ? [] : Array.from({ length: fanout }, () => buildTree(depth - 1, fanout)),
  };
}

export function updateLeaves(node, tick, counter = { n: 0 }) {
  if (node.children.length === 0) {
    const index = counter.n++;
    return index % 10 === 0
      ? { ...node, label: node.label.split(" !")[0] + " !" + tick }
      : node;
  }
  return { ...node, children: node.children.map((c) => updateLeaves(c, tick, counter)) };
}
