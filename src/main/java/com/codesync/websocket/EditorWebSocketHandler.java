package com.codesync.websocket;

import com.codesync.dto.AppUser;
import com.codesync.dto.Op;
import com.codesync.dto.Room;
import com.codesync.service.DocumentService;
import com.codesync.service.RoomCodes;
import com.codesync.service.RoomService;
import com.codesync.service.SharedDocument;
import com.codesync.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Browser protocol (JSON over /ws/editor)
 *   client -> server: {type:"join", room:"K7M2QX", token:"<Firebase ID token>"}
 *                     {type:"edit", version, from, to, text}
 *   server -> client: {type:"init", clientId, room, language, text, version, color}
 *                     {type:"op", clientId, version, from, to, text}   (sent to everyone, author included = ack)
 *                     {type:"presence", users:[{id,name,color}]}
 *                     {type:"error", message}
 *                     {type:"run-start", by}   /   {type:"run", by, status, output, exitCode, millis, truncated}
 *
 * <p>A client may only join a room if its login token is valid AND it is a member of that room.
 * The display name always comes from the user's profile, never from the browser.
 */
@Component
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EditorWebSocketHandler.class);
    private static final String[] COLORS =
            {"#22D3EE", "#8B5CF6", "#F78C6C", "#A5E075", "#FFCB6B", "#FF5370"};
    private static final int MAX_EDIT_CHARS = 200_000;

    private static final class Client {
        final WebSocketSession session;
        final String id;
        final String color;
        volatile String name = "Guest";
        volatile String room; // null until the client has received "init"

        Client(WebSocketSession session, String color) {
            this.session = session;
            this.id = session.getId();
            this.color = color;
        }
    }

    private final DocumentService documents;
    private final FirebaseAuth firebaseAuth;
    private final UserService users;
    private final RoomService rooms;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();

    public EditorWebSocketHandler(DocumentService documents, FirebaseAuth firebaseAuth,
                                  UserService users, RoomService rooms) {
        this.documents = documents;
        this.firebaseAuth = firebaseAuth;
        this.users = users;
        this.rooms = rooms;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String color = COLORS[connections.getAndIncrement() % COLORS.length];
        clients.put(session.getId(), new Client(session, color));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client c = clients.get(session.getId());
        if (c == null) {
            return;
        }
        try {
            JsonNode msg = mapper.readTree(message.getPayload());
            switch (msg.path("type").asText()) {
                case "join" -> join(c, msg);
                case "edit" -> edit(c, msg);
                default -> log.debug("ignoring message type {}", msg.path("type").asText());
            }
        } catch (RuntimeException e) {
            log.warn("Error handling message from {}: {}", c.id, e.toString());
            sendError(c, "Something went wrong on the server. Please reload the page.");
        }
    }

    private void join(Client c, JsonNode msg) {
        String oldRoom = c.room;
        c.room = null; // receive nothing from any room until the snapshot is sent

        FirebaseToken token;
        try {
            token = firebaseAuth.verifyIdToken(msg.path("token").asText(""));
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            sendError(c, "Please log in again.");
            return;
        }
        AppUser user = users.getOrCreate(token);

        String code = RoomCodes.normalize(msg.path("room").asText("")).orElse("");
        Optional<Room> found = code.isEmpty() ? Optional.empty() : rooms.find(code);
        if (found.isEmpty()) {
            sendError(c, "Room not found.");
            return;
        }
        Room room = found.get();
        if (!room.hasMember(user.uid())) {
            sendError(c, "Open the room link first to join this room.");
            return;
        }
        c.name = user.name().isBlank() ? "Guest" : user.name();

        SharedDocument doc = documents.room(code, room.language());
        synchronized (doc) {
            // snapshot and joining the room happen under the document lock, so no edit can slip in between
            Map<String, Object> init = Map.of(
                    "type", "init", "clientId", c.id, "room", code, "language", room.language(),
                    "text", doc.text(), "version", doc.version(), "color", c.color);
            sendRaw(c, toJson(init));
            c.room = code;
        }
        if (oldRoom != null && !oldRoom.equals(code)) {
            broadcastPresence(oldRoom);
        }
        broadcastPresence(code);
    }

    private void edit(Client c, JsonNode msg) {
        String room = c.room;
        if (room == null) {
            return;
        }
        String text = msg.path("text").asText("");
        if (text.length() > MAX_EDIT_CHARS) {
            sendError(c, "That paste is too large.");
            return;
        }
        SharedDocument doc = documents.room(room, "");
        Op incoming = new Op(msg.path("from").asInt(), msg.path("to").asInt(), text);
        synchronized (doc) { // apply + broadcast together so everyone sees edits in the same order
            Op applied = doc.apply(msg.path("version").asInt(), incoming);
            documents.markDirty(room);
            Map<String, Object> out = Map.of(
                    "type", "op", "clientId", c.id, "version", doc.version(),
                    "from", applied.from(), "to", applied.to(), "text", applied.text());
            broadcast(room, out);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client c = clients.remove(session.getId());
        if (c != null && c.room != null) {
            broadcastPresence(c.room);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error on {}: {}", session.getId(), exception.toString());
    }

    // ---------- helpers ----------

    private void broadcastPresence(String room) {
        List<Map<String, String>> present = new ArrayList<>();
        for (Client c : clients.values()) {
            if (room.equals(c.room)) {
                present.add(Map.of("id", c.id, "name", c.name, "color", c.color));
            }
        }
        broadcast(room, Map.of("type", "presence", "users", present));
    }

    /** Lets other parts of the server (e.g. the Run button) send a message to everyone in a room. */
    public void broadcastToRoom(String room, Object payload) {
        broadcast(room, payload);
    }

    private void broadcast(String room, Object payload) {
        String json = toJson(payload);
        for (Client c : clients.values()) {
            if (room.equals(c.room)) {
                sendRaw(c, json);
            }
        }
    }

    private void sendError(Client c, String message) {
        sendRaw(c, toJson(Map.of("type", "error", "message", message)));
    }

    private void sendRaw(Client c, String json) {
        synchronized (c.session) { // a WebSocketSession is not safe for concurrent sends
            try {
                if (c.session.isOpen()) {
                    c.session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.debug("send to {} failed: {}", c.id, e.toString());
            }
        }
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
