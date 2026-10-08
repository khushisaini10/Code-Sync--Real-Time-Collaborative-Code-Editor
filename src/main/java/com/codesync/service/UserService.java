package com.codesync.service;

import com.codesync.dto.AppUser;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.FirebaseToken;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Service;

/** Reads and writes user profiles (and their roles) in Firestore. */
@Service
public class UserService {

    public static final String COLLECTION = "users";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final Firestore db;

    public UserService(Firestore db) {
        this.db = db;
    }

    /** Returns the user's profile, creating it (role USER) the first time they log in. */
    public AppUser getOrCreate(FirebaseToken token) {
        try {
            DocumentReference ref = db.collection(COLLECTION).document(token.getUid());
            DocumentSnapshot snap = ref.get().get();
            if (snap.exists()) {
                return toUser(snap);
            }
            String email = token.getEmail() == null ? "" : token.getEmail();
            String name = token.getName() != null && !token.getName().isBlank()
                    ? token.getName()
                    : email.split("@")[0];

            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("name", name);
            data.put("role", ROLE_USER);
            data.put("createdAt", System.currentTimeMillis());
            ref.set(data).get();
            return new AppUser(token.getUid(), email, name, ROLE_USER);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore error: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Firestore", e);
        }
    }

    public List<AppUser> listAll() {
        try {
            List<QueryDocumentSnapshot> docs = db.collection(COLLECTION).get().get().getDocuments();
            return docs.stream()
                    .map(UserService::toUser)
                    .sorted(Comparator.comparing(AppUser::email, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore error: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Firestore", e);
        }
    }

    /** Changes a user's role. Throws NoSuchElementException if the user does not exist. */
    public AppUser setRole(String uid, String role) {
        try {
            DocumentReference ref = db.collection(COLLECTION).document(uid);
            DocumentSnapshot snap = ref.get().get();
            if (!snap.exists()) {
                throw new NoSuchElementException("No such user");
            }
            ref.update("role", role).get();
            return new AppUser(uid, str(snap, "email"), str(snap, "name"), role);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore error: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Firestore", e);
        }
    }

    private static AppUser toUser(DocumentSnapshot snap) {
        String role = snap.getString("role");
        return new AppUser(snap.getId(), str(snap, "email"), str(snap, "name"),
                ROLE_ADMIN.equals(role) ? ROLE_ADMIN : ROLE_USER);
    }

    private static String str(DocumentSnapshot snap, String field) {
        String value = snap.getString(field);
        return value == null ? "" : value;
    }
}
