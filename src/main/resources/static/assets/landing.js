/* =========================================================================
   LANDING ENGINE — Code-Sync / Cullet
   ONE persistent rAF loop: reads the four pinned stages, writes CSS
   custom properties, redraws only the canvases currently on screen.
   ========================================================================= */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const clamp = (v) => Math.max(0, Math.min(1, v));
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const nav = $('#nav');

  /* ---------------------------------------------------------------------
     Per-atom split: every word becomes an inline-block span on a 36ms
     stagger. `data-split="flat"` builds identical spans with no motion,
     so an absolutely-positioned clone can match the base to the pixel.
     --------------------------------------------------------------------- */
  function split(el) {
    if (!el || el.dataset.splitDone) return;
    let idx = 0;
    const walk = (node) => {
      Array.from(node.childNodes).forEach((child) => {
        if (child.nodeType === Node.TEXT_NODE) {
          const frag = document.createDocumentFragment();
          const parts = child.textContent.split(/\s+/).filter(Boolean);
          parts.forEach((word, i) => {
            if (i > 0) frag.appendChild(document.createTextNode(' '));
            const s = document.createElement('span');
            s.className = 'w';
            s.style.setProperty('--wd', (idx++ * 36) + 'ms');
            s.textContent = word;
            frag.appendChild(s);
          });
          if (child.textContent.trim().length === 0) {
            frag.appendChild(document.createTextNode(' '));
          }
          node.replaceChild(frag, child);
        } else if (child.nodeType === Node.ELEMENT_NODE && child.tagName !== 'BR') {
          walk(child);
        }
      });
    };
    walk(el);
    el.dataset.splitDone = '1';
  }

  const splitEls = Array.from(document.querySelectorAll('[data-split]'));
  splitEls.forEach(split);

  /* the wipe clone: an EXACT copy of the base, built from the same spans */
  const wipeBase = $('#wipeBase');
  const wipeClone = $('#wipeClone');
  if (wipeBase && wipeClone) wipeClone.innerHTML = wipeBase.innerHTML;

  /* ---------------------------------------------------------------------
     Reveal engine: every row / cell / strap carries data-rev and its own
     --d delay (40-100ms times its index inside its parent), revealed by
     ONE IntersectionObserver at threshold .12.
     --------------------------------------------------------------------- */
  const revEls = Array.from(document.querySelectorAll('[data-rev]'));
  const groupCount = new Map();
  revEls.forEach((el) => {
    const parent = el.parentElement;
    const i = groupCount.get(parent) || 0;
    groupCount.set(parent, i + 1);
    el.style.setProperty('--d', Math.min(i * 55, 440) + 'ms');
  });

  const revIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('rev-on'); revIO.unobserve(e.target); }
    });
  }, { threshold: 0.12 });
  revEls.forEach((el) => revIO.observe(el));

  const splitIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('split-on'); splitIO.unobserve(e.target); }
    });
  }, { threshold: 0.12 });
  splitEls.forEach((el) => splitIO.observe(el));

  /* ---------------------------------------------------------------------
     Canvas plumbing
     --------------------------------------------------------------------- */
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const heroPin = $('#stage-hero .pin'), heroCanvas = $('#glowField'), heroCtx = heroCanvas.getContext('2d');
  const gatherPin = $('#stage-gather .pin'), gatherCanvas = $('#gatherField'), gatherCtx = gatherCanvas.getContext('2d');
  const plotBox = $('.plot-box'), curveCanvas = $('#curveCanvas'), curveCtx = curveCanvas.getContext('2d');

  function sizeCanvas(canvas, w, h) {
    canvas.width = Math.max(1, Math.round(w * dpr));
    canvas.height = Math.max(1, Math.round(h * dpr));
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function sizeAll() {
    sizeCanvas(heroCanvas, heroPin.clientWidth, heroPin.clientHeight);
    sizeCanvas(gatherCanvas, gatherPin.clientWidth, gatherPin.clientHeight);
    sizeCanvas(curveCanvas, plotBox.clientWidth, plotBox.clientHeight);
  }
  window.addEventListener('resize', () => { requestAnimationFrame(() => { sizeAll(); fitWordmark(); }); });

  /* ---- stage 1: five drifting radial ember gradients, screen-blended ---- */
  const BLOBS = [
    { fx: .22, fy: .30, rf: .46, a: .28, sx: .19, sy: .13, px: 0.0, py: 1.7 },
    { fx: .78, fy: .22, rf: .40, a: .22, sx: .15, sy: .22, px: 2.1, py: 0.4 },
    { fx: .60, fy: .74, rf: .52, a: .18, sx: .24, sy: .17, px: 4.2, py: 2.8 },
    { fx: .30, fy: .82, rf: .36, a: .16, sx: .13, sy: .20, px: 1.2, py: 3.9 },
    { fx: .90, fy: .60, rf: .30, a: .24, sx: .17, sy: .14, px: 5.1, py: 5.0 }
  ];
  const EMBER = '228, 75, 60';

  function drawHero(t) {
    const w = heroPin.clientWidth, h = heroPin.clientHeight, m = Math.min(w, h);
    heroCtx.clearRect(0, 0, w, h);
    for (const b of BLOBS) {
      const x = (b.fx + .12 * Math.sin(t * b.sx + b.px)) * w;
      const y = (b.fy + .10 * Math.sin(t * b.sy + b.py)) * h;
      const r = b.rf * m * (1 + .07 * Math.sin(t * .18 + b.px));
      const g = heroCtx.createRadialGradient(x, y, 0, x, y, Math.max(1, r));
      g.addColorStop(0, `rgba(${EMBER}, ${b.a})`);
      g.addColorStop(.5, `rgba(${EMBER}, ${b.a * .38})`);
      g.addColorStop(1, `rgba(${EMBER}, 0)`);
      heroCtx.fillStyle = g;
      heroCtx.fillRect(0, 0, w, h);
    }
  }

  /* ---- stage 3: the series, the trace, the readout — one fact ---- */
  const SERIES = [0.2, 1.4, 2.8, 38, 5.5];               // ms, per station
  const STATIONS = ['TYPE', 'DIFF', 'XFORM', 'RELAY', 'SETTLE'];
  const Y_MAX = 40;
  const PAD = { l: 46, r: 20, t: 16, b: 34 };
  const readoutNum = $('#readoutNum');
  let lastReadout = '';

  function interp(p) {
    const f = p * (SERIES.length - 1);
    const i = Math.min(SERIES.length - 2, Math.floor(f));
    const frac = f - i;
    return SERIES[i] + (SERIES[i + 1] - SERIES[i]) * frac;
  }

  function drawCurve(p, t) {
    const w = plotBox.clientWidth, h = plotBox.clientHeight;
    const c = curveCtx;
    if (w < 40 || h < 40) return;
    c.clearRect(0, 0, w, h);
    const x0 = PAD.l, y0 = h - PAD.b, pw = w - PAD.l - PAD.r, ph = h - PAD.t - PAD.b;
    const X = (i) => x0 + (i / (SERIES.length - 1)) * pw;
    const Y = (v) => y0 - (v / Y_MAX) * ph;

    /* grid + y labels */
    c.font = '500 9px Manrope, sans-serif';
    c.textAlign = 'right';
    c.textBaseline = 'middle';
    for (let v = 0; v <= Y_MAX; v += 10) {
      c.strokeStyle = 'rgba(233, 237, 230, .07)';
      c.lineWidth = 1;
      c.beginPath(); c.moveTo(x0, Y(v)); c.lineTo(x0 + pw, Y(v)); c.stroke();
      c.fillStyle = 'rgba(169, 181, 178, .55)';
      c.fillText(String(v), x0 - 10, Y(v));
    }
    /* baseline + station labels */
    c.strokeStyle = 'rgba(233, 237, 230, .16)';
    c.beginPath(); c.moveTo(x0, y0); c.lineTo(x0 + pw, y0); c.stroke();
    c.textAlign = 'center';
    c.textBaseline = 'top';
    STATIONS.forEach((s, i) => {
      c.fillText(s, X(i), y0 + 10);
      c.fillStyle = 'rgba(233, 237, 230, .2)';
      c.fillRect(X(i) - .5, y0, 1, 4);
      c.fillStyle = 'rgba(169, 181, 178, .55)';
    });

    /* full series, bone at 22% */
    c.strokeStyle = 'rgba(233, 237, 230, .22)';
    c.lineWidth = 1.5;
    c.beginPath();
    SERIES.forEach((v, i) => {
      if (i) c.lineTo(X(i), Y(v)); else c.moveTo(X(i), Y(v));
    });
    c.stroke();

    /* ember trace, only as far as the scroll has travelled */
    const xe = x0 + p * pw;
    c.strokeStyle = `rgb(${EMBER})`;
    c.lineWidth = 2.4;
    c.lineJoin = 'round';
    c.beginPath();
    c.moveTo(X(0), Y(SERIES[0]));
    for (let i = 1; i < SERIES.length; i++) {
      if (X(i) <= xe) c.lineTo(X(i), Y(SERIES[i]));
      else {
        const prevX = X(i - 1), prevY = Y(SERIES[i - 1]);
        const f = (xe - prevX) / Math.max(1e-6, X(i) - prevX);
        c.lineTo(xe, prevY + (Y(SERIES[i]) - prevY) * Math.max(0, f));
        break;
      }
    }
    if (p > 0) c.stroke();

    /* pulsing head with a soft halo */
    if (p > 0) {
      const v = interp(p);
      const f = p * (SERIES.length - 1);
      const i = Math.min(SERIES.length - 2, Math.floor(f));
      const hx = xe;
      const hy = Y(SERIES[i]) + (Y(SERIES[i + 1]) - Y(SERIES[i])) * (f - i);
      const halo = c.createRadialGradient(hx, hy, 0, hx, hy, 16 + 3 * Math.sin(t * 2.6));
      halo.addColorStop(0, `rgba(${EMBER}, .34)`);
      halo.addColorStop(1, `rgba(${EMBER}, 0)`);
      c.fillStyle = halo;
      c.beginPath(); c.arc(hx, hy, 18 + 3 * Math.sin(t * 2.6), 0, Math.PI * 2); c.fill();
      c.fillStyle = `rgb(${EMBER})`;
      c.beginPath(); c.arc(hx, hy, 3 + .7 * Math.sin(t * 2.6), 0, Math.PI * 2); c.fill();
    }

    /* the readout: the value AT this exact progress */
    if (readoutNum) {
      const v = interp(p);
      const txt = v >= 10 ? String(Math.round(v)) : v.toFixed(1);
      if (txt !== lastReadout) { lastReadout = txt; readoutNum.textContent = txt; }
    }
  }

  /* ---- stage 4: nested wobbling forms from summed sines ---- */
  function drawGather(t) {
    const w = gatherPin.clientWidth, h = gatherPin.clientHeight;
    const c = gatherCtx;
    c.clearRect(0, 0, w, h);
    const cx = w / 2, cy = h * .46;
    const maxR = Math.min(w, h) * .40;
    const STEPS = 140;
    for (let k = 0; k < 3; k++) {
      const base = maxR * (.46 + .27 * k);
      c.beginPath();
      for (let s = 0; s <= STEPS; s++) {
        const th = (s / STEPS) * Math.PI * 2;
        const r = base * (1
          + .07 * Math.sin(3 * th + t * .9 + k * 1.1)
          + .045 * Math.sin(5 * th - t * 1.35 + k * 2.3));
        const x = cx + r * Math.cos(th), y = cy + r * Math.sin(th);
        if (s) c.lineTo(x, y); else c.moveTo(x, y);
      }
      c.closePath();
      const g = c.createRadialGradient(cx, cy, 0, cx, cy, base * 1.25);
      g.addColorStop(0, `rgba(${EMBER}, .16)`);
      g.addColorStop(.6, `rgba(${EMBER}, .07)`);
      g.addColorStop(1, `rgba(${EMBER}, 0)`);
      c.fillStyle = g;
      c.fill();
      c.strokeStyle = `rgba(${EMBER}, .34)`;
      c.lineWidth = 1;
      c.stroke();
    }
  }

  /* ---------------------------------------------------------------------
     Wordmark: sized from its character count and the face's own width,
     then verified against its own scrollWidth (the pin is overflow:hidden,
     so a document-level overflow test would never see this).
     --------------------------------------------------------------------- */
  function fitWordmark() {
    const wm = document.getElementById('wordmark');
    if (!wm) return;
    wm.style.fontSize = '';
    const lock = wm.parentElement;
    const cs = getComputedStyle(lock);
    const budget = lock.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    if (wm.scrollWidth > wm.clientWidth + 1) {
      const size = parseFloat(getComputedStyle(wm).fontSize);
      wm.style.fontSize = (Math.floor(size * (budget / wm.scrollWidth) * 100) / 100) + 'px';
    }
  }

  /* the wipe clone must match its base to 0px on x, y and width */
  function verifyWipe() {
    if (!wipeBase || !wipeClone) return;
    const a = wipeBase.getBoundingClientRect(), b = wipeClone.getBoundingClientRect();
    if (Math.abs(a.left - b.left) > .5 || Math.abs(a.top - b.top) > .5 || Math.abs(a.width - b.width) > .5) {
      console.warn('[cullet] wipe clone drift', a, b);
    }
  }
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(() => { fitWordmark(); verifyWipe(); sizeAll(); });
  }

  /* ---------------------------------------------------------------------
     THE loop: one rAF drives all four stages, every frame, forever.
     --------------------------------------------------------------------- */
  const stageEls = {
    hero: $('#stage-hero'), wipe: $('#stage-wipe'),
    curve: $('#stage-curve'), gather: $('#stage-gather')
  };
  const rowEls = Array.from(document.querySelectorAll('.prow'));
  const lastP = { hero: -1, wipe: -1, curve: -1, gather: -1 };
  const visible = { hero: true, wipe: false, curve: false, gather: false };

  function applyHero(p) { stageEls.hero.style.setProperty('--p1', p.toFixed(4)); }
  function applyWipe(p) { stageEls.wipe.style.setProperty('--p2', clamp(p * 1.2).toFixed(4)); }
  function applyGather(p) { stageEls.gather.style.setProperty('--p4', p.toFixed(4)); }
  function applyCurve(p) {
    const lit = p <= 0 ? 0 : Math.min(5, Math.ceil(p * 5 - .0001));
    rowEls.forEach((r, i) => r.classList.toggle('lit', i < lit));
  }

  function frame(now) {
    const t = reduced ? 12.6 : now / 1000;
    nav.classList.toggle('scrolled', window.scrollY > 24);

    for (const key in stageEls) {
      const el = stageEls[key];
      const r = el.getBoundingClientRect();
      visible[key] = r.bottom > -80 && r.top < innerHeight + 80;
      const total = el.offsetHeight - innerHeight;
      if (total > 0) {
        const p = clamp(-r.top / total);
        if (Math.abs(p - lastP[key]) > .0004) {
          lastP[key] = p;
          if (key === 'hero') applyHero(p);
          else if (key === 'wipe') applyWipe(p);
          else if (key === 'gather') applyGather(p);
          else applyCurve(p);
        }
      }
    }
    if (visible.hero) drawHero(t);
    if (visible.curve) drawCurve(lastP.curve, t);
    if (visible.gather) drawGather(t);
    requestAnimationFrame(frame);
  }

  sizeAll();
  fitWordmark();
  drawCurve(0, 0);
  requestAnimationFrame(frame);
})();
