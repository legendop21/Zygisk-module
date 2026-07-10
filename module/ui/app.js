(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  let cfg = {};

  function bridge() {
    return typeof Hivirtus !== "undefined" ? Hivirtus : null;
  }

  function parseCfg(raw) {
    try {
      return JSON.parse(raw || "{}");
    } catch (_) {
      return {};
    }
  }

  function load() {
    const b = bridge();
    cfg = b ? parseCfg(b.readConfig()) : {};
    applyToForm();
    paintStatus();
  }

  function applyToForm() {
    $$("[data-key]").forEach((el) => {
      const k = el.dataset.key;
      const v = cfg[k];
      if (el.type === "checkbox") {
        el.checked = v === true || v === "true";
      } else {
        el.value = v == null ? "" : String(v);
      }
    });
  }

  function collect() {
    const out = { ...cfg };
    $$("[data-key]").forEach((el) => {
      const k = el.dataset.key;
      if (el.type === "checkbox") out[k] = el.checked;
      else out[k] = el.value.trim();
    });
    if (out.enable_sim1_mock || (out.mock_phone_sim1 && out.mock_phone_sim1.length === 10)) {
      out.enable_virtual_sim = true;
      out.enable_phone_spoof = true;
    }
    out.hook_upi_verification = true;
    out.hook_all_upi_apps = true;
    out.auto_hook_foreground = true;
    return out;
  }

  function save() {
    const next = collect();
    const b = bridge();
    if (b) b.saveConfig(JSON.stringify(next));
    cfg = next;
    paintStatus("saved");
  }

  function paintStatus(note) {
    const el = $("#statusLine");
    if (!el) return;
    const intercept = cfg.intercept_fake_success === true || cfg.intercept_fake_success === "true";
    const mock = cfg.mock_phone_sim1 && String(cfg.mock_phone_sim1).length === 10;
    if (note === "saved") {
      el.textContent = "config saved";
      el.style.color = "var(--ok)";
      return;
    }
    if (intercept && mock) {
      el.textContent = "intercept ready";
      el.style.color = "var(--ok)";
    } else if (intercept) {
      el.textContent = "intercept on · set mock number";
      el.style.color = "var(--warn)";
    } else {
      el.textContent = "partial setup";
      el.style.color = "var(--muted)";
    }
  }

  $$(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      $$(".tab").forEach((t) => t.classList.remove("active"));
      $$(".panel").forEach((p) => p.classList.remove("active"));
      tab.classList.add("active");
      const id = "panel-" + tab.dataset.tab;
      const panel = document.getElementById(id);
      if (panel) panel.classList.add("active");
    });
  });

  $("#btnSave")?.addEventListener("click", save);
  $("#btnMin")?.addEventListener("click", () => {
    location.href = "hivirtus://minimize";
  });

  $$("input").forEach((el) => {
    el.addEventListener("change", () => {
      cfg = collect();
      paintStatus();
    });
  });

  load();
})();
