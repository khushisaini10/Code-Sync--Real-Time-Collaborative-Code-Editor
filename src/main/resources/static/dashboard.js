import { requireUser, logout, api, roomLink } from "./session.js";

const $ = (id) => document.getElementById(id);
$("logout").addEventListener("click", logout);

function say(text, isError = false) {
  const el = $("room-msg");
  el.textContent = text;
  el.classList.toggle("err", isError);
}

function button(label, onClick, extraClass = "") {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "btn " + extraClass;
  b.textContent = label;
  b.addEventListener("click", onClick);
  return b;
}

async function copy(text, label, btn) {
  try {
    await navigator.clipboard.writeText(text);
    const old = btn.textContent;
    btn.textContent = "Copied!";
    setTimeout(() => { btn.textContent = old; }, 1500);
  } catch (_) {
    window.prompt("Copy this " + label + ":", text);
  }
}

async function loadRooms() {
  const rooms = await api("/api/rooms");
  const list = $("room-list");
  if (rooms.length === 0) {
    const empty = document.createElement("p");
    empty.className = "sub";
    empty.textContent = "No rooms yet. Create one above.";
    list.replaceChildren(empty);
    return;
  }
  list.replaceChildren(...rooms.map((room) => {
    const row = document.createElement("div");
    row.className = "room-row";

    const info = document.createElement("div");
    info.className = "roominfo";
    const title = document.createElement("strong");
    title.textContent = room.name; // textContent: room names come from users
    const meta = document.createElement("span");
    meta.textContent = room.language + " · " + room.memberCount + " member" +
      (room.memberCount === 1 ? "" : "s") + " · by " + room.ownerName;
    const code = document.createElement("span");
    code.className = "chip-code";
    code.textContent = room.code;
    info.append(title, meta, code);

    const actions = document.createElement("div");
    actions.className = "room-actions";
    const open = document.createElement("a");
    open.className = "btn primary-btn";
    open.href = "editor.html?room=" + encodeURIComponent(room.code);
    open.textContent = "Open";
    const copyBtn = button("Copy link", () => copy(roomLink(room.code), "link", copyBtn));
    actions.append(open, copyBtn);
    if (room.owner || me.role === "ADMIN") {
      actions.append(button("Delete", async () => {
        if (!window.confirm('Delete room "' + room.name + '" for everyone?')) return;
        try {
          await api("/api/rooms/" + encodeURIComponent(room.code), { method: "DELETE" });
          await loadRooms();
        } catch (error) {
          say(error.message, true);
        }
      }));
    }
    row.append(info, actions);
    return row;
  }));
}

let me;
try {
  const session = await requireUser();
  if (session) {
    me = session.me;
    $("hello").textContent = "Welcome, " + me.name;
    $("email").textContent = me.email;

    const badge = $("role");
    badge.textContent = me.role;
    badge.classList.add(me.role === "ADMIN" ? "admin" : "user");
    badge.classList.remove("hidden");
    if (me.role === "ADMIN") $("admin-card").classList.remove("hidden");

    $("create-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      say("");
      try {
        const room = await api("/api/rooms", {
          method: "POST",
          body: JSON.stringify({ name: $("room-name").value.trim(), language: $("room-lang").value }),
        });
        location.href = "editor.html?room=" + encodeURIComponent(room.code);
      } catch (error) {
        say(error.message, true);
      }
    });

    $("join-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      say("");
      try {
        const room = await api("/api/rooms/" + encodeURIComponent($("join-code").value.trim()) + "/join", { method: "POST" });
        location.href = "editor.html?room=" + encodeURIComponent(room.code);
      } catch (error) {
        say(error.message, true);
      }
    });

    await loadRooms();
    $("content").classList.remove("hidden");
  }
} catch (error) {
  const box = $("error");
  box.textContent = "Could not reach the server: " + error.message;
  box.classList.remove("hidden");
}
