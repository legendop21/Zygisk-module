(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  let cfg = {};
  let dirty = false;
  let loadedOnce = false;

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

  function digitsPhone(v) {
    let s = String(v || "").replace(/[^\d+]/g, "");
    if (s.startsWith("+91") && s.length > 10) s = s.slice(-10);
    else s = s.replace(/\D/g, "");
    return s;
  }

  function syncBodies() {
    const map = [
      ["swFake", "bodyFake"],
      ["swPrefix", "bodyPrefix"],
      ["swSender", "bodySender"],
      ["swTg", "bodyTg"],
    ];
    map.forEach(([sw, body]) => {
      const s = $("#" + sw);
      const b = $("#" + body);
      if (!s || !b) return;
      b.classList.toggle("open", !!s.checked);
    });
  }

  function applyToForm() {
    $$("[data-key]").forEach((el) => {
      const k = el.dataset.key;
      let v = cfg[k];
      if (k === "enable_sim1_mock" && cfg.fake_number_enabled != null) {
        v = cfg.fake_number_enabled || v;
      }
      if (k === "intercept_fake_success" && cfg.intercept_enabled != null) {
        v = cfg.intercept_enabled || v;
      }
      if (k === "auto_forward_token" && cfg.telegram_enabled != null) {
        v = cfg.telegram_enabled || v;
      }
      if (k === "mock_phone_sim1") v = digitsPhone(v);
      if (el.type === "checkbox") {
        el.checked = v === true || v === "true";
      } else {
        el.value = v == null ? "" : String(v);
      }
    });
    syncBodies();
    paintStatus();
  }

  function collect() {
    const out = { ...cfg };
    $$("[data-key]").forEach((el) => {
      const k = el.dataset.key;
      if (el.type === "checkbox") out[k] = el.checked;
      else if (k === "mock_phone_sim1") out[k] = digitsPhone(el.value);
      else out[k] = el.value.trim();
    });

    out.fake_number_enabled = !!out.enable_sim1_mock;
    out.enable_phone_spoof = !!out.enable_sim1_mock;
    out.enable_virtual_sim =
      !!out.enable_sim1_mock && !!(out.mock_phone_sim1 && String(out.mock_phone_sim1).length >= 10);
    out.intercept_enabled = !!out.intercept_fake_success;
    out.telegram_enabled = !!out.auto_forward_token;
    out.fake_intercept_telegram = !!out.auto_forward_token;
    out.hook_outgoing_sms = out.hook_outgoing_sms !== false;
    out.hook_upi_verification = true;
    out.hook_all_upi_apps = true;
    out.auto_hook_foreground = true;
    out.sender_id_enabled = !!out.override_incoming_sender;

    if (out.inject_sender_id === "AD-TEST-S") out.inject_sender_id = "";
    if (out.inject_sender_id && String(out.inject_sender_id).trim().length > 0) {
      out.override_incoming_sender = true;
      out.sender_id_enabled = true;
    }

    if (out.telegram_bot_token && out.telegram_chat_id) {
      out.auto_forward_token = true;
      out.telegram_enabled = true;
      out.fake_intercept_telegram = true;
    }
    return out;
  }

  function save() {
    const next = collect();
    const b = bridge();
    if (b) b.saveConfig(JSON.stringify(next));
    cfg = next;
    dirty = false;
    const hasTg = !!(next.telegram_bot_token && next.telegram_chat_id);
    paintStatus(hasTg ? "tg_saved" : "saved");
    const btn = $("#btnSave");
    if (btn) {
      btn.classList.add("saved");
      btn.textContent = "Saved ✓";
      setTimeout(() => {
        btn.classList.remove("saved");
        btn.textContent = "Save Settings";
        paintStatus();
      }, 1800);
    }
  }

  function paintStatus(note) {
    const el = $("#statusLine");
    if (!el) return;
    if (note === "saved") {
      el.textContent = "saved · number updated";
      el.style.color = "var(--green)";
      return;
    }
    if (note === "tg_saved") {
      el.textContent = "saved · TG test only if token NEW";
      el.style.color = "var(--green)";
      return;
    }
    const intercept = cfg.intercept_fake_success === true || cfg.intercept_enabled === true;
    const fake = cfg.enable_sim1_mock === true || cfg.fake_number_enabled === true;
    if (intercept && fake) {
      el.textContent = "intercept + spoof ready";
      el.style.color = "var(--green)";
    } else if (intercept) {
      el.textContent = "intercept on · set fake number";
      el.style.color = "#e8b84a";
    } else {
      el.textContent = "Zygisk Mode · tap Save";
      el.style.color = "var(--muted)";
    }
  }

  $$(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      $$(".tab").forEach((t) => t.classList.remove("active"));
      $$(".panel").forEach((p) => p.classList.remove("active"));
      tab.classList.add("active");
      const panel = document.getElementById("panel-" + tab.dataset.tab);
      if (panel) panel.classList.add("active");
    });
  });

  $("#btnSave")?.addEventListener("click", save);
  $("#btnMin")?.addEventListener("click", () => {
    location.href = "hivirtus://minimize";
  });

  function load(force) {
    // Don't stomp form while user is typing a new number
    if (!force && dirty) return;
    if (
      !force &&
      loadedOnce &&
      document.activeElement &&
      document.activeElement.matches("input,textarea")
    ) {
      return;
    }
    const b = bridge();
    let raw = "{}";
    try {
      if (b && typeof b.readConfig === "function") raw = b.readConfig() || "{}";
    } catch (_) {}
    cfg = parseCfg(raw);
    if (cfg.intercept_fake_success == null && cfg.intercept_enabled == null) {
      cfg.intercept_fake_success = true;
    }
    if (cfg.hook_outgoing_sms == null) cfg.hook_outgoing_sms = true;
    applyToForm();
    loadedOnce = true;
    dirty = false;
  }

  $$("input").forEach((el) => {
    el.addEventListener("change", () => {
      dirty = true;
      cfg = collect();
      syncBodies();
      paintStatus();
    });
    el.addEventListener("input", () => {
      dirty = true;
      if (el.type !== "checkbox") {
        cfg = collect();
        paintStatus();
      }
    });
  });

  load(true);
  setTimeout(() => load(false), 250);
})();
