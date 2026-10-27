/* 夜莺界面基础件：DOM 助手、图标、评分环、全夜时间线、弹层。不依赖任何外部库。 */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';

  // ---------- DOM ----------
  function add(el, kids) {
    if (kids == null || kids === false) return;
    if (!Array.isArray(kids)) kids = [kids];
    for (var i = 0; i < kids.length; i++) {
      var c = kids[i];
      if (c == null || c === false) continue;
      if (Array.isArray(c)) { add(el, c); continue; }
      el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
    }
  }
  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) for (var k in attrs) {
      var v = attrs[k];
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    add(el, kids);
    return el;
  }
  function s(tag, attrs, kids) {
    var el = document.createElementNS(NS, tag);
    if (attrs) for (var k in attrs) {
      var v = attrs[k];
      if (v == null || v === false) continue;
      if (k === 'text') el.textContent = v;
      else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v);
    }
    add(el, kids);
    return el;
  }

  // ---------- 图标（24×24，Google Material Design 图标，Apache 2.0）----------
  var ICON = {
    moon: 'M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z',
    chart: 'M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z',
    person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    check: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    warn: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
    left: 'M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z',
    right: 'M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
    down: 'M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    mic: 'M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z',
    battery: 'M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z',
    lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
    info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
    bell: 'M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z',
    trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    help: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17h-2v-2h2v2zm2.07-7.75l-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H8c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .88-.36 1.68-.93 2.25z',
    phone: 'M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z',
    wave: 'M7 18h2V6H7v12zm4 4h2V2h-2v20zm-8-8h2v-4H3v4zm12 4h2V6h-2v12zm4-8v4h2v-4h-2z',
    sun: 'M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79 1.42-1.41zM4 10.5H1v2h3v-2zm9-9.95h-2V3.5h2V.55zm7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41 1.79-1.79zm-3.21 13.7l1.79 1.8 1.41-1.41-1.8-1.79-1.4 1.4zM20 10.5v2h3v-2h-3zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6-2.69-6-6-6zm-1 16.95h2V19.5h-2v2.95zm-7.45-3.91l1.41 1.41 1.79-1.8-1.41-1.41-1.79 1.8z'
  };
  function icon(name, size, cls) {
    var sz = size || 24;
    return s('svg', { viewBox: '0 0 24 24', width: sz, height: sz, 'aria-hidden': 'true', 'class': cls || '' }, [s('path', { d: ICON[name], fill: 'currentColor' })]);
  }

  // ---------- 时间与文字 ----------
  function p2(n) { return n < 10 ? '0' + n : '' + n; }
  function clock(ms) { var d = new Date(ms); return p2(d.getHours()) + ':' + p2(d.getMinutes()); }
  function hm(min) {
    min = Math.round(min);
    if (min < 60) return min + ' 分钟';
    var hh = Math.floor(min / 60), m = min % 60;
    return m === 0 ? hh + ' 小时' : hh + ' 小时 ' + m + ' 分';
  }
  var WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  function dayWeek(ms) { var d = new Date(ms); return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + WEEK[d.getDay()]; }

  // ---------- 评分环 ----------
  function scoreRing(score, size) {
    var sz = size || 112, r = sz / 2 - 6, c = 2 * Math.PI * r;
    var frac = score >= 0 ? Math.max(0, Math.min(1, score / 100)) : 0;
    var arc = s('circle', { cx: sz / 2, cy: sz / 2, r: r, fill: 'none', stroke: 'var(--accent-text)', 'stroke-width': sz * 0.08, 'stroke-linecap': 'round',
      'stroke-dasharray': c, 'stroke-dashoffset': c, transform: 'rotate(-90 ' + sz / 2 + ' ' + sz / 2 + ')' });
    var svg = s('svg', { viewBox: '0 0 ' + sz + ' ' + sz, width: sz, height: sz, role: 'img', 'aria-label': score >= 0 ? '睡眠评分 ' + score + ' 分，满分 100' : '这晚不计分' }, [
      s('circle', { cx: sz / 2, cy: sz / 2, r: r, fill: 'none', stroke: 'var(--surface-3)', 'stroke-width': sz * 0.08 }), arc]);
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var target = c * (1 - frac);
    if (reduce || frac === 0) arc.setAttribute('stroke-dashoffset', target);
    else {
      arc.style.transition = 'stroke-dashoffset .6s ease-out';
      setTimeout(function () { arc.setAttribute('stroke-dashoffset', target); }, 30);
    }
    var wrap = h('div', { class: 'ring' }, [svg, h('div', { class: 'num' }, [
      h('div', { class: 'score', text: score >= 0 ? String(score) : '--' }), h('div', { class: 'caption muted', text: '睡眠评分' })])]);
    return wrap;
  }

  // ---------- 全夜时间线 ----------
  // 上：睡眠状态条；中：每 10 分钟的打鼾（高度 = 这 10 分钟里打鼾占多少，颜色 = 多响）；
  // 下：疑似呼吸暂停（菱形，带编号，挤在一起时错开成多行）和其他声音（小图形）；最下面是整点刻度。
  function stackRows(xs, minGap) {
    var rows = [], last = [];
    for (var i = 0; i < xs.length; i++) {
      var r = 0;
      while (last[r] != null && xs[i] - last[r] < minGap) r++;
      last[r] = xs[i]; rows.push(r);
    }
    return rows;
  }
  function timeline(tl, opts) {
    opts = opts || {};
    var W = 340, L = 34, R = 6, PW = W - L - R;
    var t0 = tl.t0, t1 = tl.t1, span = t1 - t0;
    function X(t) { return L + (t - t0) / span * PW; }

    // 纵轴上限：取这晚最高的占比，向上取整到 25% 的倍数
    var maxShare = 0;
    (tl.snore || []).forEach(function (b) { if (b.share > maxShare) maxShare = b.share; });
    var yMax = Math.max(0.25, Math.min(1, Math.ceil(maxShare * 4) / 4));

    var apx = (tl.apnea || []).map(function (a) { return X(a.t); });
    var apRows = stackRows(apx, 17);
    var apN = apRows.length ? Math.max.apply(null, apRows) + 1 : 1;
    var mkx = (tl.marks || []).map(function (m) { return X(m.t); });
    var mkRows = stackRows(mkx, 13);
    var mkN = mkRows.length ? Math.max.apply(null, mkRows) + 1 : 1;

    var yStage = 4, hStage = 12, yBars = 48, hBars = 112, gridBot = yBars + hBars;
    var yApnea = gridBot + 22, apH = apN * 19;
    var yMarks = yApnea + apH + 6, mkH = mkN * 16;
    var yAxis = yMarks + mkH + 16, H = yAxis + 6;
    var svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': tl.summary || '' });
    var mono = 'var(--font-mono)';

    // 最集中的时段（先画，垫在底下）
    if (tl.peak) {
      var px = X(tl.peak.a), pw = Math.max(6, X(tl.peak.b) - X(tl.peak.a));
      svg.appendChild(s('rect', { x: px, y: yBars - 8, width: pw, height: hBars + 8, rx: 6, fill: 'var(--accent-container)', opacity: '.85' }));
      svg.appendChild(s('text', { x: px + pw / 2, y: yBars - 14, 'text-anchor': 'middle', fill: 'var(--accent-text)', 'font-size': 11, 'font-weight': 500, text: '最集中' }));
    }
    // 纵向参考线
    [[0, '0'], [0.5, Math.round(yMax * 50) + '%'], [1, Math.round(yMax * 100) + '%']].forEach(function (g) {
      var y = gridBot - g[0] * hBars;
      svg.appendChild(s('line', { x1: L, x2: W - R, y1: y, y2: y, stroke: g[0] === 0 ? 'var(--outline)' : 'var(--hairline)', 'stroke-width': 1, 'stroke-dasharray': g[0] === 0 ? '' : '3 4' }));
      svg.appendChild(s('text', { x: L - 5, y: y + 4, 'text-anchor': 'end', fill: 'var(--ink-muted)', 'font-size': 10, 'font-family': mono, text: g[1] }));
    });

    // 睡眠状态条
    var stColor = { 0: 'var(--stage-awake)', 1: 'var(--stage-light)', 2: 'var(--stage-deep)' };
    svg.appendChild(s('text', { x: L - 5, y: yStage + 10, 'text-anchor': 'end', fill: 'var(--ink-muted)', 'font-size': 10, text: '状态' }));
    svg.appendChild(s('rect', { x: L, y: yStage, width: PW, height: hStage, rx: 4, fill: 'var(--surface-3)' }));
    if (tl.motionOk) {
      (tl.stages || []).forEach(function (g) {
        if (g.s < 0) return;
        svg.appendChild(s('rect', { x: X(g.a), y: yStage, width: Math.max(1, X(g.b) - X(g.a)), height: hStage, fill: stColor[g.s] }));
      });
    } else if (tl.onset > 0 && tl.wake > 0) {
      svg.appendChild(s('rect', { x: X(tl.onset), y: yStage, width: Math.max(1, X(tl.wake) - X(tl.onset)), height: hStage, fill: 'var(--stage-light)', opacity: '.55' }));
    }

    // 鼾声柱
    var bw = tl.binMs / span * PW;
    var lvColor = { 1: 'var(--loud-1)', 2: 'var(--loud-2)', 3: 'var(--loud-3)' };
    (tl.snore || []).forEach(function (b) {
      var x = L + b.i * bw + 1, hh = Math.max(4, Math.min(1, b.share / yMax) * hBars), y = gridBot - hh;
      var sel = opts.selectedBin === b.i;
      svg.appendChild(s('rect', { x: x, y: y, width: Math.max(2, bw - 2), height: hh, rx: 2.5, fill: lvColor[b.lv], stroke: sel ? 'var(--ink)' : 'none', 'stroke-width': 1.5 }));
      svg.appendChild(s('rect', { x: x - 1, y: yBars - 8, width: bw, height: hBars + 8, fill: 'transparent', style: 'cursor:pointer', onclick: function () { if (opts.onBin) opts.onBin(b); } }));
    });
    (tl.gaps || []).forEach(function (g) {
      svg.appendChild(s('rect', { x: X(g.a), y: yStage, width: Math.max(1, X(g.b) - X(g.a)), height: gridBot - yStage, fill: 'var(--surface-1)', opacity: '.8' }));
    });

    // 疑似呼吸暂停：菱形 + 向上的虚线
    svg.appendChild(s('text', { x: L - 5, y: yApnea + 4, 'text-anchor': 'end', fill: 'var(--ink-muted)', 'font-size': 10, text: '暂停' }));
    var many = (tl.apnea || []).length > 9;
    (tl.apnea || []).forEach(function (a, idx) {
      var x = apx[idx], cy = yApnea + apRows[idx] * 19;
      svg.appendChild(s('line', { x1: x, x2: x, y1: gridBot, y2: cy - 8, stroke: 'var(--event-apnea)', 'stroke-width': 1, 'stroke-dasharray': '2 3', opacity: '.55' }));
      var sel = opts.selectedApnea === a.id;
      var g = s('g', { style: 'cursor:pointer', onclick: function () { if (opts.onApnea) opts.onApnea(a, idx); } });
      g.appendChild(s('circle', { cx: x, cy: cy, r: 14, fill: 'transparent' }));
      g.appendChild(s('path', { d: 'M' + x + ' ' + (cy - 9) + ' L' + (x + 9) + ' ' + cy + ' L' + x + ' ' + (cy + 9) + ' L' + (x - 9) + ' ' + cy + ' Z', fill: 'var(--event-apnea)', stroke: sel ? 'var(--ink)' : 'var(--surface-1)', 'stroke-width': sel ? 2 : 1.5 }));
      if (!many) g.appendChild(s('text', { x: x, y: cy + 3.5, 'text-anchor': 'middle', fill: 'var(--on-alert)', 'font-size': 10, 'font-weight': 700, text: String(idx + 1) }));
      svg.appendChild(g);
    });

    // 其他声音
    if ((tl.marks || []).length) svg.appendChild(s('text', { x: L - 5, y: yMarks + 8, 'text-anchor': 'end', fill: 'var(--ink-muted)', 'font-size': 10, text: '其他' }));
    (tl.marks || []).forEach(function (m, i) {
      var x = mkx[i], cy = yMarks + 7 + mkRows[i] * 16, col, shape;
      if (m.k === 'grind') { col = 'var(--event-grind)'; shape = s('path', { d: 'M' + x + ' ' + (cy - 6) + ' L' + (x + 6) + ' ' + (cy + 5) + ' L' + (x - 6) + ' ' + (cy + 5) + ' Z', fill: col }); }
      else if (m.k === 'talk') { col = 'var(--event-talk)'; shape = s('rect', { x: x - 5, y: cy - 5, width: 10, height: 10, rx: 3, fill: col }); }
      else { col = 'var(--event-other)'; shape = s('circle', { cx: x, cy: cy, r: 5, fill: col }); }
      var g = s('g', { style: 'cursor:pointer', onclick: function () { if (opts.onMark) opts.onMark(m); } });
      g.appendChild(s('circle', { cx: x, cy: cy, r: 12, fill: 'transparent' }));
      g.appendChild(shape);
      svg.appendChild(g);
    });

    // 整点刻度
    var step = span > 9 * 3600000 ? 2 : 1;
    var first = new Date(t0); first.setMinutes(0, 0, 0);
    var tt = first.getTime(), cnt = 0;
    while (tt <= t1 && cnt++ < 40) {
      if (tt >= t0) {
        var hr = new Date(tt).getHours();
        if (step === 1 || hr % step === 0) {
          svg.appendChild(s('line', { x1: X(tt), x2: X(tt), y1: gridBot, y2: gridBot + 4, stroke: 'var(--outline)' }));
          svg.appendChild(s('text', { x: X(tt), y: yAxis, 'text-anchor': 'middle', fill: 'var(--ink-muted)', 'font-size': 10, 'font-family': mono, text: p2(hr) + ':00' }));
        }
      }
      tt += 3600000;
    }
    return svg;
  }

  // ---------- 弹层 ----------
  function openSheet(build, onClose) {
    var back = h('div', { class: 'scrim' });
    var sheet = h('div', { class: 'sheet fade', role: 'dialog', 'aria-modal': 'true' });
    sheet.appendChild(h('div', { class: 'grab' }));
    function close() { if (back.parentNode) back.parentNode.removeChild(back); if (onClose) onClose(); window.YH_sheetClose = null; }
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    build(sheet, close);
    back.appendChild(sheet);
    document.body.appendChild(back);
    window.YH_sheetClose = close;
    return close;
  }
  function confirmBox(title, msg, yes, onYes, no, danger, onNo) {
    var back = h('div', { class: 'scrim center' });
    function close() { if (back.parentNode) back.parentNode.removeChild(back); window.YH_sheetClose = null; }
    var d = h('div', { class: 'dialog fade', role: 'alertdialog', 'aria-modal': 'true' }, [
      h('h3', { text: title }), h('div', { class: 'body-md muted', text: msg, style: 'white-space:pre-line' }),
      h('div', { class: 'btns' }, [
        no ? h('button', { class: 'btn text', text: no, onclick: close }) : null,
        h('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), text: yes, onclick: function () { close(); if (onYes) onYes(); } })])]);
    back.appendChild(d);
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    document.body.appendChild(back);
    window.YH_sheetClose = close;
  }
  var toastTimer = null;
  function toast(msg) {
    var old = document.querySelector('.toast'); if (old) old.parentNode.removeChild(old);
    var t = h('div', { class: 'toast fade', role: 'status', text: msg });
    document.body.appendChild(t);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2600);
  }

  window.YHUI = { h: h, s: s, icon: icon, clock: clock, hm: hm, dayWeek: dayWeek, p2: p2, scoreRing: scoreRing, timeline: timeline,
    openSheet: openSheet, confirmBox: confirmBox, toast: toast };
})();
