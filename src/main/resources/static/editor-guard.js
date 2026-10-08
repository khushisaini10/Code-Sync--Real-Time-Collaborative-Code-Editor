// Loaded by editor.html: only logged-in users may see the editor.
import { requireUser } from "./session.js";

requireUser().catch((error) => {
  console.error("Login check failed:", error);
});
