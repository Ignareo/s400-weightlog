/*
 * 浏览器演示桥。
 *
 * 手机 App 里原生会注入 window.WeightLog；直接用浏览器打开 assets/www 时没有原生对象，
 * 这里提供一个同接口的内存实现（localStorage 持久化），用于本地预览、截图和演示。
 * 这个文件在真机上不会接管：只要 window.WeightLog 已存在就直接返回。
 */
(function () {
  "use strict";

  if (window.WeightLog) {
    return;
  }

  var STORE_KEY = "weightlog_demo_v2";
  var listeners = [];
  var scanning = false;
  var scanMode = "bind";
  var timers = [];
  var seedProfile = null;

  var params = {};
  (location.search || "").replace(/[?&]([^=&]+)=?([^&]*)/g, function (m, k, v) {
    params[decodeURIComponent(k)] = decodeURIComponent(v || "1");
  });

  function nowMs() {
    return Date.now();
  }

  function pad2(v) {
    return v < 10 ? "0" + v : "" + v;
  }

  function isoTime(ms) {
    var d = new Date(ms);
    var off = -d.getTimezoneOffset();
    var sign = off >= 0 ? "+" : "-";
    var abs = Math.abs(off);
    return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
      "T" + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds()) +
      sign + pad2(Math.floor(abs / 60)) + ":" + pad2(abs % 60);
  }

  function round1(v) {
    return Math.round(v * 10) / 10;
  }

  // 与原生导出一致：公式注入前缀 + 必要的双引号转义
  function csvCell(value) {
    var s = value == null ? "" : String(value);
    if (/^[=+\-@\t\r]/.test(s)) {
      s = "'" + s;
    }
    if (/[",\n\r]/.test(s)) {
      s = '"' + s.replace(/"/g, '""') + '"';
    }
    return s;
  }

  function bodyFat(weight, impedance, heightCm, age, sex) {
    if (!(weight >= 10 && weight <= 200 && impedance > 0 && heightCm > 0 && age > 0)) {
      return null;
    }
    var h = heightCm;
    var lbm = (h * 9.058 / 100) * (h / 100);
    lbm += weight * 0.32 + 12.226;
    lbm -= impedance * 0.0068;
    lbm -= age * 0.0542;
    var female = String(sex || "").toLowerCase() === "female";
    var constant = female ? (age <= 49 ? 9.25 : 7.25) : 0.8;
    var coefficient = 1;
    if (!female && weight < 61) {
      coefficient = 0.98;
    } else if (female && weight > 60) {
      coefficient = 0.96 * (heightCm > 160 ? 1.03 : 1);
    } else if (female && weight < 50) {
      coefficient = 1.02 * (heightCm > 160 ? 1.03 : 1);
    }
    var fat = (1 - (((lbm - constant) * coefficient) / weight)) * 100;
    return round1(Math.min(Math.max(fat, 5), 75));
  }

  function bmi(weight, heightCm) {
    if (weight <= 0 || heightCm <= 0) {
      return null;
    }
    var m = heightCm / 100;
    return round1(weight / (m * m));
  }

  function bmr(weight, heightCm, age, sex) {
    if (weight <= 0 || heightCm <= 0 || age <= 0) {
      return null;
    }
    var female = String(sex || "").toLowerCase() === "female";
    return Math.round(10 * weight + 6.25 * heightCm - 5 * age + (female ? -161 : 5));
  }

  function buildMeasurement(input) {
    var profile = (store && store.profile) || seedProfile || {};
    var m = {
      id: input.id,
      measured_at_ms: input.measured_at_ms,
      measured_at: isoTime(input.measured_at_ms),
      tz_offset_min: -new Date(input.measured_at_ms).getTimezoneOffset(),
      profile_id: 1,
      profile_height_cm: profile.heightCm > 0 ? profile.heightCm : null,
      profile_age: profile.age > 0 ? profile.age : null,
      profile_sex: profile.sex || null,
      created_at_ms: input.measured_at_ms,
      weight_kg: round1(input.weight_kg),
      impedance_low_ohm: input.impedance_low_ohm || null,
      impedance_high_ohm: input.impedance_high_ohm || null,
      heart_rate: input.heart_rate || null,
      stabilized: true,
      body_fat_pct: null,
      bmi: null,
      bmr_kcal: null,
      source: input.source || "demo",
      note: input.note || null,
      raw_json: null,
      calc_version: 1
    };
    var imp = m.impedance_low_ohm || m.impedance_high_ohm;
    if (profile.heightCm > 0) {
      m.bmi = bmi(m.weight_kg, profile.heightCm);
    }
    if (imp && profile.heightCm > 0 && profile.age > 0) {
      m.body_fat_pct = bodyFat(m.weight_kg, imp, profile.heightCm, profile.age, profile.sex);
    }
    if (profile.heightCm > 0 && profile.age > 0) {
      m.bmr_kcal = bmr(m.weight_kg, profile.heightCm, profile.age, profile.sex);
    }
    return m;
  }

  // 与原生 live 负载同形：体成分由「原生」算好推给页面，页面里不再有第三份公式。
  function liveComposition(weight, impedance, profile) {
    var ready = !!(profile && profile.heightCm > 0 && profile.age > 0);
    var result = { bmi: null, body_fat_pct: null, bmr_kcal: null, profile_ready: ready };
    if (!profile || !(profile.heightCm > 0)) {
      return result;
    }
    result.bmi = bmi(weight, profile.heightCm);
    if (ready) {
      result.bmr_kcal = bmr(weight, profile.heightCm, profile.age, profile.sex);
      if (impedance) {
        result.body_fat_pct = bodyFat(weight, impedance, profile.heightCm, profile.age, profile.sex);
      }
    }
    return result;
  }

  function emptyState() {
    return {
      version: "1.2-demo",
      onboarded: false,
      device: { mac: "", name: "Xiaomi Scale S400", bindKeySet: false, tokenSet: false,
        preset: false, presetMac: "", presetName: "" },
      profile: { name: "", heightCm: 0, age: 0, sex: "male", targetWeightKg: 0, ready: false },
      scan: {
        supported: true, btOn: true, permission: true, locationOn: true,
        scanning: false, mode: "bind", rescue: false, wantScan: false, error: null
      },
      records: { count: 0, last: null, previous: null }
    };
  }

  function seedStore() {
    var profile = { name: "演示用户", heightCm: 172, age: 28, sex: "male", targetWeightKg: 62.5 };
    seedProfile = profile;
    var records = [];
    var today = new Date();
    today.setHours(8, 12, 0, 0);
    var count = 0;
    for (var i = 44; i >= 0; i--) {
      if (i % 2 === 1 && i > 2) {
        continue;
      }
      var t = (44 - i) / 44;
      var weight = 67.6 - 1.7 * t + Math.sin(i * 1.7) * 0.32 + (i % 5 === 0 ? 0.18 : 0);
      var at = today.getTime() - i * 86400000;
      var m = buildMeasurement({
        id: ++count,
        measured_at_ms: at,
        weight_kg: round1(weight),
        impedance_low_ohm: round1(548 + Math.sin(i * 0.9) * 12),
        impedance_high_ohm: round1(414 + Math.cos(i * 0.7) * 10),
        source: "demo",
        note: i === 0 ? "早上空腹" : ""
      });
      records.push(m);
    }
    seedProfile = null;
    records.sort(function (a, b) {
      return a.measured_at_ms - b.measured_at_ms;
    });
    return {
      onboarded: true,
      device: {
        mac: "E8:4A:54:12:34:56",
        name: "Xiaomi Scale S400 3456",
        bindKeySet: true,
        tokenSet: false,
        preset: true,
        presetMac: "E8:4A:54:12:34:56",
        presetName: "Xiaomi Scale S400 3456"
      },
      profile: profile,
      theme: "demo",
      nextId: count + 1,
      records: records
    };
  }

  var store = null;
  try {
    store = JSON.parse(localStorage.getItem(STORE_KEY) || "null");
  } catch (e) {
    store = null;
  }
  if (params.reset === "1") {
    store = null;
  }
  if (!store) {
    store = seedStore();
  }
  if (params.onboard === "1") {
    store.onboarded = false;
  }
  if (params.empty === "1") {
    store.records = [];
  }
  persist();

  function persist() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(store));
    } catch (e) {
      /* 隐私模式 / 存储满，忽略 */
    }
  }

  function recordsDesc() {
    return store.records.slice().sort(function (a, b) {
      return b.measured_at_ms - a.measured_at_ms || b.id - a.id;
    });
  }

  function stateObj() {
    var desc = recordsDesc();
    var records = {
      count: desc.length,
      last: desc.length ? desc[0] : null,
      previous: desc.length > 1 ? desc[1] : null
    };
    return {
      version: "1.2-demo",
      onboarded: !!store.onboarded,
      device: {
        mac: store.device.mac,
        name: store.device.name,
        bindKeySet: !!store.device.bindKeySet,
        tokenSet: !!store.device.tokenSet,
        preset: !!store.device.preset,
        presetMac: store.device.presetMac || "",
        presetName: store.device.presetName || ""
      },
      profile: {
        name: store.profile.name,
        heightCm: store.profile.heightCm,
        age: store.profile.age,
        sex: store.profile.sex,
        targetWeightKg: store.profile.targetWeightKg,
        targetSetAtKg: store.profile.targetSetAtKg || 0,
        targetSetAtMs: store.profile.targetSetAtMs || 0,
        ready: store.profile.heightCm > 0 && store.profile.age > 0
      },
      scan: {
        supported: true,
        btOn: true,
        permission: true,
        locationOn: true,
        scanning: scanning,
        mode: scanMode,
        rescue: false,
        wantScan: !!(store.device.mac && store.device.bindKeySet),
        error: null
      },
      records: records
    };
  }

  function emit(type, payload) {
    if (window.__wlEvent__) {
      window.__wlEvent__(type, payload);
    }
    for (var i = 0; i < listeners.length; i++) {
      try {
        listeners[i](type, payload);
      } catch (e) {
        /* ignore */
      }
    }
  }

  function pushState() {
    emit("state", stateObj());
  }

  function later(fn, ms) {
    var id = setTimeout(function () {
      for (var i = 0; i < timers.length; i++) {
        if (timers[i] === id) {
          timers.splice(i, 1);
          break;
        }
      }
      fn();
    }, ms);
    timers.push(id);
    return id;
  }

  function clearTimers() {
    for (var i = 0; i < timers.length; i++) {
      clearTimeout(timers[i]);
    }
    timers = [];
  }

  function stopScanInternal() {
    scanning = false;
    clearTimers();
    var s = stateObj();
    s.scan.scanning = false;
    emit("state", s);
  }

  function startWeighSimulation() {
    var seq = [65.2, 65.3, 65.4, 65.5, 65.5, 65.6];
    var idx = 0;
    function tick() {
      if (!scanning) {
        return;
      }
      var w = seq[idx];
      var imp = idx >= 3 ? 556.3 : null;
      var comp = liveComposition(w, imp, store.profile);
      emit("live", {
        weight_kg: w,
        impedance_low_ohm: imp,
        impedance_high_ohm: null,
        heart_rate: idx >= 4 ? 72 : null,
        stabilized: false,
        ts_ms: nowMs(),
        bmi: comp.bmi,
        body_fat_pct: comp.body_fat_pct,
        bmr_kcal: comp.bmr_kcal,
        profile_ready: comp.profile_ready
      });
      idx++;
      if (idx < seq.length) {
        later(tick, 520);
      } else {
        later(function () {
          if (!scanning) {
            return;
          }
          var m = buildMeasurement({
            id: store.nextId++,
            measured_at_ms: nowMs(),
            weight_kg: 65.6,
            impedance_low_ohm: 556.3,
            impedance_high_ohm: 417.4,
            heart_rate: 72,
            source: "ble",
            note: ""
          });
          store.records.push(m);
          persist();
          scanning = false;
          emit("final", { measurement: m, state: stateObj() });
        }, 900);
      }
    }
    later(tick, 600);
  }

  var bridge = {
    state: function () {
      return JSON.stringify(stateObj());
    },
    list: function (days) {
      var desc = recordsDesc();
      if (days > 0) {
        var since = nowMs() - days * 86400000;
        desc = desc.filter(function (m) {
          return m.measured_at_ms >= since;
        });
      }
      return JSON.stringify(desc);
    },
    addManual: function (json) {
      var input = typeof json === "string" ? JSON.parse(json) : json;
      var weight = Number(input.weight_kg);
      if (!(weight >= 20 && weight <= 250)) {
        return JSON.stringify({ ok: false, error: "体重需要在 20–250 kg 之间" });
      }
      var at = Number(input.measured_at_ms) || nowMs();
      // 与原生一致：非法/未来时间戳回退到当前时间（允许 60s 时钟偏差）
      if (at <= 0 || at > nowMs() + 60000) {
        at = nowMs();
      }
      var m = buildMeasurement({
        id: store.nextId++,
        measured_at_ms: at,
        weight_kg: weight,
        impedance_low_ohm: null,
        source: "manual",
        note: input.note || ""
      });
      store.records.push(m);
      persist();
      return JSON.stringify({ ok: true, measurement: m, state: stateObj() });
    },
    updateNote: function (id, note) {
      for (var i = 0; i < store.records.length; i++) {
        if (store.records[i].id === Number(id)) {
          store.records[i].note = note || null;
          persist();
          return JSON.stringify({ ok: true });
        }
      }
      return JSON.stringify({ ok: false });
    },
    remove: function (id) {
      for (var i = 0; i < store.records.length; i++) {
        if (store.records[i].id === Number(id)) {
          store.records.splice(i, 1);
          persist();
          return JSON.stringify({ ok: true, state: stateObj() });
        }
      }
      return JSON.stringify({ ok: false });
    },
    clearAll: function () {
      store.records = [];
      persist();
      return JSON.stringify({ ok: true, state: stateObj() });
    },
    saveProfile: function (json) {
      var input = typeof json === "string" ? JSON.parse(json) : json;
      var oldTarget = Number(store.profile.targetWeightKg) || 0;
      var newTarget = Number(input.targetWeightKg) || 0;
      var setAtKg = Number(store.profile.targetSetAtKg) || 0;
      var setAtMs = Number(store.profile.targetSetAtMs) || 0;
      // 与原生一致：目标值变了才重置进度起点（起点 = 设目标那一刻的最新体重）
      if (Math.abs(newTarget - oldTarget) > 0.05) {
        var desc = recordsDesc();
        setAtKg = desc.length ? desc[0].weight_kg : 0;
        setAtMs = nowMs();
      }
      store.profile = {
        name: input.name || "",
        heightCm: Number(input.heightCm) || 0,
        age: Number(input.age) || 0,
        sex: input.sex === "female" ? "female" : "male",
        targetWeightKg: newTarget,
        targetSetAtKg: setAtKg,
        targetSetAtMs: setAtMs
      };
      // 按新档案重算历史体成分（与原生行为一致）
      for (var i = 0; i < store.records.length; i++) {
        var old = store.records[i];
        store.records[i] = buildMeasurement({
          id: old.id,
          measured_at_ms: old.measured_at_ms,
          weight_kg: old.weight_kg,
          impedance_low_ohm: old.impedance_low_ohm,
          impedance_high_ohm: old.impedance_high_ohm,
          heart_rate: old.heart_rate,
          source: old.source,
          note: old.note
        });
      }
      persist();
      return JSON.stringify({ ok: true, profile: store.profile, state: stateObj() });
    },
    saveDevice: function (json) {
      var input = typeof json === "string" ? JSON.parse(json) : json;
      var mac = String(input.mac || "").toUpperCase().replace(/[^0-9A-F]/g, "");
      if (mac.length !== 12) {
        return JSON.stringify({ ok: false, error: "MAC 格式不对（例如 E8:4A:54:12:34:56）" });
      }
      var key = String(input.bindKey || "").replace(/[^0-9a-fA-F]/g, "");
      if (key.length > 0 && key.length !== 32) {
        return JSON.stringify({ ok: false, error: "bind key 应该是 32 位十六进制（16 字节）" });
      }
      var formatted = mac.replace(/(..)(?=.)/g, "$1:");
      store.device.mac = formatted;
      store.device.name = input.name || ("Xiaomi Scale S400 " + mac.slice(-4));
      if (key.length === 32) {
        store.device.bindKeySet = true;
      }
      store.device.tokenSet = store.device.tokenSet || false;
      persist();
      return JSON.stringify({
        ok: true,
        device: { mac: store.device.mac, name: store.device.name, bindKeySet: !!store.device.bindKeySet, tokenSet: !!store.device.tokenSet,
          preset: !!store.device.preset, presetMac: store.device.presetMac || "", presetName: store.device.presetName || "" },
        keyValid: !!store.device.bindKeySet,
        state: stateObj()
      });
    },
    applyPresetDevice: function () {
      if (!store.device.preset || !store.device.presetMac) {
        return JSON.stringify({ ok: false, error: "这个安装包没有内置预绑定设备" });
      }
      store.device.mac = store.device.presetMac;
      store.device.name = store.device.presetName || ("Xiaomi Scale S400 " + store.device.presetMac.slice(-5).replace(":", ""));
      store.device.bindKeySet = true;
      persist();
      pushState();
      return JSON.stringify({
        ok: true,
        device: { mac: store.device.mac, name: store.device.name, bindKeySet: true, tokenSet: !!store.device.tokenSet,
          preset: true, presetMac: store.device.presetMac, presetName: store.device.presetName || "" },
        keyValid: true,
        state: stateObj()
      });
    },
    clearDevice: function () {
      store.device = { mac: "", name: "Xiaomi Scale S400", bindKeySet: false, tokenSet: false,
        preset: !!store.device.preset, presetMac: store.device.presetMac || "", presetName: store.device.presetName || "" };
      persist();
      stopScanInternal();
      return JSON.stringify({ ok: true, state: stateObj() });
    },
    setOnboarded: function (value) {
      store.onboarded = !!value;
      persist();
    },
    startScan: function (mode) {
      scanMode = mode === "discover" ? "discover" : "bind";
      scanning = true;
      clearTimers();
      pushState();
      if (scanMode === "discover") {
        later(function () {
          if (!scanning) {
            return;
          }
          emit("candidates", [
            { address: "E8:4A:54:12:34:56", name: "Xiaomi Scale S400 3456", product_id: 15317, rssi: -54, last_seen_ms: nowMs() },
            { address: "E8:4A:54:AB:CD:EF", name: "Xiaomi Scale S400 CDEF", product_id: 15317, rssi: -78, last_seen_ms: nowMs() }
          ]);
        }, 900);
      } else if (store.device.mac && store.device.bindKeySet) {
        startWeighSimulation();
      }
    },
    stopScan: function () {
      stopScanInternal();
    },
    candidates: function () {
      return JSON.stringify([
        { address: "E8:4A:54:12:34:56", name: "Xiaomi Scale S400 3456", product_id: 15317, rssi: -54, last_seen_ms: nowMs() }
      ]);
    },
    exportData: function (format) {
      var records = recordsDesc().slice().reverse();
      var content;
      if (format === "json") {
        content = JSON.stringify({
          app: "weightlog",
          exported_at: isoTime(nowMs()),
          count: records.length,
          profile: store.profile,
          measurements: records
        }, null, 2);
      } else {
        // 与原生一致：UTF-8 BOM + 公式注入防护
        content = "\uFEFFmeasured_at,weight_kg,impedance_low_ohm,impedance_high_ohm,heart_rate,body_fat_pct_est,bmi,bmr_kcal_est,source,note\n";
        for (var i = 0; i < records.length; i++) {
          var r = records[i];
          content += csvCell(r.measured_at) + "," + csvCell(r.weight_kg) + "," +
            csvCell(r.impedance_low_ohm) + "," + csvCell(r.impedance_high_ohm) + "," +
            csvCell(r.heart_rate) + "," + csvCell(r.body_fat_pct) + "," +
            csvCell(r.bmi) + "," + csvCell(r.bmr_kcal) + "," +
            csvCell(r.source) + "," + csvCell(r.note) + "\n";
        }
      }
      try {
        var blob = new Blob([content], { type: format === "json" ? "application/json" : "text/csv" });
        var a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = "weightlog-demo." + (format === "json" ? "json" : "csv");
        document.body.appendChild(a);
        a.click();
        setTimeout(function () {
          document.body.removeChild(a);
          URL.revokeObjectURL(a.href);
        }, 1000);
      } catch (e) {
        /* 某些浏览器禁止下载，忽略 */
      }
      // 与原生同步导出路径一致：只返回结果，不额外推 export 事件（避免双 toast）
      return JSON.stringify({
        ok: true,
        count: records.length,
        format: format === "json" ? "json" : "csv",
        path: "浏览器下载",
        uri: ""
      });
    },
    openSettings: function () {
      /* 浏览器里没有系统设置页可跳转 */
    },
    setLiveMode: function () {
      /* 浏览器演示桥不需要切换扫描模式 */
    },
    seedDemo: function () {
      // 与原生一致：只写演示记录，不补假档案（档案为空时体脂留空）
      var fresh = seedStore();
      store.records = fresh.records;
      store.device = fresh.device;
      store.nextId = fresh.nextId;
      persist();
      return JSON.stringify({ ok: true, count: store.records.length, state: stateObj() });
    },
    clearDemo: function () {
      var before = store.records.length;
      store.records = store.records.filter(function (m) {
        return m.source !== "demo";
      });
      var removed = before - store.records.length;
      persist();
      return JSON.stringify({ ok: true, count: removed, state: stateObj() });
    }
  };

  window.WeightLog = bridge;
  window.__WL_DEMO__ = true;
  if (window.console) {
    console.log("WeightLog demo bridge active", params);
  }
})();
