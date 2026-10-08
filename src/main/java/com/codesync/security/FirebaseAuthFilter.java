package com.codesync.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that guards every /api/** request.
 *
 * <p>The browser logs in with Firebase and sends the resulting ID token as
 * "Authorization: Bearer &lt;token&gt;". This filter asks Firebase to verify it. If it is valid, the
 * decoded token is stored on the request for the controllers; otherwise the answer is 401.
 */
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    /** Request attribute under which the verified token is stored. */
    public static final String TOKEN_ATTR = "firebaseToken";

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "Missing login token");
            return;
        }
        try {
            FirebaseToken token = firebaseAuth.verifyIdToken(header.substring("Bearer ".length()));
            request.setAttribute(TOKEN_ATTR, token);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            reject(response, "Invalid or expired login token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
