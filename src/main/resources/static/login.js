import { auth, api, waitForUser } from "./session.js";
import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  updateProfile,
  sendPasswordResetEmail,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";

const $ = (id) => document.getElementById(id);
let mode = "login"; // "login" or "signup"

// Already logged in? Skip this page.
waitForUser().then((user) => {
  if (user) location.replace("dashboard.html");
});

function setMode(next) {
  mode = next;
  const signup = mode === "signup";
  $("tab-login").classList.toggle("active", !signup);
  $("tab-signup").classList.toggle("active", signup);
  $("name-row").classList.toggle("hidden", !signup);
  $("submit").textContent = signup ? "Create account" : "Log in";
  $("forgot").classList.toggle("hidden", signup);
  $("password").autocomplete = signup ? "new-password" : "current-password";
  showMessage("");
}

function showMessage(text, ok = false) {
  const el = $("msg");
  el.textContent = text;
  el.classList.toggle("ok", ok);
}

function setBusy(busy) {
  $("submit").disabled = busy;
}

function friendly(error) {
  switch (error.code) {
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return "Wrong email or password.";
    case "auth/email-already-in-use":
      return "An account with this email already exists. Try logging in.";
    case "auth/weak-password":
      return "Password must be at least 6 characters.";
    case "auth/invalid-email":
      return "That email address doesn't look right.";
    case "auth/too-many-requests":
      return "Too many attempts. Wait a few minutes and try again.";
    case "auth/network-request-failed":
      return "Network problem. Check your internet connection.";
    default:
      return "Something went wrong (" + (error.code || error.message) + ").";
  }
}

$("tab-login").addEventListener("click", () => setMode("login"));
$("tab-signup").addEventListener("click", () => setMode("signup"));

$("form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = $("email").value.trim();
  const password = $("password").value;
  const name = $("name").value.trim();

  if (!email || !password) {
    showMessage("Enter your email and password.");
    return;
  }
  if (mode === "signup" && name.length < 2) {
    showMessage("Enter your name.");
    return;
  }

  setBusy(true);
  showMessage("");
  try {
    if (mode === "signup") {
      const credential = await createUserWithEmailAndPassword(auth, email, password);
      await updateProfile(credential.user, { displayName: name });
      await credential.user.getIdToken(true); // refresh so the token carries the name
    } else {
      await signInWithEmailAndPassword(auth, email, password);
    }
  } catch (error) {
    setBusy(false);
    showMessage(friendly(error));
    return;
  }

  try {
    await api("/api/me"); // our Java server verifies the token and creates the profile
    location.replace("dashboard.html");
  } catch (error) {
    setBusy(false);
    showMessage("Logged in, but the server said: " + error.message);
  }
});

$("forgot").addEventListener("click", async () => {
  const email = $("email").value.trim();
  if (!email) {
    showMessage("Type your email above first, then click 'Forgot password?'.");
    return;
  }
  try {
    await sendPasswordResetEmail(auth, email);
    showMessage("Password reset email sent. Check your inbox (and spam).", true);
  } catch (error) {
    showMessage(friendly(error));
  }
});
