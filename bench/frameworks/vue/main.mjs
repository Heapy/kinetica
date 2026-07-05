import { createApp, ref, shallowRef, triggerRef } from "vue/dist/vue.esm-bundler.js";
import { buildData } from "../shared/data.mjs";

const App = {
  template: `
    <div>
      <div class="jumbotron">
        <h1>Vue (keyed)</h1>
        <div class="toolbar">
          <button id="run" @click="run">Create 1,000 rows</button>
          <button id="runlots" @click="runLots">Create 10,000 rows</button>
          <button id="add" @click="add">Append 1,000 rows</button>
          <button id="update" @click="update">Update every 10th row</button>
          <button id="clear" @click="clear">Clear</button>
          <button id="swaprows" @click="swapRows">Swap Rows</button>
        </div>
      </div>
      <table class="test-data">
        <tbody>
          <tr v-for="item of rows" :key="item.id" :class="{ danger: item.id === selected }" :data-id="item.id">
            <td class="col-id">{{ item.id }}</td>
            <td class="col-label"><a class="lbl" @click="select(item.id)">{{ item.label }}</a></td>
            <td class="col-remove"><a class="remove" @click="remove(item.id)"><span class="remove-icon" aria-hidden="true"></span></a></td>
            <td class="col-rest"></td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  setup() {
    const rows = shallowRef([]);
    const selected = ref(0);

    function run() { rows.value = buildData(1000); selected.value = 0; }
    function runLots() { rows.value = buildData(10000); selected.value = 0; }
    function add() { rows.value = rows.value.concat(buildData(1000)); }
    function update() {
      const list = rows.value;
      for (let i = 0; i < list.length; i += 10) {
        list[i] = { ...list[i], label: list[i].label + " !!!" };
      }
      triggerRef(rows);
    }
    function clear() { rows.value = []; selected.value = 0; }
    function swapRows() {
      const list = rows.value;
      if (list.length <= 998) return;
      const next = list.slice();
      const tmp = next[1];
      next[1] = next[998];
      next[998] = tmp;
      rows.value = next;
    }
    function select(id) { selected.value = id; }
    function remove(id) { rows.value = rows.value.filter((item) => item.id !== id); }

    return { rows, selected, run, runLots, add, update, clear, swapRows, select, remove };
  },
};

createApp(App).mount("#main");
