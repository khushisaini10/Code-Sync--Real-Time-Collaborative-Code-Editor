// Shared login helpers used by every page.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import { getAuth, onAuthStateChanged, signOut } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import { firebaseConfig } from "./firebase-config.js";

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);

/** Resolves with the signed-in user, or null. Waits for Firebase to restore a saved login. */
export function waitForUser() {
  return new Promise((resolve) => {
    const stop = onAuthStateChanged(auth, (user) => {
      stop();
      resolve(user);
    });
  });
}

/** Calls our Java server with the login token attached. */
export async function api(path, options = {}) {
  const user = auth.currentUser;
  if (!user) {
    throw new Error("Not signed in");
  }
  const token = await user.getIdToken();
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
      Authorization: "Bearer " + token,
    },
  });
  if (!response.ok) {
    let message = response.statusText || "Request failed";
    try {
      const body = await response.json();
      message = body.message || body.error || message;
    } catch (_) {
      /* response had no JSON body */
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

/**
 * Use at the top of any protected page.
 * Not logged in -> sends the visitor to the login page and returns null.
 * Logged in     -> returns { user, me } where me = { uid, email, name, role }.
 */
export async function requireUser(loginPage = "index.html") {
  const user = await waitForUser();
  if (!user) {
    location.replace(loginPage);
    return null;
  }
  try {
    const me = await api("/api/me");
    return { user, me };
  } catch (error) {
    if (error.status === 401) {
      await signOut(auth);
      location.replace(loginPage);
      return null;
    }
    throw error;
  }
}

export async function logout() {
  await signOut(auth);
  location.replace("index.html");
}
