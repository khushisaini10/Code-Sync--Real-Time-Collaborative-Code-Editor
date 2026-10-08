package com.codesync.service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps each room's live document in memory (fast, shared by everyone in the room) and saves it to
 * Firestore every few seconds, so a server restart does not lose anyone's code.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Map<String, String> STARTERS = Map.of(
            "java", """
                    public class Main {
                        public static void main(String[] args) {
                            System.out.println("Hello, Code-Sync!");
                        }
                    }
                    """,
            "python", """
                    print("Hello, Code-Sync!")
                    """);

    private final RoomService rooms;
    private final Map<String, SharedDocument> docs = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public DocumentService(RoomService rooms) {
        this.rooms = rooms;
    }

    /** The live document for a room: loaded from Firestore the first time, or a starter program. */
    public SharedDocument room(String code, String language) {
        SharedDocument existing = docs.get(code);
        if (existing != null) {
            return existing;
        }
        String text = rooms.loadCode(code).orElse(STARTERS.getOrDefault(language, ""));
        SharedDocument created = new SharedDocument(text);
        SharedDocument raced = docs.putIfAbsent(code, created);
        return raced != null ? raced : created;
    }

    public void markDirty(String code) {
        dirty.add(code);
    }

    /** Forget a deleted room's document. */
    public void remove(String code) {
        docs.remove(code);
        dirty.remove(code);
    }

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        for (String code : List.copyOf(dirty)) {
            dirty.remove(code);
            SharedDocument doc = docs.get(code);
            if (doc == null) {
                continue; // room was deleted
            }
            try {
                rooms.saveCode(code, doc.text());
            } catch (RuntimeException e) {
                log.warn("Could not save room {}: {}", code, e.toString());
                if (docs.containsKey(code)) {
                    dirty.add(code); // try again next round
                }
            }
        }
    }

    @PreDestroy
    public void saveEverythingOnShutdown() {
        flush();
    }
}
