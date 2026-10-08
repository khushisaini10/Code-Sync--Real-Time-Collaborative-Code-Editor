/* =========================================================================
   STUDIO ENGINE — Code-Sync
   Sync maths mirror OtEngine.java on the server. An edit is {from, to,
   text}: replace [from, to) with text. One edit per client in flight,
   acked by the server echoing the op back.
   Adds a solo fallback: if the sync server is unreachable, the buffer
   stays editable locally and the whole page is pushed as ONE op the
   moment the socket returns.
   ========================================================================= */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const ta = $('ta'), hl = $('hl'), gutter = $('gutter');
  const posEl = $('pos'), countEl = $('count'), connEl = $('conn');
  const roomInput = $('room'), nameInput = $('name'), presenceEl = $('presence');

  const STARTER = [
    '// Server unreachable - solo mode. Keep typing; this buffer will be',
    '// pushed as one operation the moment the socket comes back.',
    'public class Hello {',
    '    public static void main(String[] args) {',
    '        System.out.println("Hello, Code-Sync!");',
    '    }',
    '}',
    ''
  ].join('\n');

  /* =====================================================================
     OT maths (identical to the original engine)
     ===================================================================== */
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

  /* =====================================================================
     Syntax highlighting (Java)
     ===================================================================== */
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

  /* =====================================================================
     Rendering
     ===================================================================== */
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
    countEl.textContent = ta.value.length + ' chars | ' + ta.value.split('\n').length + ' lines';
  }

  function render() {
    const text = ta.value;
    hl.innerHTML = highlight(text) + '\n';
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
      chip.style.setProperty('--c', /^#[0-9A-Fa-f]{6}$/.test(u.color) ? u.color : '#E44B3C');
      chip.textContent = u.name + (u.id === clientId ? ' (you)' : ''); // textContent: names come from other users
      return chip;
    }));
  }

  /* =====================================================================
     Live sync over WebSocket — with a solo fallback
     ===================================================================== */
  let ws = null;
  let clientId = null;
  let ready = false;          // true once the document is in the buffer
  let serverText = '';        // the document as the server last confirmed it
  let serverVersion = 0;      // how many edits serverText includes
  let outstanding = false;    // one edit at a time is in flight

  let attempts = 0;           // consecutive failed connections
  let everConnected = false;  // have we EVER received an init?
  let solo = false;           // editing locally, server unreachable
  let soloDirty = false;      // the user typed while solo
  let retryTimer = null;

  function connect() {
    clearTimeout(retryTimer);
    setConn('connecting', 'connecting');
    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    try {
      ws = new WebSocket(scheme + location.host + '/ws/editor');
    } catch (_) {
      onDown();
      return;
    }
    // some servers leave a dead upgrade pending forever — never wait on it
    const openGuard = setTimeout(() => { try { if (ws) ws.close(); } catch (_) { /* noop */ } }, 3000);
    ws.onopen = () => { clearTimeout(openGuard); attempts = 0; join(); };
    ws.onmessage = (e) => handle(JSON.parse(e.data));
    ws.onclose = () => { clearTimeout(openGuard); onDown(); };
    ws.onerror = () => { try { ws.close(); } catch (_) { /* noop */ } };
  }

  function onDown() {
    ready = false;
    outstanding = false;
    attempts++;
    if (attempts >= 2) enterSolo();
    else {
      ta.readOnly = !solo ? true : false;  // stay editable if solo was already on
      setConn('offline', 'offline - retrying');
      retryTimer = setTimeout(connect, 1500);
    }
  }

  /** The server is gone: keep the buffer warm and editable, retry quietly. */
  function enterSolo() {
    solo = true;
    ready = true;
    if (!everConnected && !ta.value) ta.value = STARTER;
    ta.readOnly = false;
    if (!everConnected) { serverText = ta.value; serverVersion = 0; }
    render();
    setConn('solo', 'solo - no server');
    retryTimer = setTimeout(connect, 6000);
  }

  function join() {
    ready = solo;               // stay locally usable until the snapshot lands
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'join', room: roomInput.value.trim(), name: nameInput.value.trim() }));
    }
  }

  function handle(msg) {
    switch (msg.type) {
      case 'init':
        onInit(msg);
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

  function onInit(msg) {
    everConnected = true;
    solo = false;
    clearTimeout(retryTimer);
    clientId = msg.clientId;
    roomInput.value = msg.room;
    history.replaceState(null, '', '?room=' + encodeURIComponent(msg.room));

    const local = ta.value;
    const drifted = soloDirty && local !== msg.text;

    serverText = msg.text;
    serverVersion = msg.version;
    ready = true;
    ta.readOnly = false;

    if (drifted) {
      // push everything typed in solo mode as ONE whole-buffer operation
      ta.value = local;
      outstanding = true;
      ws.send(JSON.stringify({
        type: 'edit', version: serverVersion, from: 0, to: msg.text.length, text: local
      }));
    } else {
      ta.value = msg.text;
      outstanding = false;
    }
    soloDirty = false;
    render();
    setConn('live', 'live - room ' + msg.room);
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

  /* =====================================================================
     Editor events
     ===================================================================== */
  ta.addEventListener('input', () => {
    if (solo) soloDirty = true;
    render();
    flush();
  });
  ta.addEventListener('scroll', syncScroll);
  document.addEventListener('selectionchange', () => { if (document.activeElement === ta) updateStatus(); });

  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') {                    // Tab inserts spaces instead of leaving the editor
      e.preventDefault();
      ta.setRangeText('    ', ta.selectionStart, ta.selectionEnd, 'end');
      ta.dispatchEvent(new Event('input'));
    }
  });

  $('join').addEventListener('click', () => { soloDirty = soloDirty && everConnected; join(); });
  roomInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') $('join').click(); });
  $('share').addEventListener('click', () => {
    const link = location.origin + '/studio.html?room=' + encodeURIComponent(roomInput.value.trim() || 'main');
    const done = () => { $('share').textContent = 'Copied!'; setTimeout(() => { $('share').textContent = 'Copy link'; }, 1500); };
    if (navigator.clipboard) navigator.clipboard.writeText(link).then(done, () => window.prompt('Copy this link:', link));
    else window.prompt('Copy this link:', link);
  });

  nameInput.addEventListener('change', () => {
    try { localStorage.setItem('codesync-name', nameInput.value.trim()); } catch (_) { /* private mode */ }
  });

  /* =====================================================================
     Start
     ===================================================================== */
  const params = new URLSearchParams(location.search);
  roomInput.value = (params.get('room') || 'main').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'main';
  const urlName = (params.get('name') || '').trim().slice(0, 20);
  if (urlName) {
    nameInput.value = urlName;
    try { localStorage.setItem('codesync-name', urlName); } catch (_) { /* private mode */ }
  } else {
    try { nameInput.value = localStorage.getItem('codesync-name') || ''; } catch (_) { /* ignore */ }
  }
  if (!nameInput.value) nameInput.value = 'Guest-' + (100 + Math.floor(Math.random() * 900));

  render();
  connect();
})();
