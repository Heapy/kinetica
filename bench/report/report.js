(function () {
  var tip = document.getElementById("tip");
  document.addEventListener("mousemove", function (e) {
    var row = e.target.closest ? e.target.closest("[data-tip]") : null;
    if (row && row.getAttribute("data-tip")) {
      tip.textContent = row.getAttribute("data-tip");
      tip.style.display = "block";
      var x = Math.min(e.clientX + 14, window.innerWidth - tip.offsetWidth - 8);
      var y = Math.min(e.clientY + 14, window.innerHeight - tip.offsetHeight - 8);
      tip.style.left = x + "px";
      tip.style.top = y + "px";
    } else {
      tip.style.display = "none";
    }
  });
})();
