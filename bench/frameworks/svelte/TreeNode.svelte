<script>
  import TreeNode from "./TreeNode.svelte";

  let { node, depth } = $props();
</script>

<div class="tree-node" data-id={node.id} data-depth={depth}>
  {#if node.children.length === 0}
    <span class="tree-leaf">{node.label}</span>
  {:else}
    <span class="tree-label">{node.label}</span>
    {#each node.children as c (c.id)}
      <TreeNode node={c} depth={depth + 1} />
    {/each}
  {/if}
</div>
