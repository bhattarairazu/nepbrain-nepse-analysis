/*
 * Quick chart on hover for every stock link (any <a href=".../stock/SYMBOL">).
 * Shows a candlestick chart from the app's own data, a NepseAlpha tab (NepseAlpha may refuse to load inside
 * another site; the "NepseAlpha ↗" button always works and reuses one browser tab), Expand, and a link to details.
 */
(function () {
  'use strict';
  const NEPSE_ALPHA = sym => 'https://nepsealpha.com/nepse-chart?symbol=' + encodeURIComponent(sym);
  const SHOW_DELAY = 350, HIDE_DELAY = 250;
  const cache = new Map();
  let pop = null, modal = null, showTimer = null, hideTimer = null, current = null;

  const symbolOf = a => {
    const m = (a.getAttribute('href') || '').match(/\/stock\/([^/?#]+)/);
    return m ? decodeURIComponent(m[1]).toUpperCase() : null;
  };
  const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const fmt = (v, d = 2) => v == null || isNaN(v) ? '—' : Number(v).toLocaleString('en-IN', { minimumFractionDigits: d, maximumFractionDigits: d });
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function load(sym, days) {
    const key = sym + ':' + days;
    if (!cache.has(key)) {
      cache.set(key, fetch('/api/chart/' + encodeURIComponent(sym) + '?days=' + days).then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }));
    }
    return cache.get(key);
  }

  // ---------------------------------------------------------------- candlestick SVG
  function draw(el, d) {
    const n = d.dates.length;
    if (!n) { el.innerHTML = '<p class="sp-empty">No stored prices for this stock yet.</p>'; return; }
    const W = el.clientWidth || 520, H = el.clientHeight || 260;
    const padL = 6, padR = 52, padT = 22, padB = 18, volH = Math.round(H * 0.18), gap = 6;
    const pH = H - padT - padB - volH - gap;
    const vals = [...d.h, ...d.l, ...d.ema20, ...d.ema50].filter(v => v != null && v > 0);
    let lo = Math.min(...vals), hi = Math.max(...vals);
    const pad = (hi - lo) * 0.05 || hi * 0.02 || 1; lo -= pad; hi += pad;
    const step = (W - padL - padR) / n, bw = Math.max(1, Math.min(10, step * 0.65));
    const X = i => padL + step * (i + 0.5), Y = v => padT + (1 - (v - lo) / (hi - lo)) * pH;
    const up = css('--pos') || '#12864a', down = css('--neg') || '#cf3329', grid = css('--border') || '#ddd', muted = css('--muted') || '#888';
    const vmax = Math.max(...d.v, 1), base = H - padB;
    let s = `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`;
    for (let k = 0; k <= 4; k++) {
      const v = lo + (hi - lo) * k / 4, y = Y(v);
      s += `<line x1="${padL}" x2="${W - padR}" y1="${y}" y2="${y}" stroke="${grid}"/>` +
           `<text x="${W - padR + 4}" y="${y + 4}" font-size="10" fill="${muted}">${fmt(v, v >= 1000 ? 0 : 1)}</text>`;
    }
    [0, Math.floor(n / 2), n - 1].forEach((i, k) => {
      s += `<text x="${X(i)}" y="${H - 4}" font-size="10" fill="${muted}" text-anchor="${k === 0 ? 'start' : k === 2 ? 'end' : 'middle'}">${d.dates[i]}</text>`;
    });
    for (let i = 0; i < n; i++) {
      const o = d.o[i] || d.c[i], c = d.c[i], col = c >= o ? up : down;
      const vh = d.v[i] / vmax * volH;
      s += `<rect x="${X(i) - bw / 2}" y="${base - vh}" width="${bw}" height="${vh}" fill="${col}" opacity="0.35"/>`;
      if (d.h[i] > 0 && d.l[i] > 0) s += `<line x1="${X(i)}" x2="${X(i)}" y1="${Y(d.h[i])}" y2="${Y(d.l[i])}" stroke="${col}"/>`;
      const top = Y(Math.max(o, c)), h = Math.max(1, Math.abs(Y(o) - Y(c)));
      s += `<rect x="${X(i) - bw / 2}" y="${top}" width="${bw}" height="${h}" fill="${col}"/>`;
    }
    const line = (arr, color) => {
      let p = '', pen = false;
      arr.forEach((v, i) => { if (v == null) { pen = false; return; } p += (pen ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1); pen = true; });
      return p ? `<path d="${p}" fill="none" stroke="${color}" stroke-width="1.5"/>` : '';
    };
    s += line(d.ema20, '#9b51e0') + line(d.ema50, '#e0861a');
    s += `<line class="sp-cross" x1="0" x2="0" y1="${padT}" y2="${base}" stroke="${muted}" stroke-dasharray="3 3" visibility="hidden"/>`;
    s += `<text class="sp-info" x="${padL}" y="13" font-size="11" fill="${muted}"></text></svg>`;
    el.innerHTML = s;
    const info = el.querySelector('.sp-info'), cross = el.querySelector('.sp-cross');
    const describe = i => `${d.dates[i]}  O ${fmt(d.o[i])}  H ${fmt(d.h[i])}  L ${fmt(d.l[i])}  C ${fmt(d.c[i])}  Vol ${fmt(d.v[i], 0)}` +
      (d.ema20[i] != null ? `  EMA20 ${fmt(d.ema20[i])}` : '') + (d.ema50[i] != null ? `  EMA50 ${fmt(d.ema50[i])}` : '');
    info.textContent = describe(n - 1);
    el.onmousemove = e => {
      const r = el.getBoundingClientRect(), i = Math.max(0, Math.min(n - 1, Math.floor((e.clientX - r.left - padL) / step)));
      cross.setAttribute('x1', X(i)); cross.setAttribute('x2', X(i)); cross.setAttribute('visibility', 'visible');
      info.textContent = describe(i);
    };
    el.onmouseleave = () => { cross.setAttribute('visibility', 'hidden'); info.textContent = describe(n - 1); };
  }

  // ---------------------------------------------------------------- shared panel content
  function panelHtml(sym) {
    return `
      <div class="sp-head">
        <div><b>${esc(sym)}</b> <span class="sp-name"></span></div>
        <div class="sp-actions">
          <button type="button" class="sp-tab on" data-tab="quick">Quick chart</button>
          <button type="button" class="sp-tab" data-tab="na">NepseAlpha</button>
          <button type="button" class="sp-expand" title="Expand">⤢</button>
          <a class="sp-out" href="${NEPSE_ALPHA(sym)}" target="nepsealpha" rel="noopener">NepseAlpha ↗</a>
          <a class="sp-detail" href="/stock/${encodeURIComponent(sym)}">Details →</a>
        </div>
      </div>
      <div class="sp-body">
        <div class="sp-chart"><p class="sp-empty">Loading…</p></div>
        <div class="sp-na" hidden>
          <iframe title="NepseAlpha chart" loading="lazy" referrerpolicy="no-referrer"></iframe>
          <p class="sp-note">Blank? NepseAlpha doesn't allow its chart inside other sites. Use <b>NepseAlpha ↗</b>: it opens (and reuses) one tab.</p>
        </div>
      </div>
      <div class="sp-legend"><i style="background:#9b51e0"></i>EMA 20 <i style="background:#e0861a"></i>EMA 50 · from this app's stored sessions</div>`;
  }

  function wire(root, sym, days) {
    const chartEl = root.querySelector('.sp-chart');
    load(sym, days).then(d => {
      root.querySelector('.sp-name').textContent = [d.name, d.sector].filter(Boolean).join(' · ');
      draw(chartEl, d);
    }).catch(() => { chartEl.innerHTML = '<p class="sp-empty">Chart data unavailable.</p>'; });
    root.querySelectorAll('.sp-tab').forEach(b => b.addEventListener('click', () => {
      root.querySelectorAll('.sp-tab').forEach(x => x.classList.toggle('on', x === b));
      const na = b.dataset.tab === 'na';
      root.querySelector('.sp-chart').hidden = na;
      root.querySelector('.sp-na').hidden = !na;
      const f = root.querySelector('iframe');
      if (na && !f.src) f.src = NEPSE_ALPHA(sym);
    }));
    root.querySelector('.sp-expand').addEventListener('click', () => openModal(sym));
  }

  // ---------------------------------------------------------------- hover popover
  function showPopover(a, sym) {
    if (!pop) {
      pop = document.createElement('div');
      pop.className = 'sp-pop';
      pop.addEventListener('mouseenter', () => clearTimeout(hideTimer));
      pop.addEventListener('mouseleave', scheduleHide);
      document.body.appendChild(pop);
    }
    current = sym;
    pop.innerHTML = panelHtml(sym);
    pop.style.display = 'block';
    const r = a.getBoundingClientRect(), pw = pop.offsetWidth, ph = pop.offsetHeight;
    let left = r.left, top = r.bottom + 6;
    if (left + pw > window.innerWidth - 8) left = window.innerWidth - pw - 8;
    if (top + ph > window.innerHeight - 8) top = Math.max(8, r.top - ph - 6);
    pop.style.left = Math.max(8, left) + 'px';
    pop.style.top = top + 'px';
    wire(pop, sym, 90);
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (pop) pop.style.display = 'none'; current = null; }, HIDE_DELAY);
  }

  // ---------------------------------------------------------------- expanded view
  function openModal(sym) {
    if (pop) pop.style.display = 'none';
    if (!modal) {
      modal = document.createElement('div');
      modal.className = 'sp-modal';
      modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });
      document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });
      document.body.appendChild(modal);
    }
    modal.innerHTML = `<div class="sp-modal-box">${panelHtml(sym)}<button type="button" class="sp-close" title="Close (Esc)">✕</button></div>`;
    modal.style.display = 'flex';
    modal.querySelector('.sp-expand').remove();
    modal.querySelector('.sp-close').addEventListener('click', closeModal);
    wire(modal, sym, 250);
  }

  function closeModal() {
    if (modal) { modal.style.display = 'none'; modal.innerHTML = ''; }
  }

  // ---------------------------------------------------------------- events (delegated, so every page works)
  document.addEventListener('mouseover', e => {
    const a = e.target.closest && e.target.closest('a[href*="/stock/"]');
    if (!a || (pop && pop.contains(a)) || matchMedia('(hover: none)').matches) return;
    const sym = symbolOf(a);
    if (!sym) return;
    clearTimeout(hideTimer);
    if (current === sym && pop && pop.style.display === 'block') return;
    clearTimeout(showTimer);
    showTimer = setTimeout(() => showPopover(a, sym), SHOW_DELAY);
  });
  document.addEventListener('mouseout', e => {
    const a = e.target.closest && e.target.closest('a[href*="/stock/"]');
    if (!a || (pop && pop.contains(a))) return;
    clearTimeout(showTimer);
    if (pop && pop.style.display === 'block') scheduleHide();
  });
})();
