/* =============================================================================
 * 体重记 · 页面逻辑
 *
 * 原生接口：window.WeightLog（见 bridge-demo.js 的浏览器实现）
 * 原生事件：window.__wlEvent__(type, payload)
 *   type: state / live / final / candidates / error / export
 * ========================================================================== */
(function () {
  "use strict";

  var App = {
    state: null,
    records: [],
    view: "home",
    trendDays: 30,
    recordDays: 0,
    metric: "weight",
    live: null,
    result: null,
    weighOpen: false,
    weighDismissed: false,
    maskTimer: null,
    lastFocus: null,
    onboardFocus: null,
    detailId: null,
    candidates: [],
    discovering: false,
    discoverTimer: null,
    demo: !!window.__WL_DEMO__,
    sheet: null,
    profileSheetSex: "male",
    clearArmed: false,
    clearDemoArmed: false,
    deleteArmed: false
  };

  var $ = function (id) {
    return document.getElementById(id);
  };

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function pad2(n) {
    return n < 10 ? "0" + n : "" + n;
  }

  function fmt1(v) {
    if (v == null || isNaN(v)) {
      return "--";
    }
    return Number(v).toFixed(1);
  }

  function fmtDelta(v) {
    var a = Math.abs(v);
    return (v > 0 ? "+" : v < 0 ? "-" : "") + a.toFixed(1);
  }

  function callJson(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!window.WeightLog || typeof window.WeightLog[name] !== "function") {
      return null;
    }
    var result;
    try {
      result = window.WeightLog[name].apply(window.WeightLog, args);
    } catch (e) {
      if (window.console) {
        console.error("bridge " + name + " failed", e);
      }
      return null;
    }
    if (typeof result === "string") {
      try {
        return JSON.parse(result);
      } catch (e) {
        return null;
      }
    }
    return result === undefined ? null : result;
  }

  function callVoid(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!window.WeightLog || typeof window.WeightLog[name] !== "function") {
      return;
    }
    try {
      window.WeightLog[name].apply(window.WeightLog, args);
    } catch (e) {
      if (window.console) {
        console.error("bridge " + name + " failed", e);
      }
    }
  }

  function scaleIcon(size) {
    return '<svg viewBox="0 0 24 24" width="' + size + '" height="' + size + '" aria-hidden="true">' +
      '<rect x="3.2" y="4.6" width="17.6" height="15" rx="4.4" fill="none" stroke="#ff6900" stroke-width="1.7"/>' +
      '<rect x="6.6" y="8.2" width="10.8" height="4.2" rx="1.8" fill="#ff6900" opacity="0.18"/>' +
      '<path d="M8.4 14.9h7.2" stroke="#ff6900" stroke-width="1.7" stroke-linecap="round"/>' +
      '</svg>';
  }

  // ---------------------------------------------------------------- 时间 / 格式

  // offMin（记录自己的 tz_offset_min）存在时，按该 UTC 偏移渲染墙上时间；
  // 缺失时保持设备本地时间，兼容旧数据。
  function hasOff(offMin) {
    return typeof offMin === "number" && isFinite(offMin);
  }

  function wallClock(ms, offMin) {
    if (hasOff(offMin)) {
      var d = new Date(ms + offMin * 60000);
      return {
        y: d.getUTCFullYear(),
        mo: d.getUTCMonth(),
        d: d.getUTCDate(),
        h: d.getUTCHours(),
        mi: d.getUTCMinutes(),
        start: Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - offMin * 60000
      };
    }
    var raw = new Date(ms);
    var l = new Date(ms);
    l.setHours(0, 0, 0, 0);
    return {
      y: raw.getFullYear(),
      mo: raw.getMonth(),
      d: raw.getDate(),
      h: raw.getHours(),
      mi: raw.getMinutes(),
      start: l.getTime()
    };
  }

  function dayStart(ms, offMin) {
    return wallClock(ms, offMin).start;
  }

  function dayLabel(ms, offMin) {
    var today = dayStart(Date.now(), offMin);
    var day = dayStart(ms, offMin);
    var diff = Math.round((today - day) / 86400000);
    if (diff === 0) {
      return "今天";
    }
    if (diff === 1) {
      return "昨天";
    }
    var w = wallClock(ms, offMin);
    if (w.y === wallClock(Date.now(), offMin).y) {
      return (w.mo + 1) + "月" + w.d + "日";
    }
    return w.y + "年" + (w.mo + 1) + "月" + w.d + "日";
  }

  function timeLabel(ms, offMin) {
    var w = wallClock(ms, offMin);
    return pad2(w.h) + ":" + pad2(w.mi);
  }

  function shortDate(ms, offMin) {
    var w = wallClock(ms, offMin);
    return (w.mo + 1) + "/" + w.d;
  }

  function fullDate(ms, offMin) {
    var w = wallClock(ms, offMin);
    return (w.mo + 1) + "月" + w.d + "日";
  }

  function monthLabel(ms, offMin) {
    var w = wallClock(ms, offMin);
    return w.y + "年" + (w.mo + 1) + "月";
  }

  function sourceLabel(source) {
    if (source === "manual") {
      return "手动";
    }
    if (source === "demo") {
      return "演示";
    }
    return "体重秤";
  }

  function greeting() {
    var h = new Date().getHours();
    if (h < 6) {
      return "夜深了，注意休息";
    }
    if (h < 11) {
      return "早上好，今天也在好好记录";
    }
    if (h < 14) {
      return "中午好，记得按时吃饭";
    }
    if (h < 18) {
      return "下午好，保持节奏";
    }
    return "晚上好，别忘了称一称";
  }

  // ---------------------------------------------------------------- 数据

  function latest() {
    return App.records.length ? App.records[0] : null;
  }

  function previous() {
    return App.records.length > 1 ? App.records[1] : null;
  }

  function hasDevice() {
    return !!(App.state && App.state.device && App.state.device.mac);
  }

  function hasKey() {
    return !!(App.state && App.state.device && App.state.device.bindKeySet);
  }

  function target() {
    return (App.state && App.state.profile && Number(App.state.profile.targetWeightKg)) || 0;
  }

  // 目标进度起点：设目标那一刻的体重；旧数据没有这个字段时回退到最早一条记录。
  function targetStartWeight() {
    var p = (App.state && App.state.profile) || {};
    var setAt = Number(p.targetSetAtKg);
    if (setAt > 0) {
      return setAt;
    }
    var asc = ascRecords(0);
    return asc.length ? asc[0].weight_kg : 0;
  }

  function dbError() {
    return !!(App.state && App.state.dbError);
  }

  function demoCount() {
    var n = 0;
    for (var i = 0; i < App.records.length; i++) {
      if (App.records[i].source === "demo") {
        n++;
      }
    }
    return n;
  }

  function profileReady() {
    return !!(App.state && App.state.profile && App.state.profile.ready);
  }

  function scanError() {
    return App.state && App.state.scan && App.state.scan.error ? App.state.scan.error : null;
  }

  function ascRecords(days) {
    var list = App.records.slice();
    if (days > 0) {
      var since = Date.now() - days * 86400000;
      list = list.filter(function (m) {
        return m.measured_at_ms >= since;
      });
    }
    list.sort(function (a, b) {
      return a.measured_at_ms - b.measured_at_ms || a.id - b.id;
    });
    return list;
  }

  function prevWeights() {
    var asc = ascRecords(0);
    var map = {};
    for (var i = 0; i < asc.length; i++) {
      map[asc[i].id] = i > 0 ? asc[i - 1].weight_kg : null;
    }
    return map;
  }

  function reload() {
    App.state = callJson("state") || App.state;
    App.records = callJson("list", 0) || [];
  }

  function showToast(message) {
    var el = $("toast");
    if (!el) {
      return;
    }
    el.textContent = message;
    el.hidden = false;
    el.classList.add("show");
    clearTimeout(el._timer);
    el._timer = setTimeout(function () {
      el.classList.remove("show");
      setTimeout(function () {
        el.hidden = true;
      }, 240);
    }, 2200);
  }

  // ---------------------------------------------------------------- 渲染：公共

  function renderAll() {
    renderDbError();
    renderHeader();
    renderHome();
    renderRecords();
    renderMe();
    if (App.view === "trend") {
      renderTrend();
    }
    if (App.sheet === "sheetDevice") {
      renderDeviceBanner();
      renderPresetBox();
    }
  }

  // DB 读写失败（state().dbError）必须在页面上说清楚：否则空态会被误读成「还没有记录」。
  function renderDbError() {
    var el = $("dbErrorBar");
    if (!el) {
      return;
    }
    var bad = dbError();
    el.hidden = !bad;
    if (bad) {
      el.textContent = "记录读写失败，可能有数据没保存。先导出备份，再重开 App。";
    }
  }

  function renderHeader() {
    var sub = $("brandSub");
    if (sub) {
      sub.textContent = greeting();
    }
  }

  function renderScanChip() {
    var chip = $("scanChip");
    if (!chip) {
      return;
    }
    var cls = "chip";
    var text = "等待上秤";
    var scan = App.state && App.state.scan;
    if (App.live) {
      cls += " live";
      text = "称重中";
    } else if (!hasDevice()) {
      cls += " off";
      text = "未绑定秤";
    } else if (!hasKey()) {
      cls += " off";
      text = "缺少密钥";
    } else if (scan && !scan.supported) {
      cls += " off";
      text = "不支持蓝牙";
    } else if (scan && !scan.btOn) {
      cls += " off";
      text = "蓝牙已关";
    } else if (scan && !scan.permission) {
      cls += " off";
      text = "等待授权";
    } else if (scan && scan.rescue) {
      cls += " live";
      text = "重新找秤";
    } else if (scan && scan.scanning) {
      cls += " ok";
      text = "正在扫描";
    } else if (scanError()) {
      cls += " off";
      text = "需要处理";
    }
    chip.className = cls;
    chip.textContent = text;
  }

  // ---------------------------------------------------------------- 渲染：首页

  function renderHome() {
    renderScanChip();
    var last = latest();
    var prev = previous();
    $("lastWeight").textContent = last ? fmt1(last.weight_kg) : "--";
    $("lastMeta").textContent = last
      ? dayLabel(last.measured_at_ms, last.tz_offset_min) + " " +
        timeLabel(last.measured_at_ms, last.tz_offset_min) + " · " +
        (last.source === "manual" ? "手动记录" : last.source === "demo" ? "演示记录" : "S400 自动记录")
      : dbError() ? "读取失败" : "还没有记录，绑定秤后站上去即可";

    var deltaEl = $("lastDelta");
    if (last && prev) {
      var d = last.weight_kg - prev.weight_kg;
      deltaEl.className = "delta " + (d > 0.05 ? "up" : d < -0.05 ? "" : "flat");
      deltaEl.innerHTML = (d > 0.05 ? "↑ " : d < -0.05 ? "↓ " : "") +
        fmtDelta(d) + " kg <small>较上次</small>";
    } else {
      deltaEl.className = "delta flat";
      deltaEl.innerHTML = last ? "第一条记录" : "还没有数据";
    }

    renderSpark();
    renderMetrics(last);
    renderGoal(last);
    renderDeviceStrip();
    renderRecent();
  }

  function renderSpark() {
    var canvas = $("spark");
    var empty = $("sparkEmpty");
    if (!canvas) {
      return;
    }
    var asc = ascRecords(30);
    var pts = asc.slice(-14);
    if (pts.length < 2) {
      clearCanvas(canvas);
      canvas.style.opacity = "0";
      empty.hidden = false;
      return;
    }
    canvas.style.opacity = "1";
    empty.hidden = true;
    drawSpark(canvas, pts);
  }

  function renderMetrics(last) {
    var grid = $("metricGrid");
    if (!grid) {
      return;
    }
    var fat = last && last.body_fat_pct != null ? fmt1(last.body_fat_pct) : "--";
    var bmiVal = last && last.bmi != null ? fmt1(last.bmi) : "--";
    var bmrVal = last && last.bmr_kcal != null ? last.bmr_kcal : "--";
    var fatNote = "本地估算";
    if (last && last.body_fat_pct != null) {
      var f = last.body_fat_pct;
      fatNote = f < 15 ? "偏低" : f < 20 ? "健康" : f < 25 ? "正常偏高" : "偏高";
    }
    var bmiNote = "需要身高";
    if (last && last.bmi != null) {
      var b = last.bmi;
      bmiNote = b < 18.5 ? "偏瘦" : b < 24 ? "正常" : b < 28 ? "偏胖" : "肥胖";
    }
    var bmrNote = profileReady() ? "静息估算" : "需要档案";
    grid.innerHTML =
      metricCard("体脂率", fat, "%", fatNote) +
      metricCard("BMI", bmiVal, "", bmiNote) +
      metricCard("基础代谢", bmrVal, bmrVal === "--" ? "" : "kcal", bmrNote);
  }

  function metricCard(label, value, unit, note) {
    return '<div class="metric"><div class="metric-label">' + label + '</div>' +
      '<div class="metric-value">' + value +
      (unit ? '<small>' + unit + '</small>' : "") + "</div>" +
      '<div class="metric-note">' + note + "</div></div>";
  }

  function renderGoal(last) {
    var card = $("goalCard");
    if (!card) {
      return;
    }
    var t = target();
    if (!t) {
      card.innerHTML = '<div class="goal-top"><span class="goal-title">目标体重</span>' +
        '<span class="goal-num">未设置</span></div>' +
        '<div class="goal-hint">设置一个目标，趋势页会显示达成进度。</div>';
      card.setAttribute("data-action", "open-profile");
      return;
    }
    card.removeAttribute("data-action");
    if (!last) {
      card.innerHTML = '<div class="goal-top"><span class="goal-title">目标体重</span>' +
        '<span class="goal-num"><b>' + fmt1(t) + '</b> kg</span></div>' +
        '<div class="goal-bar"><span style="width:0%"></span></div>' +
        '<div class="goal-hint">记录第一条体重后开始计算进度。</div>';
      return;
    }
    var start = targetStartWeight();
    var current = last.weight_kg;
    // 方向由「起点 → 目标」决定（起点高于目标 = 减重，低于目标 = 增重），不能用当前值判定：
    // 否则减重目标一旦掉到目标以下，方向会翻转成增重，达成状态永远出不来。
    var dir = start > t + 0.05 ? "down" : start < t - 0.05 ? "up" : "flat";
    var achieved = dir === "up" ? current >= t
      : dir === "down" ? current <= t
      : Math.abs(current - t) <= 0.05;
    var total = Math.abs(start - t);
    var done = Math.abs(start - current);
    var progress = 0;
    if (achieved) {
      progress = 100;
    } else if (total > 0.05) {
      var movedRight = dir === "up" ? current >= start : current <= start;
      progress = movedRight ? Math.max(0, Math.min(100, (done / total) * 100)) : 0;
    }
    var hint = achieved
      ? "已达成目标 <b>" + fmt1(t) + " kg</b>"
      : "距离目标还差 <b>" + fmt1(Math.abs(current - t)) + " kg</b>";
    card.innerHTML = '<div class="goal-top"><span class="goal-title">目标体重</span>' +
      '<span class="goal-num"><b>' + fmt1(t) + "</b> kg</span></div>" +
      '<div class="goal-bar"><span style="width:' + progress.toFixed(1) + '%"></span></div>' +
      '<div class="goal-hint">' + hint + "</div>";
  }

  function renderDeviceStrip() {
    var el = $("deviceStrip");
    if (!el) {
      return;
    }
    var scan = App.state && App.state.scan;
    var stateText = "未绑定";
    var dot = "off";
    if (hasDevice() && hasKey()) {
      if (scan && !scan.btOn) {
        stateText = "蓝牙未开";
        dot = "warn";
      } else if (scan && !scan.permission) {
        stateText = "待授权";
        dot = "warn";
      } else if (scan && scan.rescue) {
        stateText = "重新找秤";
        dot = "warn";
      } else if (scan && scan.scanning) {
        stateText = "扫描中";
        dot = "on";
      } else {
        stateText = "已就绪";
        dot = "on";
      }
    } else if (hasDevice()) {
      stateText = "缺少 bind key";
      dot = "warn";
    }
    el.innerHTML =
      '<div class="device-ico">' + scaleIcon(22) + "</div>" +
      '<div class="device-main"><div class="device-name">' +
      esc(hasDevice() ? (App.state.device.name || "Xiaomi Scale S400") : "小米体脂秤 S400") +
      '</div><div class="device-sub">' +
      (hasDevice() ? esc(App.state.device.mac) +
        (App.state.device.preset ? " · 已预绑定" : "") : "还没有绑定，点此添加") +
      "</div></div>" +
      '<div class="device-state"><span class="dot ' + dot + '"></span>' + stateText + "</div>" +
      '<span class="chev">›</span>';
    el.setAttribute("data-action", "open-device");
  }

  function renderRecent() {
    var el = $("recentList");
    if (!el) {
      return;
    }
    var list = App.records.slice(0, 3);
    if (!list.length) {
      el.innerHTML = '<div class="empty-row">还没有记录，站上秤试试</div>';
      return;
    }
    var prev = prevWeights();
    var html = "";
    for (var i = 0; i < list.length; i++) {
      html += recordRow(list[i], prev[list[i].id], true);
    }
    el.innerHTML = html;
  }

  function recordRow(m, prevWeight, compact) {
    var delta = prevWeight != null ? m.weight_kg - prevWeight : null;
    var deltaHtml = "";
    if (delta != null) {
      var cls = delta > 0.05 ? "up" : delta < -0.05 ? "down" : "";
      deltaHtml = '<div class="row-delta ' + cls + '">' + fmtDelta(delta) + " kg</div>";
    } else {
      deltaHtml = '<div class="row-delta">首条</div>';
    }
    var tags = "";
    if (m.body_fat_pct != null) {
      tags += '<span class="tag">体脂 ' + fmt1(m.body_fat_pct) + "%</span>";
    }
    var sourceText = m.source === "manual" ? "手动记录"
      : m.source === "demo" ? "演示数据" : "S400 自动记录";
    return '<button class="row" data-action="open-record" data-id="' + m.id + '" type="button">' +
      '<div class="row-main"><div class="row-date">' +
      dayLabel(m.measured_at_ms, m.tz_offset_min) + " " +
      timeLabel(m.measured_at_ms, m.tz_offset_min) + "</div>" +
      '<div class="row-sub">' + sourceText + tags + "</div></div>" +
      '<div style="text-align:right">' +
      '<div class="row-weight">' + fmt1(m.weight_kg) + "<small>kg</small></div>" +
      deltaHtml + "</div></button>";
  }

  // ---------------------------------------------------------------- 渲染：记录页

  function renderRecords() {
    var listEl = $("recordList");
    var emptyEl = $("recordsEmpty");
    var countEl = $("recordCount");
    var subEl = $("recordSub");
    if (!listEl) {
      return;
    }
    var list = App.records.slice();
    if (App.recordDays > 0) {
      var since = Date.now() - App.recordDays * 86400000;
      list = list.filter(function (m) {
        return m.measured_at_ms >= since;
      });
    }
    countEl.textContent = dbError() ? "读取失败" : list.length + " 条";
    subEl.textContent = App.recordDays > 0
      ? "近 " + App.recordDays + " 天 · " + list.length + " 条"
      : "全部称重数据";
    if (!list.length) {
      listEl.innerHTML = "";
      emptyEl.hidden = false;
      var titleEl = $("recordsEmptyTitle");
      var textEl = $("recordsEmptyText");
      var addBtn = $("recordsEmptyAdd");
      if (dbError()) {
        // 坏库时不要引导用户继续写，也不要把「读不到」说成「没有记录」
        if (titleEl) {
          titleEl.textContent = "读取失败";
        }
        if (textEl) {
          textEl.textContent = "数据没有丢，重开 App 试试";
        }
        if (addBtn) {
          addBtn.hidden = true;
        }
      } else {
        if (titleEl) {
          titleEl.textContent = "还没有记录";
        }
        if (textEl) {
          textEl.textContent = "站上体重秤，或在右上角手动补一条。";
        }
        if (addBtn) {
          addBtn.hidden = false;
        }
      }
      var demoTag = $("demoTag");
      if (demoTag) {
        demoTag.hidden = true;
      }
      return;
    }
    emptyEl.hidden = true;
    var demoTagEl = $("demoTag");
    if (demoTagEl) {
      var hasDemo = false;
      for (var d = 0; d < list.length; d++) {
        if (list[d].source === "demo") {
          hasDemo = true;
          break;
        }
      }
      demoTagEl.hidden = !hasDemo;
    }
    var prev = prevWeights();
    var html = "";
    var month = "";
    var groupOpen = false;
    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      var mk = monthLabel(m.measured_at_ms, m.tz_offset_min);
      if (mk !== month) {
        if (groupOpen) {
          html += "</div>";
          groupOpen = false;
        }
        month = mk;
        var monthCount = list.filter(function (x) {
          return monthLabel(x.measured_at_ms, x.tz_offset_min) === mk;
        }).length;
        html += '<div class="month-head"><b>' + mk + "</b><span>" + monthCount + " 条</span></div>";
        html += '<div class="record-group">';
        groupOpen = true;
      }
      html += recordRow(m, prev[m.id], false);
    }
    if (groupOpen) {
      html += "</div>";
    }
    listEl.innerHTML = html;
  }

  // ---------------------------------------------------------------- 渲染：趋势

  function renderTrend() {
    var asc = ascRecords(App.trendDays);
    var stats = $("trendStats");
    var chartTitle = $("chartTitle");
    var chartSub = $("chartSub");
    var isFat = App.metric === "fat";
    var values = [];
    for (var i = 0; i < asc.length; i++) {
      var v = isFat ? asc[i].body_fat_pct : asc[i].weight_kg;
      if (v != null) {
        values.push({ x: asc[i].measured_at_ms, y: Number(v), m: asc[i] });
      }
    }

    var latestVal = values.length ? values[values.length - 1].y : null;
    var change = values.length > 1 ? latestVal - values[0].y : null;
    var sum = 0;
    for (var j = 0; j < values.length; j++) {
      sum += values[j].y;
    }
    var avg = values.length ? sum / values.length : null;
    var unit = isFat ? "%" : "kg";

    var changeCls = change == null ? "" : change > 0.01 ? "up" : change < -0.01 ? "down" : "";
    stats.innerHTML =
      '<div class="stat"><div class="stat-label">最新</div><div class="stat-value">' +
      (latestVal == null ? "--" : fmt1(latestVal)) + "<small>" + unit + "</small></div></div>" +
      '<div class="stat"><div class="stat-label">区间变化</div><div class="stat-value ' + changeCls + '">' +
      (change == null ? "--" : (change > 0 ? "+" : "") + fmt1(change)) +
      "<small>" + unit + "</small></div></div>" +
      '<div class="stat"><div class="stat-label">平均</div><div class="stat-value">' +
      (avg == null ? "--" : fmt1(avg)) + "<small>" + unit + "</small></div></div>";

    chartTitle.textContent = isFat ? "体脂率变化" : "体重变化";
    chartSub.textContent = App.trendDays > 0
      ? "最近 " + App.trendDays + " 天 · " + values.length + " 次记录"
      : "全部数据 · " + values.length + " 次记录";

    var canvas = $("chart");
    var empty = $("chartEmpty");
    var tip = $("chartTip");
    if (tip) {
      tip.hidden = true;
    }
    if (!values.length) {
      clearCanvas(canvas);
      if (empty) {
        empty.hidden = false;
      }
    } else {
      if (empty) {
        empty.hidden = true;
      }
      drawTrendChart(canvas, values, {
        unit: unit,
        goal: !isFat && target() ? target() : null,
        decimals: 1
      });
    }
    renderInsight(asc, values, isFat);
  }

  function renderInsight(asc, values, isFat) {
    var el = $("insightCard");
    if (!el) {
      return;
    }
    if (values.length < 2) {
      el.innerHTML = '<div class="insight-title">数据洞察</div>' +
        '<div class="insight-empty">再记录几次，就能看到变化速度和最低/最高点。</div>';
      return;
    }
    var first = values[0];
    var last = values[values.length - 1];
    var min = values[0];
    var max = values[0];
    for (var i = 1; i < values.length; i++) {
      if (values[i].y < min.y) {
        min = values[i];
      }
      if (values[i].y > max.y) {
        max = values[i];
      }
    }
    var days = Math.max(1, (last.x - first.x) / 86400000);
    var perWeek = (last.y - first.y) / days * 7;
    var unit = isFat ? "%" : "kg";
    var trend = Math.abs(last.y - first.y) < 0.05
      ? "基本持平"
      : (last.y < first.y ? "下降" : "上升");
    var text = "这段时间" + (isFat ? "体脂率" : "体重") + trend +
      " <b>" + fmt1(Math.abs(last.y - first.y)) + " " + unit + "</b>，" +
      "平均每周 <b>" + (perWeek > 0 ? "+" : "") + fmt1(perWeek) + " " + unit + "</b>。";
    text += "最低 <b>" + fmt1(min.y) + " " + unit + "</b>（" +
      fullDate(min.x, min.m ? min.m.tz_offset_min : null) + "），" +
      "最高 <b>" + fmt1(max.y) + " " + unit + "</b>（" +
      fullDate(max.x, max.m ? max.m.tz_offset_min : null) + "）。";
    if (!isFat && target()) {
      var t = target();
      var start = targetStartWeight();
      var tdir = start > t + 0.05 ? "down" : start < t - 0.05 ? "up" : "flat";
      var tAchieved = tdir === "up" ? last.y >= t
        : tdir === "down" ? last.y <= t
        : Math.abs(last.y - t) <= 0.05;
      if (tAchieved) {
        text += " 目标 <b>" + fmt1(t) + " kg</b> 已达成。";
      } else {
        text += " 距离目标 <b>" + fmt1(t) + " kg</b> 还差 <b>" +
          fmt1(Math.abs(last.y - t)) + " kg</b>。";
      }
    }
    el.innerHTML = '<div class="insight-title">数据洞察</div>' +
      '<div class="insight-body">' + text + "</div>";
  }

  // ---------------------------------------------------------------- 渲染：我的

  function renderMe() {
    var profileCard = $("profileCard");
    if (profileCard) {
      var p = App.state && App.state.profile ? App.state.profile : {};
      var name = p.name && p.name.length ? p.name : "未设置昵称";
      var sub = [];
      if (p.heightCm > 0) {
        sub.push(p.heightCm + " cm");
      }
      if (p.age > 0) {
        sub.push(p.age + " 岁");
      }
      if (p.sex) {
        sub.push(p.sex === "female" ? "女" : "男");
      }
      if (!sub.length) {
        sub.push("完善档案后可估算体脂");
      }
      profileCard.innerHTML =
        '<div class="avatar">' + esc(name.slice(0, 1).toUpperCase()) + "</div>" +
        '<div class="profile-main"><div class="profile-name">' + esc(name) + "</div>" +
        '<div class="profile-sub">' + esc(sub.join(" · ")) + "</div></div>" +
        '<button class="profile-edit" data-action="open-profile" type="button">编辑</button>';
    }

    var deviceCard = $("meDeviceCard");
    if (deviceCard) {
      var scan = App.state && App.state.scan;
      var bound = hasDevice() && hasKey();
      var stateText = !hasDevice() ? "未绑定" : !hasKey() ? "缺少 bind key" : "已绑定";
      deviceCard.innerHTML =
        '<button class="action-row" data-action="open-device" type="button">' +
        '<span class="action-ico">S400</span>' +
        '<span class="action-text"><b>' +
        esc(hasDevice() ? (App.state.device.name || "Xiaomi Scale S400") : "添加体重秤") +
        "</b><small>" +
        (hasDevice() ? esc(App.state.device.mac) : "小米体脂秤 S400 / MJTZC01YM") +
        "</small></span>" +
        '<span class="device-state"><span class="dot ' + (bound ? "on" : "off") + '"></span>' +
        stateText + "</span>" +
        '<span class="chev">›</span></button>';
    }

    var versionEl = $("aboutVersion");
    if (versionEl) {
      versionEl.textContent = (App.state && App.state.version) || "1.0";
    }
    var demoHint = $("demoHint");
    if (demoHint) {
      demoHint.hidden = !App.demo;
    }
    // 有演示记录时才出现「清除演示记录」入口（真实记录不受影响）
    var clearDemoRow = $("clearDemoRow");
    if (clearDemoRow) {
      var demoN = demoCount();
      clearDemoRow.hidden = demoN <= 0;
      var clearDemoLabel = $("clearDemoLabel");
      if (clearDemoLabel) {
        clearDemoLabel.textContent = "清除演示记录（" + demoN + " 条）";
      }
    }
  }

  // ---------------------------------------------------------------- 图表

  function clearCanvas(canvas) {
    if (!canvas) {
      return;
    }
    var ctx = canvas.getContext("2d");
    if (ctx) {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }
  }

  function sizeCanvas(canvas, height) {
    var rect = canvas.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var w = Math.max(1, Math.round(rect.width));
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(height * dpr);
    var ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx: ctx, width: w, height: height };
  }

  function drawSpark(canvas, points) {
    var size = sizeCanvas(canvas, canvas.clientHeight || 62);
    var ctx = size.ctx;
    var w = size.width;
    var h = size.height;
    var padX = 6;
    var padY = 10;
    var min = Infinity;
    var max = -Infinity;
    for (var i = 0; i < points.length; i++) {
      min = Math.min(min, points[i].weight_kg);
      max = Math.max(max, points[i].weight_kg);
    }
    if (max - min < 0.4) {
      min -= 0.2;
      max += 0.2;
    }
    var x0 = padX;
    var x1 = w - padX;
    var y0 = padY;
    var y1 = h - padY;
    function px(i) {
      return points.length === 1 ? (x0 + x1) / 2 : x0 + (x1 - x0) * (i / (points.length - 1));
    }
    function py(v) {
      return y1 - ((v - min) / (max - min)) * (y1 - y0);
    }
    ctx.clearRect(0, 0, w, h);
    var grad = ctx.createLinearGradient(0, y0, 0, y1);
    grad.addColorStop(0, "rgba(255,105,0,0.22)");
    grad.addColorStop(1, "rgba(255,105,0,0)");
    ctx.beginPath();
    ctx.moveTo(px(0), y1);
    for (var j = 0; j < points.length; j++) {
      ctx.lineTo(px(j), py(points[j].weight_kg));
    }
    ctx.lineTo(px(points.length - 1), y1);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    ctx.beginPath();
    for (var k = 0; k < points.length; k++) {
      var x = px(k);
      var y = py(points[k].weight_kg);
      if (k === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.strokeStyle = "#ff6900";
    ctx.lineWidth = 2;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.stroke();
    var lx = px(points.length - 1);
    var ly = py(points[points.length - 1].weight_kg);
    ctx.beginPath();
    ctx.arc(lx, ly, 3.2, 0, Math.PI * 2);
    ctx.fillStyle = "#ff6900";
    ctx.fill();
    ctx.beginPath();
    ctx.arc(lx, ly, 6, 0, Math.PI * 2);
    ctx.fillStyle = "rgba(255,105,0,0.18)";
    ctx.fill();
  }

  function drawTrendChart(canvas, points, opts) {
    if (!canvas) {
      return;
    }
    var size = sizeCanvas(canvas, 208);
    var ctx = size.ctx;
    var w = size.width;
    var h = size.height;
    var padL = 34;
    var padR = 12;
    var padT = 14;
    var padB = 24;
    var plotW = w - padL - padR;
    var plotH = h - padT - padB;

    var min = Infinity;
    var max = -Infinity;
    for (var i = 0; i < points.length; i++) {
      min = Math.min(min, points[i].y);
      max = Math.max(max, points[i].y);
    }
    if (max - min < 0.5) {
      min -= 0.25;
      max += 0.25;
    } else {
      var pad = (max - min) * 0.18;
      min -= pad;
      max += pad;
    }
    var xMin = points[0].x;
    var xMax = points[points.length - 1].x;
    if (xMax === xMin) {
      xMax = xMin + 86400000;
      xMin = xMin - 86400000;
    }

    function px(x) {
      return padL + ((x - xMin) / (xMax - xMin)) * plotW;
    }
    function py(y) {
      return padT + (1 - (y - min) / (max - min)) * plotH;
    }

    ctx.clearRect(0, 0, w, h);

    // 网格 + y 轴
    ctx.font = "10px -apple-system, PingFang SC, sans-serif";
    ctx.fillStyle = "#aab0ba";
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    var gridCount = 3;
    for (var g = 0; g <= gridCount; g++) {
      var value = min + (max - min) * (g / gridCount);
      var gy = py(value);
      ctx.strokeStyle = "#eef0f4";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(padL, gy);
      ctx.lineTo(w - padR, gy);
      ctx.stroke();
      ctx.fillText(value.toFixed(opts.decimals || 0), padL - 6, gy);
    }

    // 目标线
    if (opts.goal) {
      var gy2 = py(opts.goal);
      if (gy2 > padT && gy2 < h - padB) {
        ctx.save();
        ctx.strokeStyle = "rgba(255,105,0,0.55)";
        ctx.lineWidth = 1.2;
        if (ctx.setLineDash) {
          ctx.setLineDash([5, 5]);
        }
        ctx.beginPath();
        ctx.moveTo(padL, gy2);
        ctx.lineTo(w - padR, gy2);
        ctx.stroke();
        ctx.restore();
        ctx.fillStyle = "#ff8a2b";
        ctx.textAlign = "left";
        ctx.textBaseline = "bottom";
        ctx.fillText("目标 " + opts.goal.toFixed(1), padL + 2, gy2 - 3);
      }
    }

    // 面积
    var grad = ctx.createLinearGradient(0, padT, 0, h - padB);
    grad.addColorStop(0, "rgba(255,105,0,0.18)");
    grad.addColorStop(1, "rgba(255,105,0,0)");
    ctx.beginPath();
    ctx.moveTo(px(points[0].x), h - padB);
    for (var j = 0; j < points.length; j++) {
      ctx.lineTo(px(points[j].x), py(points[j].y));
    }
    ctx.lineTo(px(points[points.length - 1].x), h - padB);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    // 折线
    ctx.beginPath();
    for (var k = 0; k < points.length; k++) {
      var x = px(points[k].x);
      var y = py(points[k].y);
      if (k === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.strokeStyle = "#ff6900";
    ctx.lineWidth = 2.4;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.stroke();

    // 点
    if (points.length <= 45) {
      for (var d = 0; d < points.length; d++) {
        var cx = px(points[d].x);
        var cy = py(points[d].y);
        ctx.beginPath();
        ctx.arc(cx, cy, d === points.length - 1 ? 4 : 2.6, 0, Math.PI * 2);
        ctx.fillStyle = "#ffffff";
        ctx.fill();
        ctx.lineWidth = d === points.length - 1 ? 2.4 : 1.6;
        ctx.strokeStyle = "#ff6900";
        ctx.stroke();
      }
    }

    // x 轴标签：首 / 中 / 尾
    ctx.fillStyle = "#aab0ba";
    ctx.textBaseline = "top";
    var labels = [{ x: points[0].x, off: points[0].m ? points[0].m.tz_offset_min : null }];
    if (points.length > 2) {
      var mid = points[Math.floor((points.length - 1) / 2)];
      labels.push({ x: mid.x, off: mid.m ? mid.m.tz_offset_min : null });
    }
    var tail = points[points.length - 1];
    labels.push({ x: tail.x, off: tail.m ? tail.m.tz_offset_min : null });
    for (var lb = 0; lb < labels.length; lb++) {
      ctx.textAlign = lb === 0 ? "left" : lb === labels.length - 1 ? "right" : "center";
      ctx.fillText(shortDate(labels[lb].x, labels[lb].off), px(labels[lb].x), h - padB + 7);
    }

    canvas._points = points.slice();
    canvas._geom = { padL: padL, padR: padR, padT: padT, padB: padB, w: w, h: h, xMin: xMin, xMax: xMax, min: min, max: max, px: px, opts: opts };
  }

  function handleChartPointer(clientX) {
    var canvas = $("chart");
    var tip = $("chartTip");
    if (!canvas || !canvas._points || !canvas._geom) {
      return;
    }
    var rect = canvas.getBoundingClientRect();
    var x = clientX - rect.left;
    var geom = canvas._geom;
    var best = null;
    var bestDist = Infinity;
    for (var i = 0; i < canvas._points.length; i++) {
      var dist = Math.abs(geom.px(canvas._points[i].x) - x);
      if (dist < bestDist) {
        bestDist = dist;
        best = canvas._points[i];
      }
    }
    if (!best || bestDist > 28) {
      tip.hidden = true;
      return;
    }
    var unit = geom.opts.unit;
    tip.hidden = false;
    tip.innerHTML = "<b>" + fullDate(best.x, best.m ? best.m.tz_offset_min : null) + " " +
      timeLabel(best.x, best.m ? best.m.tz_offset_min : null) + "</b> · " +
      fmt1(best.y) + " " + unit +
      (best.m && best.m.source ? " · " + sourceLabel(best.m.source) : "");
  }

  // ---------------------------------------------------------------- 弹层

  function openSheet(id) {
    var sheet = $(id);
    if (!sheet) {
      return;
    }
    var mask = $("mask");
    // 260ms 后隐藏遮罩的定时器还没触发就重新打开时，必须先取消并立刻显示遮罩，
    // 否则会出现「弹层是开的、下面却没有遮罩」的窗口。
    clearTimeout(App.maskTimer);
    App.maskTimer = null;
    mask.hidden = false;
    mask.classList.add("open");
    sheet.classList.add("open");
    sheet.scrollTop = 0;
    App.sheet = id;
    document.body.style.overflow = "hidden";
    // 焦点移入弹层（在 .open 之后，否则元素还是 display:none，focus 不生效）
    try {
      App.lastFocus = document.activeElement;
      sheet.focus();
    } catch (e) {
      App.lastFocus = null;
    }
  }

  function restoreFocus(el) {
    try {
      if (el && document.body.contains(el) && typeof el.focus === "function") {
        el.focus();
      }
    } catch (e) {
      /* 旧 WebView 上 focus 可能抛异常 */
    }
  }

  function closeSheet() {
    var mask = $("mask");
    if (App.sheet === "sheetWeigh") {
      // 先记住「用户主动关掉了称重层」，再清空 App.sheet，live 事件才不会再自动拉起
      App.weighDismissed = true;
    }
    var restore = App.lastFocus;
    App.lastFocus = null;
    clearTimeout(App.maskTimer);
    mask.classList.remove("open");
    App.maskTimer = setTimeout(function () {
      App.maskTimer = null;
      mask.hidden = true;
    }, 260);
    var sheets = document.querySelectorAll(".sheet");
    for (var i = 0; i < sheets.length; i++) {
      sheets[i].classList.remove("open");
    }
    App.sheet = null;
    App.weighOpen = false;
    document.body.style.overflow = "";
    restoreFocus(restore);
    callVoid("setLiveMode", false);
    if (App.discovering) {
      stopDiscover();
    }
  }

  function openWeigh() {
    if (!hasDevice()) {
      showToast("先绑定体重秤的 MAC 和 bind key");
      openDeviceSheet();
      return;
    }
    if (!hasKey()) {
      showToast("还缺少 bind key");
      openDeviceSheet();
      return;
    }
    App.weighDismissed = false;
    App.live = null;
    App.result = null;
    $("weighLive").hidden = false;
    $("weighResult").hidden = true;
    $("liveWeight").textContent = "--";
    $("weighChips").innerHTML = "";
    $("weighStatus").textContent = "正在等待秤…";
    $("weighHint").textContent = "赤脚站上秤，等数字稳定";
    openSheet("sheetWeigh");
    callVoid("setLiveMode", true);
    callVoid("startScan", "bind");
    clearTimeout(App.weighHintTimer);
    App.weighHintTimer = setTimeout(function () {
      if (App.sheet === "sheetWeigh" && !App.live && !App.result) {
        $("weighStatus").textContent = "还没收到数据";
        $("weighHint").textContent = "确认秤在身上、蓝牙已打开；第一次用请检查 bind key";
      }
    }, 8000);
  }

  function renderLive(data) {
    App.live = data;
    $("lastWeight").textContent = fmt1(data.weight_kg);
    $("liveWeight").textContent = fmt1(data.weight_kg);
    $("weighStatus").textContent = "正在称重…";
    $("weighHint").textContent = "站稳，保持不动";
    var chips = [];
    if (data.impedance_low_ohm) {
      chips.push('<span class="wchip">阻抗 <b>' + fmt1(data.impedance_low_ohm) + "</b> Ω</span>");
    }
    if (data.heart_rate) {
      chips.push('<span class="wchip">心率 <b>' + data.heart_rate + "</b> bpm</span>");
    }
    // 体脂 / BMI / BMR 全部来自原生 live 负载，页面里不再做任何本地估算
    if (data.body_fat_pct != null) {
      chips.push('<span class="wchip">体脂 <b>' + fmt1(data.body_fat_pct) + "</b>%</span>");
    }
    if (data.bmi != null) {
      chips.push('<span class="wchip">BMI <b>' + fmt1(data.bmi) + "</b></span>");
    }
    $("weighChips").innerHTML = chips.join("");
    renderScanChip();
  }

  function showResult(m, auto) {
    App.result = m;
    App.live = null;
    clearTimeout(App.weighHintTimer);
    $("weighLive").hidden = true;
    $("weighResult").hidden = false;
    $("resultWeight").textContent = fmt1(m.weight_kg);
    $("resultTime").textContent =
      dayLabel(m.measured_at_ms, m.tz_offset_min) + " " +
      timeLabel(m.measured_at_ms, m.tz_offset_min);
    var cells = "";
    if (m.body_fat_pct != null) {
      cells += resultCell("体脂率", fmt1(m.body_fat_pct), "%");
    }
    if (m.bmi != null) {
      cells += resultCell("BMI", fmt1(m.bmi), "");
    }
    if (m.bmr_kcal != null) {
      cells += resultCell("基础代谢", m.bmr_kcal, "kcal");
    }
    if (!cells) {
      cells = '<div class="result-cell" style="flex:1"><span>提示</span><b style="font-size:13px;font-weight:500">完善档案后估算体脂</b></div>';
    }
    $("resultMetrics").innerHTML = cells;
    if (!App.sheet) {
      openSheet("sheetWeigh");
    }
    App.weighOpen = true;
    renderScanChip();
  }

  function resultCell(label, value, unit) {
    return '<div class="result-cell"><span>' + label + "</span><b>" + value +
      (unit ? "<small>" + unit + "</small>" : "") + "</b></div>";
  }

  function openAddSheet() {
    var now = new Date();
    var local = now.getFullYear() + "-" + pad2(now.getMonth() + 1) + "-" + pad2(now.getDate()) +
      "T" + pad2(now.getHours()) + ":" + pad2(now.getMinutes());
    $("inputTime").value = local;
    $("inputWeight").value = latest() ? fmt1(latest().weight_kg) : "";
    $("inputNote").value = "";
    $("addError").hidden = true;
    openSheet("sheetAdd");
    setTimeout(function () {
      $("inputWeight").focus();
      $("inputWeight").select();
    }, 320);
  }

  function saveManual() {
    var weight = parseFloat($("inputWeight").value);
    var timeVal = $("inputTime").value;
    var note = $("inputNote").value.trim();
    var err = $("addError");
    if (!(weight >= 20 && weight <= 250)) {
      err.textContent = "请输入 20–250 kg 之间的体重";
      err.hidden = false;
      return;
    }
    var at = timeVal ? new Date(timeVal).getTime() : Date.now();
    if (isNaN(at)) {
      at = Date.now();
    }
    var result = callJson("addManual", JSON.stringify({
      weight_kg: weight,
      measured_at_ms: at,
      note: note
    }));
    if (!result || !result.ok) {
      err.textContent = (result && result.error) || "保存失败";
      err.hidden = false;
      return;
    }
    closeSheet();
    reload();
    renderAll();
    showToast("已记录 " + fmt1(weight) + " kg");
  }

  function openProfileSheet() {
    var p = (App.state && App.state.profile) || {};
    $("pName").value = p.name || "";
    $("pHeight").value = p.heightCm ? p.heightCm : "";
    $("pAge").value = p.age ? p.age : "";
    $("pTarget").value = p.targetWeightKg ? fmt1(p.targetWeightKg) : "";
    App.profileSheetSex = p.sex === "female" ? "female" : "male";
    updateSexSeg();
    $("profileError").hidden = true;
    openSheet("sheetProfile");
  }

  function updateSexSeg() {
    var buttons = document.querySelectorAll("#sexSeg .seg-item");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].classList.toggle("active", buttons[i].getAttribute("data-sex") === App.profileSheetSex);
    }
  }

  function saveProfile() {
    var name = $("pName").value.trim();
    var height = parseInt($("pHeight").value, 10);
    var age = parseInt($("pAge").value, 10);
    var t = parseFloat($("pTarget").value);
    var err = $("profileError");
    if (!height || height < 80 || height > 250) {
      err.textContent = "请输入 80–250 cm 之间的身高";
      err.hidden = false;
      return;
    }
    if (!age || age < 5 || age > 120) {
      err.textContent = "请输入 5–120 岁之间的年龄";
      err.hidden = false;
      return;
    }
    var result = callJson("saveProfile", JSON.stringify({
      name: name,
      heightCm: height,
      age: age,
      sex: App.profileSheetSex,
      targetWeightKg: isNaN(t) ? 0 : t
    }));
    if (!result || !result.ok) {
      err.textContent = (result && result.error) || "保存失败";
      err.hidden = false;
      return;
    }
    closeSheet();
    reload();
    renderAll();
    showToast("档案已保存");
  }

  function openDeviceSheet() {
    var device = (App.state && App.state.device) || {};
    $("dMac").value = device.mac || "";
    $("dKey").value = "";
    $("dKey").type = "password";
    $("dKey").placeholder = device.bindKeySet ? "已保存，留空表示不修改" : "粘贴 32 位 hex bind key";
    var toggle = document.querySelector('[data-action="toggle-key"]');
    if (toggle) {
      toggle.textContent = "显示";
    }
    App.candidates = [];
    $("scanList").innerHTML = "";
    renderDeviceBanner();
    renderPresetBox();
    openSheet("sheetDevice");
  }

  var SETTINGS_TARGETS = {
    no_permission: "",
    bt_off: "bluetooth",
    location_off: "location"
  };

  function scanErrorText(err) {
    if (err && err.message) {
      return err.message;
    }
    var code = err && err.code;
    if (code === "no_permission") {
      return "缺少扫描权限，点「去设置」在应用详情里授予权限";
    }
    if (code === "bt_off") {
      return "蓝牙没有打开，点「去设置」打开蓝牙";
    }
    if (code === "location_off") {
      return "系统定位开关未打开，点「去设置」开启";
    }
    if (code === "no_config") {
      return "还没有绑定体重秤，先填写 MAC 和 bind key";
    }
    if (code === "no_key") {
      return "缺少 bind key，无法解密体重秤广播";
    }
    if (code === "decode_failed") {
      return "解密广播失败，请检查 bind key 是否正确";
    }
    if (code === "db_error") {
      return "数据库读写失败，请重试或重开 App";
    }
    if (code === "scan_failed") {
      return "蓝牙扫描启动失败，正在自动重试";
    }
    return "设备状态异常";
  }

  function renderDeviceBanner() {
    var el = $("deviceBanner");
    if (!el) {
      return;
    }
    var device = (App.state && App.state.device) || {};
    var scan = (App.state && App.state.scan) || {};
    var err = scan.error;
    if (err && err.code) {
      var target = SETTINGS_TARGETS.hasOwnProperty(err.code) ? SETTINGS_TARGETS[err.code] : null;
      var html = '<span class="dot warn"></span><div class="device-banner-main"><b>' +
        esc(scanErrorText(err)) + "</b>" +
        (err.code === "scan_failed" ? "稍等几秒会自动重试，也可以去设置看看蓝牙状态。" : "") +
        "</div>";
      if (target != null) {
        html += '<button class="btn-ghost banner-btn" data-action="open-settings" data-target="' +
          esc(target) + '" type="button">去设置</button>';
      }
      el.innerHTML = html;
      return;
    }
    var bound = device.mac && device.bindKeySet;
    var msg;
    if (bound) {
      msg = "<b>" + (device.preset ? "已预绑定" : "已绑定") + " · " +
        esc(device.name || "S400") + "</b>" +
        esc(device.mac) + " · " +
        (scan.rescue ? "超时后正在重新广播发现（地址不对会自动纠正）"
          : scan.scanning ? "正在扫描" : "停在后台扫描");
    } else if (device.mac) {
      msg = "<b>还需要 bind key</b>" + esc(device.mac) + " · 填好密钥后即可解密广播";
    } else {
      msg = "<b>还没有绑定体重秤</b>扫描附近设备或手动填写 MAC 和 bind key";
    }
    el.innerHTML = '<span class="dot ' + (bound ? "on" : "warn") + '"></span><div>' + msg + "</div>";
  }

  /** 预绑定提示块：只有 build.sh 注入了 S400 的安装包才显示。 */
  function renderPresetBox() {
    var box = $("presetBox");
    if (!box) {
      return;
    }
    var device = (App.state && App.state.device) || {};
    if (!device.preset) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    var info = $("presetInfo");
    if (info) {
      info.textContent = (device.presetName || "Xiaomi Scale S400") +
        " · " + (device.presetMac || "");
    }
  }

  /** 恢复构建时内置的预绑定秤（不用扫描添加）。 */
  function applyPreset() {
    var result = callJson("applyPresetDevice");
    if (!result || !result.ok) {
      showToast((result && result.error) || "恢复预绑定失败");
      return;
    }
    closeSheet();
    reload();
    renderAll();
    showToast("已恢复预绑定 " + ((result.device && result.device.name) || "S400"));
  }

  function toggleKey() {
    var input = $("dKey");
    var button = document.querySelector('[data-action="toggle-key"]');
    if (input.type === "password") {
      input.type = "text";
      button.textContent = "隐藏";
    } else {
      input.type = "password";
      button.textContent = "显示";
    }
  }

  function saveDevice() {
    var mac = $("dMac").value.trim();
    var key = $("dKey").value.trim();
    var result = callJson("saveDevice", JSON.stringify({
      mac: mac,
      bindKey: key,
      name: mac ? "Xiaomi Scale S400 " + mac.replace(/[^0-9A-Fa-f]/g, "").slice(-4).toUpperCase() : ""
    }));
    if (!result || !result.ok) {
      showToast((result && result.error) || "保存失败");
      return;
    }
    closeSheet();
    reload();
    renderAll();
    if (result.keyValid) {
      showToast("已保存，站上秤试试");
    } else {
      showToast("MAC 已保存，还缺 bind key");
    }
  }

  function startDiscover() {
    App.candidates = [];
    App.discovering = true;
    $("scanList").innerHTML = '<div class="scan-empty">扫描中…请让秤亮起来</div>';
    $("scanNearbyBtn").textContent = "正在扫描…（15 秒）";
    callVoid("startScan", "discover");
    clearTimeout(App.discoverTimer);
    App.discoverTimer = setTimeout(stopDiscover, 15000);
  }

  function stopDiscover() {
    App.discovering = false;
    clearTimeout(App.discoverTimer);
    callVoid("stopScan");
    var btn = $("scanNearbyBtn");
    if (btn) {
      btn.textContent = "重新扫描附近设备";
    }
    if (!App.candidates.length && $("scanList")) {
      $("scanList").innerHTML = '<div class="scan-empty">没有扫到。站上秤唤醒后重试，或手动填写 MAC。</div>';
    }
    renderDeviceBanner();
  }

  function renderCandidates() {
    var el = $("scanList");
    if (!el) {
      return;
    }
    if (!App.candidates.length) {
      return;
    }
    var html = "";
    for (var i = 0; i < App.candidates.length; i++) {
      var c = App.candidates[i];
      html += '<button class="scan-item" data-action="pick-candidate" data-mac="' + esc(c.address) +
        '" data-name="' + esc(c.name || "") + '" type="button">' +
        '<div class="scan-item-main"><div class="scan-item-name">' +
        esc(c.name || "小米体脂秤") + "</div>" +
        '<div class="scan-item-sub">' + esc(c.address) + "</div></div>" +
        '<div class="scan-item-rssi">' + c.rssi + " dBm</div></button>";
    }
    el.innerHTML = html;
  }

  function openDetail(id) {
    var m = null;
    for (var i = 0; i < App.records.length; i++) {
      if (App.records[i].id === id) {
        m = App.records[i];
        break;
      }
    }
    if (!m) {
      return;
    }
    App.detailId = id;
    App.deleteArmed = false;
    var cells = "";
    cells += detailCell("体脂率", m.body_fat_pct != null ? fmt1(m.body_fat_pct) : "--", "%");
    cells += detailCell("BMI", m.bmi != null ? fmt1(m.bmi) : "--", "");
    cells += detailCell("基础代谢", m.bmr_kcal != null ? m.bmr_kcal : "--", "kcal");
    cells += detailCell("低频阻抗", m.impedance_low_ohm != null ? fmt1(m.impedance_low_ohm) : "--", "Ω");
    cells += detailCell("高频阻抗", m.impedance_high_ohm != null ? fmt1(m.impedance_high_ohm) : "--", "Ω");
    cells += detailCell("心率", m.heart_rate != null ? m.heart_rate : "--", "bpm");
    var noteHtml = m.note
      ? '<div class="detail-note"><b>备注</b>' + esc(m.note) + "</div>"
      : "";
    $("detailBody").innerHTML =
      '<div class="detail-hero"><div class="detail-weight">' + fmt1(m.weight_kg) +
      "<small>kg</small></div>" +
      '<div class="detail-time">' + dayLabel(m.measured_at_ms, m.tz_offset_min) + " " +
      timeLabel(m.measured_at_ms, m.tz_offset_min) + " · " + sourceLabel(m.source) + "</div></div>" +
      '<div class="detail-grid">' + cells + "</div>" + noteHtml +
      '<label class="field"><span class="field-label">备注</span>' +
      '<input id="detailNote" class="input" type="text" maxlength="60" placeholder="加一条备注">' +
      "</label>" +
      '<button class="btn-ghost btn-block" data-action="save-note" type="button">保存备注</button>' +
      '<div class="detail-actions">' +
      '<button class="btn-ghost" data-action="delete-record" type="button" id="deleteBtn">删除这条</button>' +
      '<button class="btn-primary" data-action="close-sheet" type="button">完成</button></div>';
    var noteInput = $("detailNote");
    if (noteInput && m.note) {
      noteInput.value = m.note;
    }
    openSheet("sheetDetail");
  }

  function detailCell(label, value, unit) {
    return '<div class="detail-cell"><span>' + label + "</span><b>" + value +
      (unit ? "<small>" + unit + "</small>" : "") + "</b></div>";
  }

  function saveNote() {
    var input = $("detailNote");
    if (!input || !App.detailId) {
      return;
    }
    var result = callJson("updateNote", App.detailId, input.value.trim());
    if (!result || !result.ok) {
      showToast("保存备注失败");
      return;
    }
    reload();
    renderAll();
    showToast("备注已保存");
    closeSheet();
  }

  function deleteRecord() {
    if (!App.detailId) {
      return;
    }
    if (!App.deleteArmed) {
      App.deleteArmed = true;
      var btn = $("deleteBtn");
      if (btn) {
        btn.textContent = "再点一次确认删除";
      }
      return;
    }
    var result = callJson("remove", App.detailId);
    if (!result || !result.ok) {
      showToast("删除失败");
      return;
    }
    closeSheet();
    reload();
    renderAll();
    showToast("已删除这条记录");
  }

  function exportData(format) {
    var result = callJson("exportData", format);
    if (!result) {
      showToast("导出失败");
      return;
    }
    if (result.pending) {
      // 原生会在存储权限授权后自动重试导出，并推 export 事件；这里只提示一次
      showToast("已请求存储权限，授权后自动导出");
      return;
    }
    if (!result.ok) {
      showToast(result.error || "导出失败");
      return;
    }
    showToast("已导出 " + result.count + " 条 · " + (result.path || "下载目录"));
  }

  function seedDemo() {
    var result = callJson("seedDemo");
    if (!result || !result.ok) {
      showToast("生成演示数据失败");
      return;
    }
    reload();
    renderAll();
    showToast("已生成 " + result.count + " 条演示数据");
  }

  function clearAll() {
    if (!App.clearArmed) {
      App.clearArmed = true;
      showToast("再点一次「清空全部记录」确认");
      setTimeout(function () {
        App.clearArmed = false;
      }, 4000);
      return;
    }
    App.clearArmed = false;
    var result = callJson("clearAll");
    if (!result || !result.ok) {
      showToast("清空失败");
      return;
    }
    reload();
    renderAll();
    showToast("记录已清空");
  }

  /** 只删 source='demo' 的记录，真实记录保留。两次确认与「清空全部」一致。 */
  function clearDemo() {
    if (!App.clearDemoArmed) {
      App.clearDemoArmed = true;
      showToast("再点一次确认清除演示记录");
      setTimeout(function () {
        App.clearDemoArmed = false;
      }, 4000);
      return;
    }
    App.clearDemoArmed = false;
    var result = callJson("clearDemo");
    if (!result || !result.ok) {
      showToast((result && result.error) || "清除演示记录失败");
      return;
    }
    reload();
    renderAll();
    showToast("已清除 " + result.count + " 条演示记录");
  }

  // ---------------------------------------------------------------- 视图切换 / 引导

  function switchView(name) {
    App.view = name;
    var views = document.querySelectorAll(".view");
    for (var i = 0; i < views.length; i++) {
      views[i].classList.toggle("active", views[i].id === "view-" + name);
    }
    var tabs = document.querySelectorAll(".tab-item");
    for (var j = 0; j < tabs.length; j++) {
      tabs[j].classList.toggle("active", tabs[j].getAttribute("data-view") === name);
    }
    window.scrollTo(0, 0);
    if (name === "trend") {
      renderTrend();
    } else if (name === "records") {
      renderRecords();
    } else if (name === "me") {
      renderMe();
    } else {
      renderHome();
    }
  }

  function showOnboarding() {
    var el = $("onboarding");
    if (el) {
      el.hidden = false;
      el.scrollTop = 0;
      try {
        App.onboardFocus = document.activeElement;
        el.focus();
      } catch (e) {
        App.onboardFocus = null;
      }
      document.body.style.overflow = "hidden";
    }
  }

  function hideOnboarding() {
    var el = $("onboarding");
    var restore = App.onboardFocus;
    App.onboardFocus = null;
    if (el) {
      el.hidden = true;
    }
    document.body.style.overflow = "";
    restoreFocus(restore);
  }

  // ---------------------------------------------------------------- 事件

  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || !target.closest) {
      return;
    }

    var candidate = target.closest("[data-candidate]");
    if (candidate) {
      pickCandidate(candidate);
      return;
    }

    var actionEl = target.closest("[data-action]");
    if (actionEl) {
      var action = actionEl.getAttribute("data-action");
      handleAction(action, actionEl, event);
      return;
    }

    var tab = target.closest("[data-view]");
    if (tab) {
      switchView(tab.getAttribute("data-view"));
      return;
    }

    var daysEl = target.closest("[data-days]");
    if (daysEl) {
      setTrendDays(parseInt(daysEl.getAttribute("data-days"), 10));
      return;
    }

    var rangeEl = target.closest("[data-range]");
    if (rangeEl) {
      setRecordRange(parseInt(rangeEl.getAttribute("data-range"), 10));
      return;
    }

    var metricEl = target.closest("[data-metric]");
    if (metricEl) {
      setMetric(metricEl.getAttribute("data-metric"));
      return;
    }

    var sexEl = target.closest("[data-sex]");
    if (sexEl) {
      App.profileSheetSex = sexEl.getAttribute("data-sex");
      updateSexSeg();
      return;
    }

    var idEl = target.closest("[data-id]");
    if (idEl) {
      openDetail(parseInt(idEl.getAttribute("data-id"), 10));
    }
  });

  function handleAction(action, el, event) {
    switch (action) {
      case "close-sheet":
        closeSheet();
        break;
      case "add-manual":
        openAddSheet();
        break;
      case "save-manual":
        saveManual();
        break;
      case "weigh":
        openWeigh();
        break;
      case "weigh-again":
        App.weighDismissed = false;
        App.result = null;
        $("weighResult").hidden = true;
        $("weighLive").hidden = false;
        $("liveWeight").textContent = "--";
        $("weighChips").innerHTML = "";
        $("weighStatus").textContent = "正在等待秤…";
        $("weighHint").textContent = "赤脚站上秤，等数字稳定";
        callVoid("startScan", "bind");
        break;
      case "go-records":
        switchView("records");
        break;
      case "open-profile":
        openProfileSheet();
        break;
      case "save-profile":
        saveProfile();
        break;
      case "open-device":
        openDeviceSheet();
        break;
      case "open-settings":
        callVoid("openSettings", el.getAttribute("data-target") || "");
        break;
      case "save-device":
        saveDevice();
        break;
      case "apply-preset":
        applyPreset();
        break;
      case "clear-device":
        confirmClearDevice();
        break;
      case "toggle-key":
        toggleKey();
        break;
      case "scan-nearby":
        startDiscover();
        break;
      case "pick-candidate":
        pickCandidate(el);
        break;
      case "open-record":
        openDetail(parseInt(el.getAttribute("data-id"), 10));
        break;
      case "save-note":
        saveNote();
        break;
      case "delete-record":
        deleteRecord();
        break;
      case "export-csv":
        exportData("csv");
        break;
      case "export-json":
        exportData("json");
        break;
      case "seed-demo":
        seedDemo();
        break;
      case "clear-demo":
        clearDemo();
        break;
      case "clear-all":
        clearAll();
        break;
      case "onboard-setup":
        callVoid("setOnboarded", true);
        hideOnboarding();
        if (App.state) {
          App.state.onboarded = true;
        }
        setTimeout(function () {
          openProfileSheet();
        }, 260);
        break;
      case "onboard-demo":
        callVoid("setOnboarded", true);
        seedDemo();
        hideOnboarding();
        break;
      default:
        break;
    }
  }

  function pickCandidate(el) {
    var mac = el.getAttribute("data-mac");
    var name = el.getAttribute("data-name");
    $("dMac").value = mac;
    if (!hasKey()) {
      $("dKey").focus();
    }
    stopDiscover();
    showToast("已选择 " + (name || mac) + "，填入 bind key 后保存");
  }

  function confirmClearDevice() {
    if (!App.clearArmedDevice) {
      App.clearArmedDevice = true;
      showToast("再点一次确认解绑");
      setTimeout(function () {
        App.clearArmedDevice = false;
      }, 4000);
      return;
    }
    App.clearArmedDevice = false;
    var result = callJson("clearDevice");
    if (!result || !result.ok) {
      showToast("解绑失败");
      return;
    }
    closeSheet();
    reload();
    renderAll();
    showToast("已解除绑定，历史记录保留");
  }

  function setTrendDays(days) {
    App.trendDays = days;
    var buttons = document.querySelectorAll("#rangeSeg .seg-item");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].classList.toggle("active", parseInt(buttons[i].getAttribute("data-days"), 10) === days);
    }
    renderTrend();
  }

  function setRecordRange(days) {
    App.recordDays = days;
    var buttons = document.querySelectorAll("#recordSeg .seg-item");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].classList.toggle("active", parseInt(buttons[i].getAttribute("data-range"), 10) === days);
    }
    renderRecords();
  }

  function setMetric(metric) {
    App.metric = metric === "fat" ? "fat" : "weight";
    var buttons = document.querySelectorAll("#metricSeg .seg-item");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].classList.toggle("active", buttons[i].getAttribute("data-metric") === App.metric);
    }
    renderTrend();
  }

  // ---------------------------------------------------------------- 原生事件

  window.__wlEvent__ = function (type, payload) {
    if (typeof payload === "string") {
      try {
        payload = JSON.parse(payload);
      } catch (e) {
        payload = null;
      }
    }
    if (type === "state") {
      if (payload) {
        App.state = payload;
        var count = payload.records && payload.records.count;
        if (count != null && count !== App.records.length) {
          App.records = callJson("list", 0) || App.records;
        }
        renderAll();
      }
      return;
    }
    if (type === "live") {
      if (!payload) {
        return;
      }
      if (!App.sheet && !App.weighOpen && !App.weighDismissed) {
        openWeigh();
      }
      renderLive(payload);
      return;
    }
    if (type === "final") {
      if (!payload || !payload.measurement) {
        return;
      }
      var m = payload.measurement;
      var dismissed = App.weighDismissed;
      // 新的称重已经出结果，下次 live 帧可以重新自动拉起称重层
      App.weighDismissed = false;
      App.live = null;
      if (payload.state) {
        App.state = payload.state;
      }
      var exists = false;
      for (var i = 0; i < App.records.length; i++) {
        if (App.records[i].id === m.id) {
          exists = true;
          break;
        }
      }
      if (!exists) {
        App.records.unshift(m);
        App.records.sort(function (a, b) {
          return b.measured_at_ms - a.measured_at_ms || b.id - a.id;
        });
      }
      if (!App.sheet) {
        if (dismissed) {
          // 用户已经主动关掉称重层：记录照常落库，只提示一下，不再强制弹层
          showToast("已记录 " + fmt1(m.weight_kg) + " kg");
        } else {
          openSheet("sheetWeigh");
        }
      }
      if (!dismissed) {
        showResult(m, false);
      }
      renderAll();
      return;
    }
    if (type === "candidates") {
      App.candidates = payload || [];
      renderCandidates();
      if (App.candidates.length) {
        $("scanNearbyBtn").textContent = "重新扫描附近设备";
      }
      return;
    }
    if (type === "device") {
      // 兜底逻辑纠正了秤的广播地址（MAC 变了但 bind key 还能解密）
      if (payload && payload.code === "address_corrected") {
        showToast("秤的地址变了，已自动纠正为 " + (payload.mac || ""));
        reload();
        renderAll();
      }
      return;
    }
    if (type === "error") {
      if (payload && payload.message) {
        showToast(payload.message);
      }
      return;
    }
    if (type === "export") {
      if (payload && payload.ok) {
        showToast("已导出 " + payload.count + " 条 · " + (payload.path || "下载目录"));
      }
      return;
    }
  };

  window.wlHandleBack = function () {
    if (!$("onboarding").hidden) {
      hideOnboarding();
      if (App.state) {
        callVoid("setOnboarded", true);
        App.state.onboarded = true;
      }
      return true;
    }
    if (App.sheet) {
      closeSheet();
      return true;
    }
    return false;
  };

  // ---------------------------------------------------------------- 初始化

  function bindChart() {
    var canvas = $("chart");
    if (!canvas) {
      return;
    }
    canvas.addEventListener("touchstart", function (e) {
      if (e.touches && e.touches.length) {
        handleChartPointer(e.touches[0].clientX);
      }
    }, { passive: true });
    canvas.addEventListener("touchmove", function (e) {
      if (e.touches && e.touches.length) {
        handleChartPointer(e.touches[0].clientX);
      }
    }, { passive: true });
    canvas.addEventListener("mousemove", function (e) {
      handleChartPointer(e.clientX);
    });
    canvas.addEventListener("mouseleave", function () {
      var tip = $("chartTip");
      if (tip) {
        tip.hidden = true;
      }
    });
    window.addEventListener("resize", function () {
      clearTimeout(App.resizeTimer);
      App.resizeTimer = setTimeout(function () {
        if (App.view === "home") {
          renderSpark();
        }
        if (App.view === "trend") {
          renderTrend();
        }
      }, 160);
    });
  }

  function init() {
    reload();
    renderAll();
    bindChart();
    if (!App.state) {
      showToast("初始化失败，请重开 App");
      return;
    }
    // 预绑定包：引导里直接说明不用扫描添加
    if (App.state.device && App.state.device.preset) {
      var step2 = $("onboardStep2");
      if (step2) {
        step2.textContent = "S400 已预绑定，不用再扫描添加（可在「我的 → 体重秤」里恢复）";
      }
    }
    if (!App.state.onboarded) {
      showOnboarding();
    }
    if (App.demo) {
      var hint = $("demoHint");
      if (hint) {
        hint.hidden = false;
      }
    }
    if (window.console) {
      console.log("WeightLog ready", App.state.version, App.records.length + " records");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
