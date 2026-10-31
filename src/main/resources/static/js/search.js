// Top-bar stock search, on every page.
//  - As you type, rows of this page's tables for matching stocks are highlighted; "N on this page" jumps between them and
//    "Only these" hides the other rows.
//  - Picking a suggestion jumps to that stock on this page if it is here, otherwise opens its page. Enter always opens it.
//  - The suggestion list loads once and is cached for the browser session. "/" focuses the box, Escape clears it.
(function () {
    const form = document.querySelector('.nav-search');
    if (!form) return;
    const input = form.querySelector('input'), list = form.querySelector('datalist');
    let loaded = false, hits = [], at = -1, only = false;

    // ---- suggestions
    function fill(symbols) {
        list.replaceChildren(...symbols.map(s => { const o = document.createElement('option'); o.value = s; return o; }));
        loaded = true;
    }

    function load() {
        if (loaded) return;
        try {
            const cached = sessionStorage.getItem('nepse-symbols');
            if (cached) { fill(JSON.parse(cached)); return; }
        } catch (e) { /* storage unavailable */ }
        fetch(form.dataset.symbols).then(r => r.ok ? r.json() : []).then(symbols => {
            fill(symbols);
            try { sessionStorage.setItem('nepse-symbols', JSON.stringify(symbols)); } catch (e) { /* ignore */ }
        }).catch(() => {});
    }

    // ---- find on this page
    const bar = document.createElement('span');
    bar.className = 'find-hits';
    bar.hidden = true;
    bar.innerHTML = '<button type="button" data-next></button><button type="button" data-only>Only these</button>';
    form.appendChild(bar);
    const nextBtn = bar.querySelector('[data-next]'), onlyBtn = bar.querySelector('[data-only]');

    /** Every table row on the page that belongs to one stock, with that stock's symbol. */
    function stockRows() {
        const out = [];
        document.querySelectorAll('main tbody tr').forEach(tr => {
            const a = tr.querySelector('a[href*="/stock/"]');
            if (a) out.push([tr, a.textContent.trim().toUpperCase()]);
        });
        return out;
    }

    function query() {
        const v = input.value.toUpperCase();
        const i = v.indexOf(' — ');
        return i > 0 ? { q: v.slice(0, i).trim(), exact: true } : { q: v.trim(), exact: false };
    }

    function clear() {
        document.querySelectorAll('tr.find-hit').forEach(tr => tr.classList.remove('find-hit', 'find-current'));
        document.querySelectorAll('tr.find-hidden').forEach(tr => tr.classList.remove('find-hidden'));
        hits = []; at = -1;
        bar.hidden = true;
    }

    function find() {
        clear();
        const { q, exact } = query();
        if (q.length < 2) return;
        const rows = stockRows();
        hits = rows.filter(([, sym]) => exact ? sym === q : sym.startsWith(q)).map(([tr]) => tr);
        hits.forEach(tr => tr.classList.add('find-hit'));
        if (only) rows.forEach(([tr]) => { if (!tr.classList.contains('find-hit')) tr.classList.add('find-hidden'); });
        bar.hidden = rows.length === 0;
        nextBtn.textContent = hits.length ? hits.length + ' on this page ↓' : 'Not on this page: Enter opens it';
        nextBtn.disabled = hits.length === 0;
        onlyBtn.hidden = hits.length === 0;
        onlyBtn.classList.toggle('on', only);
    }

    function next() {
        if (!hits.length) return;
        if (at >= 0) hits[at].classList.remove('find-current');
        at = (at + 1) % hits.length;
        const tr = hits[at];
        for (let d = tr.closest('details'); d; d = d.parentElement.closest('details')) d.open = true;
        tr.classList.add('find-current');
        tr.scrollIntoView({ block: 'center', behavior: 'smooth' });
        nextBtn.textContent = (at + 1) + ' of ' + hits.length + ' on this page ↓';
    }

    let timer;
    input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
            find();
            const { exact } = query();
            // a picked suggestion: jump to it here, or open its page if it is not on this one
            if (exact && [...list.options].some(o => o.value === input.value)) {
                if (hits.length) next(); else form.submit();
            }
        }, 120);
    });
    nextBtn.addEventListener('click', next);
    onlyBtn.addEventListener('click', () => { only = !only; find(); });
    form.addEventListener('submit', e => {
        if (!query().q) e.preventDefault();
    });
    for (const ev of ['focus', 'pointerdown', 'keydown']) input.addEventListener(ev, load, { once: true });
    input.addEventListener('keydown', e => { if (e.key === 'Escape') { input.value = ''; only = false; clear(); input.blur(); } });
    document.addEventListener('keydown', e => {
        if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); input.focus(); }
    });
})();
