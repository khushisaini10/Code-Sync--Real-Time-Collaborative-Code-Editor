package com.codesync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoomCodesTest {

    @Test
    void generatedCodesAreValidAndAvoidLookAlikeCharacters() {
        for (int i = 0; i < 500; i++) {
            String code = RoomCodes.generate();
            assertEquals(RoomCodes.LENGTH, code.length());
            assertTrue(RoomCodes.normalize(code).isPresent());
            assertFalse(code.matches(".*[01OI].*"), code);
        }
    }

    @Test
    void normalizeTrimsAndUppercases() {
        assertEquals(Optional.of("K7M2QX"), RoomCodes.normalize("  k7m2qx "));
    }

    @Test
    void normalizeRejectsBadInput() {
        assertTrue(RoomCodes.normalize(null).isEmpty());
        assertTrue(RoomCodes.normalize("abc").isEmpty());
        assertTrue(RoomCodes.normalize("K7M2Q!").isEmpty());
        assertTrue(RoomCodes.normalize("K7M2QXX").isEmpty());
    }
}
