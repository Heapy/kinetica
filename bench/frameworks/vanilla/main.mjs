import { buildData } from "../shared/data.mjs";

const main = document.getElementById("main");

let cleanup = null;

function mountApp() {
  main.innerHTML = `
    <div>
      <div class="jumbotron">
        <h1>Vanilla JS (keyed)</h1>
        <div class="toolbar">
          <button id="run">Create 1,000 rows</button>
          <button id="runlots">Create 10,000 rows</button>
          <button id="add">Append 1,000 rows</button>
          <button id="update">Update every 10th row</button>
          <button id="clear">Clear</button>
          <button id="swaprows">Swap Rows</button>
          <button id="animate">Animate</button>
        </div>
      </div>
      <table class="test-data"><tbody></tbody></table>
    </div>
  `;

  const tbody = main.querySelector("tbody");
  const rowTemplate = document.createElement("tr");
  rowTemplate.innerHTML =
    '<td class="col-id"></td>' +
    '<td class="col-label"><a class="lbl"></a></td>' +
    '<td class="col-remove"><a class="remove"><span class="remove-icon" aria-hidden="true"></span></a></td>' +
    '<td class="col-rest"></td>';

  let selectedRow = null;
  let animating = false;
  let rafId = 0;
  let animTick = 0;

  function createRow(item) {
    const tr = rowTemplate.cloneNode(true);
    tr.dataset.id = item.id;
    tr.firstChild.textContent = item.id;
    tr.childNodes[1].firstChild.textContent = item.label;
    return tr;
  }

  function appendRows(items) {
    const fragment = document.createDocumentFragment();
    for (const item of items) fragment.appendChild(createRow(item));
    tbody.appendChild(fragment);
  }

  function animateStep() {
    const rows = tbody.childNodes;
    animTick++;
    for (let i = 0; i < rows.length; i += 10) {
      const label = rows[i].childNodes[1].firstChild;
      label.textContent = label.textContent.split(" !")[0] + " !" + animTick;
    }
    rafId = requestAnimationFrame(animateStep);
  }

  main.querySelector("#run").addEventListener("click", () => {
    tbody.textContent = "";
    selectedRow = null;
    appendRows(buildData(1000));
  });
  main.querySelector("#runlots").addEventListener("click", () => {
    tbody.textContent = "";
    selectedRow = null;
    appendRows(buildData(10000));
  });
  main.querySelector("#add").addEventListener("click", () => {
    appendRows(buildData(1000));
  });
  main.querySelector("#update").addEventListener("click", () => {
    const rows = tbody.childNodes;
    for (let i = 0; i < rows.length; i += 10) {
      rows[i].childNodes[1].firstChild.textContent += " !!!";
    }
  });
  main.querySelector("#clear").addEventListener("click", () => {
    tbody.textContent = "";
    selectedRow = null;
  });
  main.querySelector("#swaprows").addEventListener("click", () => {
    const rows = tbody.childNodes;
    if (rows.length <= 998) return;
    const a = rows[1];
    const b = rows[998];
    const afterB = b.nextSibling;
    tbody.insertBefore(b, a);
    tbody.insertBefore(a, afterB);
  });
  main.querySelector("#animate").addEventListener("click", () => {
    animating = !animating;
    if (animating) {
      rafId = requestAnimationFrame(animateStep);
    } else {
      cancelAnimationFrame(rafId);
    }
  });
  tbody.addEventListener("click", (e) => {
    const target = e.target;
    const row = target.closest("tr");
    if (!row) return;
    if (target.closest("a.remove")) {
      row.remove();
      if (selectedRow === row) selectedRow = null;
    } else if (target.closest("a.lbl")) {
      if (selectedRow) selectedRow.classList.remove("danger");
      row.classList.add("danger");
      selectedRow = row;
    }
  });

  cleanup = () => {
    cancelAnimationFrame(rafId);
    main.textContent = "";
  };
}

mountApp();
window.__mount = mountApp;
window.__unmount = () => {
  if (cleanup) cleanup();
  cleanup = null;
};
