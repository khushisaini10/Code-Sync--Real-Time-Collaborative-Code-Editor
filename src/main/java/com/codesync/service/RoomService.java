package com.codesync.service;

import com.codesync.dto.AppUser;
import com.codesync.dto.Room;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Rooms live in Firestore. A room's id is its shareable 6-character code. */
@Service
public class RoomService {

    public static final String COLLECTION = "rooms";
    public static final Set<String> LANGUAGES = Set.of("java", "python");

    private static final int MAX_ROOMS_PER_USER = 10;
    private static final int MAX_MEMBERS = 20;

    private final Firestore db;

    public RoomService(Firestore db) {
        this.db = db;
    }

    public Room create(AppUser owner, String rawName, String rawLanguage) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 40) {
            throw bad("Room name must be 1 to 40 characters");
        }
        String language = rawLanguage == null ? "" : rawLanguage.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(language)) {
            throw bad("Language must be java or python");
        }

        int owned = await(db.collection(COLLECTION).whereEqualTo("ownerUid", owner.uid()).get()).size();
        if (owned >= MAX_ROOMS_PER_USER) {
            throw bad("You can own at most " + MAX_ROOMS_PER_USER + " rooms. Delete one first.");
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            String code = RoomCodes.generate();
            DocumentReference ref = db.collection(COLLECTION).document(code);
            if (await(ref.get()).exists()) {
                continue; // that code is taken - draw another
            }
            long now = System.currentTimeMillis();
            Map<String, Object> data = new HashMap<>();
            data.put("name", name);
            data.put("language", language);
            data.put("ownerUid", owner.uid());
            data.put("ownerName", owner.name());
            data.put("createdAt", now);
            data.put("memberUids", new ArrayList<>(List.of(owner.uid())));
            await(ref.set(data));
            return new Room(code, name, language, owner.uid(), owner.name(), now, List.of(owner.uid()));
        }
        throw new IllegalStateException("Could not find a free room code");
    }

    public Optional<Room> find(String code) {
        DocumentSnapshot snap = await(db.collection(COLLECTION).document(code).get());
        return snap.exists() ? Optional.of(toRoom(snap)) : Optional.empty();
    }

    /** Rooms the user owns or has joined, newest first. */
    public List<Room> listFor(String uid) {
        List<QueryDocumentSnapshot> docs =
                await(db.collection(COLLECTION).whereArrayContains("memberUids", uid).get()).getDocuments();
        return docs.stream()
                .map(RoomService::toRoom)
                .sorted(Comparator.comparingLong(Room::createdAt).reversed())
                .toList();
    }

    /** Adds the user to the room (safe to call again), so opening a shared link joins you. */
    public Room join(String rawCode, AppUser user) {
        String code = RoomCodes.normalize(rawCode).orElseThrow(RoomService::notFound);
        Room room = find(code).orElseThrow(RoomService::notFound);
        if (room.hasMember(user.uid())) {
            return room;
        }
        if (room.memberUids().size() >= MAX_MEMBERS) {
            throw bad("This room is full (" + MAX_MEMBERS + " people)");
        }
        await(db.collection(COLLECTION).document(code)
                .update("memberUids", FieldValue.arrayUnion(user.uid())));
        List<String> members = new ArrayList<>(room.memberUids());
        members.add(user.uid());
        return new Room(room.code(), room.name(), room.language(), room.ownerUid(),
                room.ownerName(), room.createdAt(), members);
    }

    /** Only the owner or an admin may delete. Returns the normalised code. */
    public String delete(String rawCode, AppUser user) {
        String code = RoomCodes.normalize(rawCode).orElseThrow(RoomService::notFound);
        Room room = find(code).orElseThrow(RoomService::notFound);
        boolean allowed = room.ownerUid().equals(user.uid()) || UserService.ROLE_ADMIN.equals(user.role());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the room owner can delete it");
        }
        await(db.collection(COLLECTION).document(code).delete());
        return code;
    }

    // ---- the room's saved code text (written by DocumentService) ----

    public Optional<String> loadCode(String code) {
        DocumentSnapshot snap = await(db.collection(COLLECTION).document(code).get());
        return Optional.ofNullable(snap.getString("code"));
    }

    public void saveCode(String code, String text) {
        await(db.collection(COLLECTION).document(code).update("code", text));
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Room toRoom(DocumentSnapshot s) {
        List<String> members = (List<String>) s.get("memberUids");
        Long created = s.getLong("createdAt");
        return new Room(s.getId(), str(s, "name"), str(s, "language"), str(s, "ownerUid"),
                str(s, "ownerName"), created == null ? 0L : created,
                members == null ? List.of() : List.copyOf(members));
    }

    private static String str(DocumentSnapshot s, String field) {
        String value = s.getString(field);
        return value == null ? "" : value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Firestore error: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Firestore", e);
        }
    }
}
