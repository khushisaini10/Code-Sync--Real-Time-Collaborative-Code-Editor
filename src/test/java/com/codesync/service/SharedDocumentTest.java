package com.codesync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.codesync.dto.Op;
import org.junit.jupiter.api.Test;

class SharedDocumentTest {

    @Test
    void twoInsertsAtTheSameSpotKeepArrivalOrder() {
        SharedDocument d = new SharedDocument("hello");
        d.apply(0, new Op(5, 5, "X"));
        d.apply(0, new Op(5, 5, "Y")); // written against version 0, arrives second
        assertEquals("helloXY", d.text());
        assertEquals(2, d.version());
    }

    @Test
    void laterEditIsShiftedPastEarlierInsert() {
        SharedDocument d = new SharedDocument("abcdef");
        d.apply(0, new Op(0, 0, "123"));
        d.apply(0, new Op(4, 6, "")); // deletes "ef" as the author saw it
        assertEquals("123abcd", d.text());
    }

    @Test
    void upToDateEditIsAppliedAsIs() {
        SharedDocument d = new SharedDocument("abc");
        d.apply(0, new Op(3, 3, "d"));
        d.apply(1, new Op(0, 1, "Z"));
        assertEquals("Zbcd", d.text());
    }
}
