// Main menu: groups open on click (and on hover with a mouse); on phones the whole menu sits behind the Menu button.
(function () {
    const bar = document.querySelector('.topbar');
    if (!bar) return;
    const groups = [...bar.querySelectorAll('.nav-group')];
    const menuBtn = bar.querySelector('.menu-btn');

    function closeAll(except) {
        groups.forEach(g => {
            if (g === except) return;
            g.classList.remove('open');
            g.querySelector('.nav-group-btn').setAttribute('aria-expanded', 'false');
        });
    }

    groups.forEach(g => {
        const btn = g.querySelector('.nav-group-btn');
        btn.addEventListener('click', e => {
            e.stopPropagation();
            const open = !g.classList.contains('open');
            closeAll(g);
            g.classList.toggle('open', open);
            btn.setAttribute('aria-expanded', String(open));
        });
    });
    document.addEventListener('click', e => { if (!e.target.closest('.nav-group')) closeAll(); });
    document.addEventListener('keydown', e => {
        if (e.key !== 'Escape') return;
        closeAll();
        if (bar.classList.contains('menu-open')) toggleMenu(false);
    });

    function toggleMenu(open) {
        bar.classList.toggle('menu-open', open);
        menuBtn.setAttribute('aria-expanded', String(open));
        document.body.classList.toggle('no-scroll', open);
    }
    menuBtn.addEventListener('click', () => toggleMenu(!bar.classList.contains('menu-open')));

    // bottom bar "Search": scroll up and focus the search box
    const search = bar.querySelector('.bottom-search'), input = bar.querySelector('.nav-search input');
    if (search && input) search.addEventListener('click', () => { window.scrollTo({ top: 0, behavior: 'smooth' }); input.focus(); });

    // any table without a scrolling container scrolls inside its own box instead of widening the page (phones)
    document.querySelectorAll('main table').forEach(t => {
        for (let p = t.parentElement; p && p.tagName !== 'MAIN'; p = p.parentElement) {
            const o = getComputedStyle(p).overflowX;
            if (o === 'auto' || o === 'scroll' || o === 'hidden') return;
        }
        const wrap = document.createElement('div');
        wrap.className = 'scroll-x';
        t.parentNode.insertBefore(wrap, t);
        wrap.appendChild(t);
    });
})();
