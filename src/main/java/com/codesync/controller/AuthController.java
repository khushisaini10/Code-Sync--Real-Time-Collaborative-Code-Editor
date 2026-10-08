package com.codesync.controller;

import com.codesync.dto.AppUser;
import com.codesync.security.FirebaseAuthFilter;
import com.codesync.service.UserService;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    /** Who am I? Creates the profile on first login. The browser calls this right after logging in. */
    @GetMapping("/me")
    public AppUser me(@RequestAttribute(FirebaseAuthFilter.TOKEN_ATTR) FirebaseToken token) {
        return users.getOrCreate(token);
    }
}
