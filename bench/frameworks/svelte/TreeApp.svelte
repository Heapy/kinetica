<script>
  import TreeNode from "./TreeNode.svelte";
  import { buildTree, updateLeaves } from "../shared/tree-data.mjs";

  let tree = $state.raw(null);
  let tick = $state(0);

  function run() { tree = buildTree(); }
  function update() {
    tick += 1;
    tree = updateLeaves(tree, tick);
  }
  function reverse() { tree = { ...tree, children: tree.children.slice().reverse() }; }
  function noop() { tick += 1; }
</script>

<div>
  <div class="jumbotron">
    <h1>Svelte tree</h1>
    <div class="toolbar">
      <button id="run" onclick={run}>Create tree</button>
      <button id="update" onclick={update}>Update leaves</button>
      <button id="reverse" onclick={reverse}>Reverse</button>
      <button id="noop" onclick={noop}>No-op render</button>
      <span id="status">{tick}</span>
    </div>
  </div>
  <div class="tree-root">
    {#if tree}
      <TreeNode node={tree} depth={0} />
    {/if}
  </div>
</div>
