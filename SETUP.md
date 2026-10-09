# Code-Sync - local setup

## 1. Get your own Firebase key (once per person)
1. Firebase console -> project `code-sync` -> gear icon -> **Project settings -> Service accounts**
2. Click **Generate new private key**.
3. Save the file as `C:\keys\codesync-key.json` (create the `C:\keys` folder).
4. **Never** commit, paste or share this file. It is a password. It is blocked by `.gitignore`.

To keep the key somewhere else, set the environment variable `FIREBASE_KEY_PATH` to its full path.

## 2. Run
- VS Code / Eclipse: run `CodeSyncApplication`
- or: `mvn spring-boot:run`

Open http://localhost:8081

## 3. First admin
1. Sign up in the app (everyone starts as `USER`).
2. Firebase console -> **Firestore Database -> Data -> users -> your document**
3. Change `role` from `USER` to `ADMIN`.
4. Refresh the app. The dashboard now shows the Admin panel, where you can promote other people.

## 4. Firestore rules
The browser never talks to Firestore directly (only our Java server does, using the key), so lock it down:
Firestore -> **Rules** ->
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```
Click **Publish**. The server is not affected, because the Admin SDK bypasses these rules.

## Rooms (Phase 2)
1. Run the app, log in, and on the dashboard create a room. You land in the editor with a 6-letter code (e.g. `K7M2QX`).
2. **Copy link** and send it to a teammate. Opening `/editor.html?room=K7M2QX` sends them to login first (if needed), adds them to the room, and syncs the code live.
3. Room code is saved to Firestore every ~10 seconds and when the server stops.

### Who can open your link?
| Where you run it | Link looks like | Who can open it |
|---|---|---|
| Your PC | `http://localhost:8081/...` | Only you |
| Same Wi-Fi | `http://<your-PC-IP>:8081/...` | People on that network (allow Java through Windows Firewall) |
| Temporary tunnel (ngrok / Cloudflare) | `https://something.trycloudflare.com/...` | Anyone, while your PC and tunnel are running. Add the tunnel domain to Firebase > Authentication > Settings > Authorized domains |
| Real deployment (Phase 5) | `https://your-app.example.com/...` | Anyone, always on |

Links use whatever address the page is opened from, so nothing needs changing in code.

## Running code (Phase 4)
Press **Run** (or Ctrl+Enter) in a room. The server takes the room's current code, compiles it (Java) and runs it, then shows the result to everyone in the room.
- Java: the public class's `main` runs. Without a public class, the class declared before `main` runs.
- Needs a **JDK 21** (not just a JRE) on the machine running the server. Python needs `python` (Windows) or `python3` on PATH, or set `CODESYNC_PYTHON`.
- The Input box feeds `Scanner` / `input()`.
- Limits: 15 s to compile, 5 s to run, 64 KB output, 2 programs at once, one run per user every 2 s.
- **Safety:** these are process limits, not a full sandbox. While you run it on your own PC, only invite people you trust. Before opening it to the public, run it inside a locked-down container (Phase 5: no network, memory limit, non-root user).

## Deploying later
Set these environment variables on the host:
- `FIREBASE_CREDENTIALS_JSON` = the full contents of the service-account key (never commit it)
- `PORT` = provided by most hosts automatically (the app reads it)
Then add your site's domain in Firebase > Authentication > Settings > Authorized domains.
