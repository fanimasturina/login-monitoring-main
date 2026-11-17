/* Intelligent Login Monitoring - small chart and meter helpers (no library, works with a strict CSP). */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var COLORS = { LOW: '#0ca30c', MEDIUM: '#fab219', HIGH: '#ec835a', CRITICAL: '#d03b3b' };
  var LABELS = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };

  function el(name, attrs, parent) {
    var e = document.createElementNS(NS, name);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function list(s) { return s ? s.split(',') : []; }
  function nums(s) { return list(s).map(Number); }
  function level(score) { return score >= 76 ? 'CRITICAL' : score >= 51 ? 'HIGH' : score >= 26 ? 'MEDIUM' : 'LOW'; }
  function fmt(n) { return Number(n).toLocaleString('en-GB'); }
  function niceMax(v) {
    if (v <= 4) return 4;
    var p = Math.pow(10, Math.floor(Math.log10(v))), m = v / p;
    return (m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
  }
  function tip(host) {
    var t = document.createElement('div');
    t.className = 'tip';
    t.hidden = true;
    host.appendChild(t);
    return {
      show: function (x, y, text) { t.textContent = text; t.style.left = x + 'px'; t.style.top = y + 'px'; t.hidden = false; },
      hide: function () { t.hidden = true; }
    };
  }

  /* Risk meter: <span data-meter="72"></span> */
  function meters() {
    document.querySelectorAll('[data-meter]').forEach(function (m) {
      var raw = m.getAttribute('data-meter');
      if (raw === '' || raw === null) { m.textContent = '-'; return; }
      var s = Math.max(0, Math.min(100, Number(raw)));
      m.classList.add('meter');
      m.textContent = '';
      var track = document.createElement('span');
      track.className = 'track';
      var fill = document.createElement('span');
      fill.className = 'fill';
      fill.style.width = s + '%';
      fill.style.background = COLORS[level(s)];
      track.appendChild(fill);
      [26, 51, 76].forEach(function (b) {
        var t = document.createElement('span');
        t.className = 'tick';
        t.style.left = 'calc(' + b + '% - 1px)';
        track.appendChild(t);
      });
      var num = document.createElement('span');
      num.className = 'score';
      num.textContent = s;
      m.appendChild(num);
      m.appendChild(track);
      m.setAttribute('role', 'img');
      m.setAttribute('aria-label', 'Risk score ' + s + ' of 100, ' + LABELS[level(s)]);
    });
  }

  /* Line or bar chart: <div class="chart" data-chart="line|bars" data-labels="a,b" data-values="1,2" data-name="Logins"> */
  function chart(host) {
    var labels = list(host.getAttribute('data-labels')), values = nums(host.getAttribute('data-values'));
    var name = host.getAttribute('data-name') || '', type = host.getAttribute('data-chart');
    var color = host.getAttribute('data-color') || '#2a78d6';
    if (!values.length) { host.innerHTML = '<div class="empty">No data in this period</div>'; return; }
    var W = 640, H = Number(host.getAttribute('data-height') || 200), L = 40, R = 10, T = 10, B = 24;
    var svg = el('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': name + ' chart, ' + values.length + ' points' }, host);
    var max = niceMax(Math.max.apply(null, values)), iw = W - L - R, ih = H - T - B;
    function y(v) { return T + ih - (v / max) * ih; }
    [0, 0.5, 1].forEach(function (f) {
      var yy = y(max * f);
      el('line', { x1: L, x2: W - R, y1: yy, y2: yy, 'class': f === 0 ? 'base' : 'grid' }, svg);
      el('text', { x: L - 6, y: yy + 4, 'text-anchor': 'end', 'class': 'axis' }, svg).textContent = fmt(max * f);
    });
    var n = values.length, step = iw / n;
    function x(i) { return L + step * i + step / 2; }
    var every = Math.ceil(n / 7);
    labels.forEach(function (lb, i) {
      if (i % every === 0) el('text', { x: x(i), y: H - 6, 'text-anchor': 'middle', 'class': 'axis' }, svg).textContent = lb;
    });
    var marker = null;
    if (type === 'bars') {
      var bw = Math.max(2, Math.min(28, step - 2));
      values.forEach(function (v, i) {
        if (v <= 0) return;
        var h = Math.max(2, (v / max) * ih), r = Math.min(4, bw / 2, h);
        var x0 = x(i) - bw / 2, y0 = T + ih - h;
        el('path', { fill: color, d: 'M' + x0 + ',' + (y0 + h) + 'V' + (y0 + r) + 'Q' + x0 + ',' + y0 + ' ' + (x0 + r) + ',' + y0 + 'H' + (x0 + bw - r)
          + 'Q' + (x0 + bw) + ',' + y0 + ' ' + (x0 + bw) + ',' + (y0 + r) + 'V' + (y0 + h) + 'Z' }, svg);
      });
    } else {
      var d = values.map(function (v, i) { return (i ? 'L' : 'M') + x(i) + ',' + y(v); }).join('');
      el('path', { d: d, fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }, svg);
      marker = el('circle', { r: 4.5, fill: color, stroke: '#fff', 'stroke-width': 2, visibility: 'hidden' }, svg);
    }
    var cross = el('line', { y1: T, y2: T + ih, 'class': 'base', visibility: 'hidden' }, svg);
    var tt = tip(host);
    function move(ev) {
      var box = svg.getBoundingClientRect(), px = (ev.clientX - box.left) / box.width * W;
      var i = Math.max(0, Math.min(n - 1, Math.floor((px - L) / step)));
      cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.setAttribute('visibility', 'visible');
      if (marker) { marker.setAttribute('cx', x(i)); marker.setAttribute('cy', y(values[i])); marker.setAttribute('visibility', 'visible'); }
      tt.show(x(i) / W * box.width, y(values[i]) / H * box.height, labels[i] + ': ' + fmt(values[i]) + ' ' + name.toLowerCase());
    }
    svg.addEventListener('mousemove', move);
    svg.addEventListener('mouseleave', function () { tt.hide(); cross.setAttribute('visibility', 'hidden'); if (marker) marker.setAttribute('visibility', 'hidden'); });
  }

  /* Risk level split: <div data-split data-low="1" data-medium="2" data-high="3" data-critical="4"> */
  function split(host) {
    var keys = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], total = 0, vals = {};
    keys.forEach(function (k) { vals[k] = Number(host.getAttribute('data-' + k.toLowerCase()) || 0); total += vals[k]; });
    if (!total) { host.innerHTML = '<div class="empty">No scored events in this period</div>'; return; }
    var bar = document.createElement('div');
    bar.className = 'split';
    bar.setAttribute('role', 'img');
    bar.setAttribute('aria-label', 'Risk level share');
    var legend = document.createElement('div');
    legend.className = 'legend';
    keys.forEach(function (k) {
      if (vals[k] > 0) {
        var s = document.createElement('span');
        s.style.flex = vals[k] + ' 0 0';
        s.style.background = COLORS[k];
        s.title = LABELS[k] + ': ' + fmt(vals[k]);
        bar.appendChild(s);
      }
      var item = document.createElement('span');
      item.className = 'lvl lvl-' + k;
      item.textContent = LABELS[k] + ' ' + fmt(vals[k]) + ' (' + (vals[k] / total * 100).toFixed(vals[k] / total < 0.1 ? 1 : 0) + '%)';
      legend.appendChild(item);
    });
    host.appendChild(bar);
    host.appendChild(legend);
  }

  document.addEventListener('DOMContentLoaded', function () {
    meters();
    document.querySelectorAll('[data-chart]').forEach(chart);
    document.querySelectorAll('[data-split]').forEach(split);
    document.querySelectorAll('form[data-confirm]').forEach(function (f) {
      f.addEventListener('submit', function (ev) { if (!window.confirm(f.getAttribute('data-confirm'))) ev.preventDefault(); });
    });
    document.querySelectorAll('select[data-autosubmit]').forEach(function (s) {
      s.addEventListener('change', function () { s.form.submit(); });
    });
  });
})();
