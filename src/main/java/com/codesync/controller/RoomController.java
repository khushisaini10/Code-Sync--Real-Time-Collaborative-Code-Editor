package com.codesync.controller;

import com.codesync.dto.AppUser;
import com.codesync.dto.Room;
import com.codesync.dto.RoomView;
import com.codesync.security.FirebaseAuthFilter;
import com.codesync.service.DocumentService;
import com.codesync.service.RoomService;
import com.codesync.service.UserService;
import com.google.firebase.auth.FirebaseToken;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Create, list, join and delete rooms. Every call is already login-checked by FirebaseAuthFilter. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    public record CreateRoomRequest(String name, String language) {
    }

    private final UserService users;
    private final RoomService rooms;
    private final DocumentService documents;

    public RoomController(UserService users, RoomService rooms, DocumentService documents) {
        this.users = users;
        this.rooms = rooms;
        this.documents = documents;
    }

    /** My rooms (owned or joined). */
    @GetMapping
    public List<RoomView> mine(@RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser me = users.getOrCreate(token);
        return rooms.listFor(me.uid()).stream().map(room -> RoomView.of(room, me.uid())).toList();
    }

    @PostMapping
    public RoomView create(@RequestBody CreateRoomRequest body,
                           @RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser me = users.getOrCreate(token);
        Room room = rooms.create(me, body.name(), body.language());
        return RoomView.of(room, me.uid());
    }

    /** Opening a shared link calls this first, so anyone with the code and an account is added. */
    @PostMapping("/{code}/join")
    public RoomView join(@PathVariable String code,
                         @RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser me = users.getOrCreate(token);
        return RoomView.of(rooms.join(code, me), me.uid());
    }

    @DeleteMapping("/{code}")
    public Map<String, String> delete(@PathVariable String code,
                                      @RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser me = users.getOrCreate(token);
        String deleted = rooms.delete(code, me);
        documents.remove(deleted);
        return Map.of("deleted", deleted);
    }
}
