import { createApp, ref, shallowRef } from "vue/dist/vue.esm-bundler.js";
import { buildTree, updateLeaves } from "../shared/tree-data.mjs";

const TreeNode = {
  name: "TreeNode",
  props: ["node", "depth"],
  template: `
    <div class="tree-node" :data-id="node.id" :data-depth="depth">
      <span v-if="node.children.length === 0" class="tree-leaf">{{ node.label }}</span>
      <template v-else>
        <span class="tree-label">{{ node.label }}</span>
        <TreeNode v-for="c of node.children" :key="c.id" :node="c" :depth="depth + 1" />
      </template>
    </div>
  `,
};
TreeNode.components = { TreeNode };

const App = {
  components: { TreeNode },
  template: `
    <div>
      <div class="jumbotron">
        <h1>Vue tree</h1>
        <div class="toolbar">
          <button id="run" @click="run">Create tree</button>
          <button id="update" @click="update">Update leaves</button>
          <button id="reverse" @click="reverse">Reverse</button>
          <button id="noop" @click="noop">No-op render</button>
          <span id="status">{{ tick }}</span>
        </div>
      </div>
      <div class="tree-root">
        <TreeNode v-if="tree" :node="tree" :depth="0" />
      </div>
    </div>
  `,
  setup() {
    const tree = shallowRef(null);
    const tick = ref(0);

    function run() { tree.value = buildTree(); }
    function update() {
      tick.value += 1;
      tree.value = updateLeaves(tree.value, tick.value);
    }
    function reverse() { tree.value = { ...tree.value, children: tree.value.children.slice().reverse() }; }
    function noop() { tick.value += 1; }

    return { tree, tick, run, update, reverse, noop };
  },
};

createApp(App).mount("#main");
