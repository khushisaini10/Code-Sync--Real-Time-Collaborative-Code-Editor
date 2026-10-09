package com.codesync.controller;

import com.codesync.dto.AppUser;
import com.codesync.dto.Room;
import com.codesync.security.FirebaseAuthFilter;
import com.codesync.service.CodeRunner;
import com.codesync.service.CodeRunner.RunResult;
import com.codesync.service.DocumentService;
import com.codesync.service.RoomCodes;
import com.codesync.service.RoomService;
import com.codesync.service.UserService;
import com.codesync.websocket.EditorWebSocketHandler;
import com.google.firebase.auth.FirebaseToken;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The Run button. Runs the room's CURRENT code (taken from the server, not from the browser). */
@RestController
@RequestMapping("/api/rooms")
public class RunController {

    public record RunRequest(String stdin) {
    }

    private static final long COOLDOWN_MS = 2_000;

    private final UserService users;
    private final RoomService rooms;
    private final DocumentService documents;
    private final CodeRunner runner;
    private final EditorWebSocketHandler sockets;
    private final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    public RunController(UserService users, RoomService rooms, DocumentService documents,
                         CodeRunner runner, EditorWebSocketHandler sockets) {
        this.users = users;
        this.rooms = rooms;
        this.documents = documents;
        this.runner = runner;
        this.sockets = sockets;
    }

    @PostMapping("/{code}/run")
    public RunResult run(@PathVariable String code,
                         @RequestBody(required = false) RunRequest body,
                         @RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser me = users.getOrCreate(token);
        String roomCode = RoomCodes.normalize(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found."));
        Room room = rooms.find(roomCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found."));
        if (!room.hasMember(me.uid())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join this room first.");
        }

        long now = System.currentTimeMillis();
        Long previous = lastRun.put(me.uid(), now);
        if (previous != null && now - previous < COOLDOWN_MS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait a moment before running again.");
        }

        String source = documents.room(roomCode, room.language()).text();
        String stdin = body == null ? "" : body.stdin();
        String by = me.name().isBlank() ? "Someone" : me.name();

        sockets.broadcastToRoom(roomCode, Map.of("type", "run-start", "by", by));
        RunResult result = runner.run(room.language(), source, stdin);
        sockets.broadcastToRoom(roomCode, Map.of(
                "type", "run", "by", by, "status", result.status(), "output", result.output(),
                "exitCode", result.exitCode(), "millis", result.millis(), "truncated", result.truncated()));
        return result;
    }
}
