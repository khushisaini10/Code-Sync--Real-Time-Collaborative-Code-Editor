package com.codesync.service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Keeps every room's document in memory. */
@Service
public class DocumentService {

    private static final String STARTER = """
            // Welcome to Code-Sync - open this page in two tabs and type.
            public class Hello {
                public static void main(String[] args) {
                    System.out.println("Hello, Code-Sync!");
                }
            }
            """;

    private final Map<String, SharedDocument> rooms = new ConcurrentHashMap<>();

    public SharedDocument room(String name) {
        return rooms.computeIfAbsent(name, n -> new SharedDocument(STARTER));
    }

    /** room -> {version, length}, for the REST endpoint. */
    public Map<String, Map<String, Integer>> summary() {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        rooms.forEach((name, doc) ->
                out.put(name, Map.of("version", doc.version(), "length", doc.text().length())));
        return out;
    }
}
