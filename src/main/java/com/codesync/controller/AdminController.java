package com.codesync.controller;

import com.codesync.dto.AppUser;
import com.codesync.security.FirebaseAuthFilter;
import com.codesync.service.UserService;
import com.google.firebase.auth.FirebaseToken;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only endpoints. Every method first checks that the caller's role is ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record RoleRequest(String role) {
    }

    private final UserService users;

    public AdminController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    public List<AppUser> listUsers(@RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        requireAdmin(token);
        return users.listAll();
    }

    @PutMapping("/users/{uid}/role")
    public AppUser changeRole(@PathVariable String uid,
                              @RequestBody RoleRequest body,
                              @RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        AppUser caller = requireAdmin(token);
        if (caller.uid().equals(uid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot change your own role");
        }
        String role = body.role() == null ? "" : body.role().trim().toUpperCase();
        if (!UserService.ROLE_USER.equals(role) && !UserService.ROLE_ADMIN.equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be USER or ADMIN");
        }
        try {
            return users.setRole(uid, role);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
        }
    }

    private AppUser requireAdmin(FirebaseToken token) {
        AppUser caller = users.getOrCreate(token);
        if (!UserService.ROLE_ADMIN.equals(caller.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins only");
        }
        return caller;
    }
}
