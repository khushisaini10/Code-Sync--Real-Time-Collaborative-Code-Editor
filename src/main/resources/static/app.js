(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const ta = $('ta'), hl = $('hl'), gutter = $('gutter');
  const posEl = $('pos'), countEl = $('count'), connEl = $('conn');
  const roomInput = $('room'), nameInput = $('name'), presenceEl = $('presence');
  const splash = $('splash'), app = $('app');

  // =====================================================================
  //  Sync maths: mirrors OtEngine.java on the server. Keep the two in step.
  //  An edit is {from, to, text}: replace [from, to) with text.
  // =====================================================================
  function mapPos(p, ag, againstFirst, isFrom) {
    const af = ag.from, at = ag.to;
    if (p < af) return p;
    if (p > at) return p + ag.text.length - (at - af);
    if (!isFrom) return af;
    return (p === af && !againstFirst) ? af : af + ag.text.length;
  }

  function transform(op, against, againstFirst) {
    const from = mapPos(op.from, against, againstFirst, true);
    let to = mapPos(op.to, against, againstFirst, false);
    if (to < from) to = from;
    return { from, to, text: op.text };
  }

  const applyOp = (s, op) => s.slice(0, op.from) + op.text + s.slice(op.to);

  /** The single edit that turns string a into string b (common prefix/suffix trimmed). */
  function diff(a, b) {
    if (a === b) return null;
    const m = Math.min(a.length, b.length);
    let s = 0;
    while (s < m && a[s] === b[s]) s++;
    let e = 0;
    while (e < m - s && a[a.length - 1 - e] === b[b.length - 1 - e]) e++;
    return { from: s, to: a.length - e, text: b.slice(s, b.length - e) };
  }

  function mapCaret(p, op) {
    if (p <= op.from) return p;
    if (p >= op.to) return p + op.text.length - (op.to - op.from);
    return op.from + op.text.length;
  }

  // =====================================================================
  //  Syntax highlighting (Java)
  // =====================================================================
  const KEYWORDS = 'abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|' +
    'else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|new|package|' +
    'private|protected|public|return|short|static|super|switch|this|throw|throws|try|void|volatile|while|var|' +
    'record|true|false|null';
  const TOKEN = new RegExp(
    /(\/\/.*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*")/.source +
    '|\\b(' + KEYWORDS + ')\\b|\\b(\\d+(?:\\.\\d+)?)\\b', 'g');

  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  function highlight(src) {
    let out = '', last = 0, m;
    TOKEN.lastIndex = 0;
    while ((m = TOKEN.exec(src)) !== null) {
      out += esc(src.slice(last, m.index));
      const cls = m[1] ? 't-c' : m[2] ? 't-s' : m[3] ? 't-k' : 't-n';
      out += '<span class="' + cls + '">' + esc(m[0]) + '</span>';
      last = TOKEN.lastIndex;
    }
    return out + esc(src.slice(last));
  }

  // =====================================================================
  //  Rendering
  // =====================================================================
  let lastLines = 0;

  function syncScroll() {
    hl.scrollTop = ta.scrollTop;
    hl.scrollLeft = ta.scrollLeft;
    gutter.scrollTop = ta.scrollTop;
  }

  function updateStatus() {
    const p = ta.selectionStart;
    const before = ta.value.slice(0, p);
    const line = before.split('\n').length;
    const col = p - before.lastIndexOf('\n');
    posEl.textContent = 'Ln ' + line + ', Col ' + col;
    countEl.textContent = ta.value.length + ' chars  |  ' + ta.value.split('\n').length + ' lines';
  }

  function render() {
    const text = ta.value;
    hl.innerHTML = highlight(text) + '\n'; // trailing newline keeps both layers the same height
    const lines = text.split('\n').length;
    if (lines !== lastLines) {
      gutter.textContent = Array.from({ length: lines }, (_, i) => i + 1).join('\n');
      lastLines = lines;
    }
    updateStatus();
    syncScroll();
  }

  function setConn(state, label) {
    connEl.className = state;
    connEl.textContent = label;
  }

  function renderPresence(users) {
    presenceEl.replaceChildren(...users.map((u) => {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.style.setProperty('--c', /^#[0-9A-Fa-f]{6}$/.test(u.color) ? u.color : '#22D3EE');
      chip.textContent = u.name + (u.id === clientId ? ' (you)' : ''); // textContent: names come from other users
      return chip;
    }));
  }

  // =====================================================================
  //  Live sync over WebSocket
  // =====================================================================
  let ws = null;
  let clientId = null;
  let ready = false;          // true once the server has sent us the document
  let serverText = '';        // the document as the server last confirmed it
  let serverVersion = 0;      // how many edits serverText includes
  let outstanding = false;    // one edit at a time is in flight

  function connect() {
    setConn('connecting', 'connecting');
    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(scheme + location.host + '/ws/editor');
    ws.onopen = join;
    ws.onmessage = (e) => handle(JSON.parse(e.data));
    ws.onclose = () => {
      ready = false;
      outstanding = false;
      ta.readOnly = true;
      setConn('offline', 'offline - retrying');
      setTimeout(connect, 2000);
    };
  }

  function join() {
    ready = false;
    ta.readOnly = true;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'join', room: roomInput.value.trim(), name: nameInput.value.trim() }));
    }
  }

  function handle(msg) {
    switch (msg.type) {
      case 'init':
        clientId = msg.clientId;
        serverText = msg.text;
        serverVersion = msg.version;
        outstanding = false;
        ready = true;
        ta.readOnly = false;
        ta.value = msg.text;
        roomInput.value = msg.room;
        history.replaceState(null, '', '?room=' + encodeURIComponent(msg.room));
        render();
        setConn('live', 'live - room ' + msg.room);
        break;
      case 'op':
        onOp(msg);
        break;
      case 'presence':
        renderPresence(msg.users);
        break;
      default:
        break;
    }
  }

  function onOp(m) {
    const op = { from: m.from, to: m.to, text: m.text };

    if (m.clientId === clientId) {            // the server confirmed my own edit
      serverText = applyOp(serverText, op);
      serverVersion = m.version;
      outstanding = false;
      flush();                                // send anything typed while waiting
      return;
    }

    // someone else's edit: slide it past whatever I have typed but not yet confirmed
    const local = diff(serverText, ta.value);
    const remote = local ? transform(op, local, false) : op;

    const selStart = ta.selectionStart, selEnd = ta.selectionEnd;
    const top = ta.scrollTop, left = ta.scrollLeft;
    ta.value = applyOp(ta.value, remote);
    ta.setSelectionRange(mapCaret(selStart, remote), mapCaret(selEnd, remote));
    ta.scrollTop = top;
    ta.scrollLeft = left;

    serverText = applyOp(serverText, op);
    serverVersion = m.version;
    render();
  }

  /** Send my unconfirmed typing as one edit (only when nothing else is in flight). */
  function flush() {
    if (!ready || outstanding || !ws || ws.readyState !== WebSocket.OPEN) return;
    const d = diff(serverText, ta.value);
    if (!d) return;
    outstanding = true;
    ws.send(JSON.stringify({ type: 'edit', version: serverVersion, from: d.from, to: d.to, text: d.text }));
  }

  // =====================================================================
  //  Editor events
  // =====================================================================
  ta.addEventListener('input', () => { render(); flush(); });
  ta.addEventListener('scroll', syncScroll);
  document.addEventListener('selectionchange', () => { if (document.activeElement === ta) updateStatus(); });

  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') {                    // Tab inserts spaces instead of leaving the editor
      e.preventDefault();
      ta.setRangeText('    ', ta.selectionStart, ta.selectionEnd, 'end');
      ta.dispatchEvent(new Event('input'));
    }
  });

  $('join').addEventListener('click', join);
  roomInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') join(); });
  $('share').addEventListener('click', () => {
    const link = location.origin + '/editor.html?room=' + encodeURIComponent(roomInput.value.trim() || 'main');
    const done = () => { $('share').textContent = 'Copied!'; setTimeout(() => { $('share').textContent = 'Copy link'; }, 1500); };
    if (navigator.clipboard) navigator.clipboard.writeText(link).then(done, () => window.prompt('Copy this link:', link));
    else window.prompt('Copy this link:', link);
  });

  nameInput.addEventListener('change', () => {
    try { localStorage.setItem('codesync-name', nameInput.value.trim()); } catch (_) { /* private mode */ }
  });

  // =====================================================================
  //  Opening screen
  // =====================================================================
  const BRAND = 'Code-Sync';
  const SPLIT = 5;                            // "Code-" white, "Sync" cyan
  const STEPS = ['Warming up the engine...', 'Loading syntax rules...', 'Connecting to sync server...', 'Ready.'];
  const DURATION = 3200;
  const clamp = (v) => Math.max(0, Math.min(1, v));
  const t0 = performance.now();

  function finishSplash() {
    const wait = ready ? 0 : 1200;            // give the socket a moment if it is slow
    setTimeout(() => {
      app.classList.remove('hidden');
      splash.classList.add('out');
      setTimeout(() => splash.remove(), 700);
      ta.focus();
    }, wait);
  }

  function tick(now) {
    const p = clamp((now - t0) / DURATION);
    $('bar-fill').style.width = (p * 100) + '%';
    const chars = Math.ceil(BRAND.length * clamp((p - 0.25) / 0.4));
    $('brand-w').textContent = BRAND.slice(0, Math.min(SPLIT, chars));
    $('brand-y').textContent = BRAND.slice(SPLIT, chars);
    $('status').textContent = STEPS[Math.min(STEPS.length - 1, Math.floor(p * STEPS.length))];
    if (p < 1) requestAnimationFrame(tick); else finishSplash();
  }

  // =====================================================================
  //  Start
  // =====================================================================
  const params = new URLSearchParams(location.search);
  roomInput.value = (params.get('room') || 'main').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'main';
  try { nameInput.value = localStorage.getItem('codesync-name') || ''; } catch (_) { /* ignore */ }
  if (!nameInput.value) nameInput.value = 'Guest-' + (100 + Math.floor(Math.random() * 900));

  render();
  connect();
  requestAnimationFrame(tick);
})();
