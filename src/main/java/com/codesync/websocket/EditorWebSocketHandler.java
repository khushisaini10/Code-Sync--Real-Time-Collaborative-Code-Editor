package com.codesync.websocket;

import com.codesync.dto.Op;
import com.codesync.service.DocumentService;
import com.codesync.service.SharedDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
 *   client -> server: {type:"join", room, name}   {type:"edit", version, from, to, text}
 *   server -> client: {type:"init", clientId, room, text, version, color}
 *                     {type:"op", clientId, version, from, to, text}   (sent to everyone, author included = ack)
 *                     {type:"presence", users:[{id,name,color}]}
 */
@Component
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EditorWebSocketHandler.class);
    private static final String[] COLORS =
            {"#E44B3C", "#E9EDE6", "#A9B5B2", "#DFA083", "#C9A227", "#8FA39D"};

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
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();

    public EditorWebSocketHandler(DocumentService documents) {
        this.documents = documents;
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
        JsonNode msg = mapper.readTree(message.getPayload());
        switch (msg.path("type").asText()) {
            case "join" -> join(c, msg);
            case "edit" -> edit(c, msg);
            default -> log.debug("ignoring message type {}", msg.path("type").asText());
        }
    }

    private void join(Client c, JsonNode msg) {
        String oldRoom = c.room;
        c.room = null; // receive nothing from any room until the snapshot is sent

        String room = sanitizeRoom(msg.path("room").asText("main"));
        String name = msg.path("name").asText("").trim();
        if (name.isEmpty()) {
            name = "Guest-" + (100 + ThreadLocalRandom.current().nextInt(900));
        }
        c.name = name.length() > 20 ? name.substring(0, 20) : name;

        SharedDocument doc = documents.room(room);
        synchronized (doc) {
            // snapshot and joining the room happen under the document lock, so no edit can slip in between
            Map<String, Object> init = Map.of(
                    "type", "init", "clientId", c.id, "room", room,
                    "text", doc.text(), "version", doc.version(), "color", c.color);
            sendRaw(c, toJson(init));
            c.room = room;
        }
        if (oldRoom != null && !oldRoom.equals(room)) {
            broadcastPresence(oldRoom);
        }
        broadcastPresence(room);
    }

    private void edit(Client c, JsonNode msg) {
        String room = c.room;
        if (room == null) {
            return;
        }
        SharedDocument doc = documents.room(room);
        Op incoming = new Op(msg.path("from").asInt(), msg.path("to").asInt(), msg.path("text").asText(""));
        synchronized (doc) { // apply + broadcast together so everyone sees edits in the same order
            Op applied = doc.apply(msg.path("version").asInt(), incoming);
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
        List<Map<String, String>> users = new ArrayList<>();
        for (Client c : clients.values()) {
            if (room.equals(c.room)) {
                users.add(Map.of("id", c.id, "name", c.name, "color", c.color));
            }
        }
        broadcast(room, Map.of("type", "presence", "users", users));
    }

    private void broadcast(String room, Object payload) {
        String json = toJson(payload);
        for (Client c : clients.values()) {
            if (room.equals(c.room)) {
                sendRaw(c, json);
            }
        }
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

    private static String sanitizeRoom(String raw) {
        String room = raw.replaceAll("[^A-Za-z0-9_-]", "");
        if (room.length() > 32) {
            room = room.substring(0, 32);
        }
        return room.isEmpty() ? "main" : room;
    }
}
