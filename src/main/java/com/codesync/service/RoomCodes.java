package com.codesync.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Creates and checks the short room codes people share (for example K7M2QX). */
public final class RoomCodes {

    public static final int LENGTH = 6;

    // No 0/O or 1/I, so a code read out loud or typed from a screenshot is not ambiguous.
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{" + LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomCodes() {
    }

    public static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Trims, upper-cases and validates user input. Empty when it cannot be a room code. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return VALID.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }
}
