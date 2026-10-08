import { requireUser, logout } from "./session.js";

const $ = (id) => document.getElementById(id);
$("logout").addEventListener("click", logout);

try {
  const session = await requireUser();
  if (session) {
    const { me } = session;
    $("hello").textContent = "Welcome, " + me.name;
    $("email").textContent = me.email;

    const badge = $("role");
    badge.textContent = me.role;
    badge.classList.add(me.role === "ADMIN" ? "admin" : "user");
    badge.classList.remove("hidden");

    if (me.role === "ADMIN") {
      $("admin-card").classList.remove("hidden");
    }
    $("content").classList.remove("hidden");
  }
} catch (error) {
  const box = $("error");
  box.textContent = "Could not reach the server: " + error.message;
  box.classList.remove("hidden");
}
