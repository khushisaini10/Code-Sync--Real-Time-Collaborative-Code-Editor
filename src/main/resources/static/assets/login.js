/* =========================================================================
   LOGIN — identity + room gate. No password: rooms are the keys.
   ========================================================================= */
(() => {
  'use strict';

  const $ = (s) => document.getElementById(s);
  const form = $('loginForm'), nameIn = $('name'), roomIn = $('room');
  const recentWrap = $('recent'), recentList = $('recentList');
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- reveals (same engine as the landing, one page tall) ------- */
  const revEls = document.querySelectorAll('[data-rev]');
  const groupCount = new Map();
  revEls.forEach((el) => {
    const i = groupCount.get(el.parentElement) || 0;
    groupCount.set(el.parentElement, i + 1);
    el.style.setProperty('--d', Math.min(i * 55, 440) + 'ms');
  });
  const revIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('rev-on'); revIO.unobserve(e.target); }
    });
  }, { threshold: 0.12 });
  revEls.forEach((el) => revIO.observe(el));

  const splitEls = document.querySelectorAll('[data-split]');
  const splitIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('split-on'); splitIO.unobserve(e.target); }
    });
  }, { threshold: 0.12 });
  splitEls.forEach((el) => splitIO.observe(el));

  /* ---------- molten panel: two slow drifting gradients ----------------- */
  const pane = document.querySelector('.pane');
  const canvas = $('paneField'), ctx = canvas.getContext('2d');
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const EMBER = '228, 75, 60';
  const BLOBS = [
    { fx: .30, fy: .36, rf: .55, a: .26, sx: .16, sy: .12, px: 0.9, py: 2.2 },
    { fx: .74, fy: .70, rf: .48, a: .18, sx: .12, sy: .18, px: 3.7, py: 0.5 },
    { fx: .55, fy: .15, rf: .34, a: .16, sx: .20, sy: .14, px: 5.2, py: 4.1 }
  ];

  function size() {
    canvas.width = Math.max(1, Math.round(pane.clientWidth * dpr));
    canvas.height = Math.max(1, Math.round(pane.clientHeight * dpr));
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', () => requestAnimationFrame(size));

  function draw(t) {
    const w = pane.clientWidth, h = pane.clientHeight, m = Math.min(w, h);
    ctx.clearRect(0, 0, w, h);
    for (const b of BLOBS) {
      const x = (b.fx + .12 * Math.sin(t * b.sx + b.px)) * w;
      const y = (b.fy + .10 * Math.sin(t * b.sy + b.py)) * h;
      const r = b.rf * m * (1 + .07 * Math.sin(t * .18 + b.px));
      const g = ctx.createRadialGradient(x, y, 0, x, y, Math.max(1, r));
      g.addColorStop(0, `rgba(${EMBER}, ${b.a})`);
      g.addColorStop(.5, `rgba(${EMBER}, ${b.a * .38})`);
      g.addColorStop(1, `rgba(${EMBER}, 0)`);
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, w, h);
    }
    if (!reduced) requestAnimationFrame(draw);
  }

  /* ---------- room codes ------------------------------------------------ */
  const WORDS = ['forge', 'molten', 'anneal', 'gather', 'punty', 'frit', 'kiln', 'glory', 'blowpipe', 'bloom'];
  const sanitize = (raw) => raw.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32);
  const roll = () => WORDS[Math.floor(Math.random() * WORDS.length)] + '-' + (100 + Math.floor(Math.random() * 900));

  $('roll').addEventListener('click', () => {
    roomIn.value = roll();
    roomIn.focus();
  });

  /* ---------- recents ---------------------------------------------------- */
  const readJSON = (key, fallback) => {
    try { return JSON.parse(localStorage.getItem(key)) || fallback; }
    catch (_) { return fallback; }
  };

  function renderRecents() {
    const rooms = readJSON('codesync-rooms', []);
    if (!rooms.length) return;
    recentWrap.hidden = false;
    recentWrap.classList.add('rev-on');
    recentList.replaceChildren(...rooms.slice(0, 3).map((r) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'recent-item';
      b.innerHTML = '<span></span><span class="arrow">Join &#8594;</span>';
      b.firstChild.textContent = r; // textContent: values are user-typed
      b.addEventListener('click', () => { roomIn.value = r; form.requestSubmit(); });
      return b;
    }));
  }

  /* ---------- submit ------------------------------------------------------ */
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const name = nameIn.value.trim().slice(0, 20);
    let room = sanitize(roomIn.value);
    if (!room) room = roll();                 // never bounce the eager
    roomIn.value = room;

    try {
      if (name) localStorage.setItem('codesync-name', name);
      const rooms = readJSON('codesync-rooms', []).filter((r) => r !== room);
      rooms.unshift(room);
      localStorage.setItem('codesync-rooms', JSON.stringify(rooms.slice(0, 6)));
    } catch (_) { /* private mode */ }

    location.href = 'studio.html?room=' + encodeURIComponent(room);
  });

  /* ---------- start ------------------------------------------------------- */
  size();
  draw(reduced ? 12.6 : 0);
  if (!reduced) requestAnimationFrame(draw);

  const params = new URLSearchParams(location.search);
  const pre = sanitize(params.get('room') || '');
  if (pre) roomIn.value = pre; else roomIn.value = '';
  try { nameIn.value = localStorage.getItem('codesync-name') || ''; } catch (_) { /* ignore */ }
  if (!nameIn.value) nameIn.focus();
  renderRecents();
})();
