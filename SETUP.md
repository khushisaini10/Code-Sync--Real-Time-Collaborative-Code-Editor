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

## Deploying later
Instead of a key file, set the environment variable `FIREBASE_CREDENTIALS_JSON` to the full contents of the key.
