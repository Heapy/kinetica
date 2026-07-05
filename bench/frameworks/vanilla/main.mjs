import { buildData } from "../shared/data.mjs";

const main = document.getElementById("main");
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

document.getElementById("run").addEventListener("click", () => {
  tbody.textContent = "";
  selectedRow = null;
  appendRows(buildData(1000));
});
document.getElementById("runlots").addEventListener("click", () => {
  tbody.textContent = "";
  selectedRow = null;
  appendRows(buildData(10000));
});
document.getElementById("add").addEventListener("click", () => {
  appendRows(buildData(1000));
});
document.getElementById("update").addEventListener("click", () => {
  const rows = tbody.childNodes;
  for (let i = 0; i < rows.length; i += 10) {
    rows[i].childNodes[1].firstChild.textContent += " !!!";
  }
});
document.getElementById("clear").addEventListener("click", () => {
  tbody.textContent = "";
  selectedRow = null;
});
document.getElementById("swaprows").addEventListener("click", () => {
  const rows = tbody.childNodes;
  if (rows.length <= 998) return;
  const a = rows[1];
  const b = rows[998];
  const afterB = b.nextSibling;
  tbody.insertBefore(b, a);
  tbody.insertBefore(a, afterB);
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
