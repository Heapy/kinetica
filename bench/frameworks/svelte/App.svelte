<script>
  import { onDestroy } from "svelte";
  import { buildData } from "../shared/data.mjs";

  let rows = $state.raw([]);
  let selected = $state(0);

  function run() { rows = buildData(1000); selected = 0; }
  function runLots() { rows = buildData(10000); selected = 0; }
  function add() { rows = rows.concat(buildData(1000)); }
  function update() {
    const next = rows.slice();
    for (let i = 0; i < next.length; i += 10) {
      next[i] = { ...next[i], label: next[i].label + " !!!" };
    }
    rows = next;
  }
  function clear() { rows = []; selected = 0; }
  function swapRows() {
    if (rows.length <= 998) return;
    const next = rows.slice();
    const tmp = next[1];
    next[1] = next[998];
    next[998] = tmp;
    rows = next;
  }
  function select(id) { selected = id; }
  function remove(id) { rows = rows.filter((item) => item.id !== id); }

  let animating = false;
  let raf = 0;
  let animTick = 0;
  function animateStep() {
    const next = rows.slice();
    animTick++;
    for (let i = 0; i < next.length; i += 10) {
      next[i] = { ...next[i], label: next[i].label.split(" !")[0] + " !" + animTick };
    }
    rows = next;
    raf = requestAnimationFrame(animateStep);
  }
  function animate() {
    animating = !animating;
    if (animating) {
      raf = requestAnimationFrame(animateStep);
    } else {
      cancelAnimationFrame(raf);
    }
  }
  onDestroy(() => cancelAnimationFrame(raf));
</script>

<div>
  <div class="jumbotron">
    <h1>Svelte (keyed)</h1>
    <div class="toolbar">
      <button id="run" onclick={run}>Create 1,000 rows</button>
      <button id="runlots" onclick={runLots}>Create 10,000 rows</button>
      <button id="add" onclick={add}>Append 1,000 rows</button>
      <button id="update" onclick={update}>Update every 10th row</button>
      <button id="clear" onclick={clear}>Clear</button>
      <button id="swaprows" onclick={swapRows}>Swap Rows</button>
      <button id="animate" onclick={animate}>Animate</button>
    </div>
  </div>
  <table class="test-data">
    <tbody>
      {#each rows as item (item.id)}
        <tr class={item.id === selected ? "danger" : ""} data-id={item.id}>
          <td class="col-id">{item.id}</td>
          <td class="col-label"><a class="lbl" onclick={() => select(item.id)}>{item.label}</a></td>
          <td class="col-remove"><a class="remove" onclick={() => remove(item.id)}><span class="remove-icon" aria-hidden="true"></span></a></td>
          <td class="col-rest"></td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>
