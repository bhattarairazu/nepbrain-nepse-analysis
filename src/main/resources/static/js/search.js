// Top-bar stock search: fills the suggestion list once (cached for the browser session) and opens a stock
// as soon as a suggestion is picked.
(function () {
    const form = document.querySelector('.nav-search');
    if (!form) return;
    const input = form.querySelector('input'), list = form.querySelector('datalist');
    let loaded = false;

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

    for (const ev of ['focus', 'pointerdown', 'keydown']) input.addEventListener(ev, load, { once: true });
    input.addEventListener('input', () => {
        // a picked suggestion ("NABIL — Nabil Bank Limited") opens the stock straight away
        if (input.value.includes(' — ') && [...list.options].some(o => o.value === input.value)) form.submit();
    });
    // "/" focuses the search box, unless typing in a field
    document.addEventListener('keydown', e => {
        if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); input.focus(); }
    });
})();
