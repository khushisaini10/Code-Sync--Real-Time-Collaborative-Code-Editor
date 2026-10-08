package com.codesync.dto;

/** A Code-Sync user as stored in Firestore (collection "users", document id = Firebase uid). */
public record AppUser(String uid, String email, String name, String role) {
}
