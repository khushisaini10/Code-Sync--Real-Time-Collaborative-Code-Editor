import { requireUser, api, logout } from "./session.js";

const $ = (id) => document.getElementById(id);
$("logout").addEventListener("click", logout);

function toast(text, isError = false) {
  const el = $("toast");
  el.textContent = text;
  el.classList.toggle("err", isError);
}

function cell(text) {
  const td = document.createElement("td");
  td.textContent = text; // textContent, never innerHTML: names and emails come from other users
  return td;
}

function renderRow(user, myUid) {
  const tr = document.createElement("tr");
  const isMe = user.uid === myUid;
  tr.append(cell(user.name + (isMe ? " (you)" : "")), cell(user.email));

  const select = document.createElement("select");
  for (const role of ["USER", "ADMIN"]) {
    const option = document.createElement("option");
    option.value = role;
    option.textContent = role;
    option.selected = user.role === role;
    select.append(option);
  }
  select.disabled = isMe; // the server also refuses this, so nobody locks themselves out
  const roleCell = document.createElement("td");
  roleCell.append(select);

  const save = document.createElement("button");
  save.type = "button";
  save.className = "btn";
  save.textContent = "Save";
  save.disabled = isMe;
  save.addEventListener("click", async () => {
    save.disabled = true;
    try {
      const updated = await api("/api/admin/users/" + encodeURIComponent(user.uid) + "/role", {
        method: "PUT",
        body: JSON.stringify({ role: select.value }),
      });
      toast(updated.name + " is now " + updated.role + ".");
    } catch (error) {
      toast(error.message, true);
    } finally {
      save.disabled = false;
    }
  });
  const actionCell = document.createElement("td");
  actionCell.append(save);

  tr.append(roleCell, actionCell);
  return tr;
}

try {
  const session = await requireUser();
  if (session) {
    if (session.me.role !== "ADMIN") {
      location.replace("dashboard.html");
    } else {
      const users = await api("/api/admin/users");
      $("rows").replaceChildren(...users.map((user) => renderRow(user, session.me.uid)));
    }
  }
} catch (error) {
  const box = $("error");
  box.textContent = error.message;
  box.classList.remove("hidden");
}
