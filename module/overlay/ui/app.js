(function () {
  "use strict";

  var bridge = window.VirtusBridge;
  var state = {};

  function $(sel) { return document.querySelector(sel); }
  function $all(sel) { return document.querySelectorAll(sel); }

  function toast(msg) {
    var el = $("#toast");
    el.textContent = msg;
    el.hidden = false;
    el.style.animation = "none";
    void el.offsetWidth;
    el.style.animation = "";
    setTimeout(function () { el.hidden = true; }, 2300);
  }

  function loadFromBridge() {
    if (!bridge) {
      toast("bridge missing");
      return;
    }
    try {
      var raw = bridge.getConfig();
      state = raw ? JSON.parse(raw) : {};
      applyToForm();
      var zyg = bridge.getZygiskStatus ? bridge.getZygiskStatus() : "";
      $("#statusText").textContent = zyg || "config loaded · local";
    } catch (e) {
      toast("load fail");
    }
  }

  function applyToForm() {
    $all("[data-key]").forEach(function (el) {
      var key = el.getAttribute("data-key");
      var val = state[key];
      if (el.type === "checkbox") {
        el.checked = !!val;
      } else if (val !== undefined && val !== null) {
        el.value = String(val);
      }
    });
  }

  function collectForm() {
    $all("[data-key]").forEach(function (el) {
      var key = el.getAttribute("data-key");
      if (el.type === "checkbox") {
        state[key] = el.checked;
      } else {
        state[key] = el.value.trim();
      }
    });
    return state;
  }

  function save() {
    if (!bridge) return;
    var payload = JSON.stringify(collectForm());
    var ok = bridge.saveConfig(payload);
    toast(ok ? "saved · hooks reload" : "save failed");
    if (ok && bridge.onSaved) bridge.onSaved();
  }

  function setupTabs() {
    $all("#tabs button").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var tab = btn.getAttribute("data-tab");
        $all("#tabs button").forEach(function (b) { b.classList.remove("active"); });
        $all(".panel").forEach(function (p) { p.classList.remove("active"); });
        btn.classList.add("active");
        var panel = document.getElementById("panel-" + tab);
        if (panel) panel.classList.add("active");
      });
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    setupTabs();
    $("#btnSave").addEventListener("click", save);
    $("#btnReload").addEventListener("click", loadFromBridge);
    $("#btnClose").addEventListener("click", function () {
      if (bridge && bridge.closeMenu) bridge.closeMenu();
    });
    loadFromBridge();
  });
})();
