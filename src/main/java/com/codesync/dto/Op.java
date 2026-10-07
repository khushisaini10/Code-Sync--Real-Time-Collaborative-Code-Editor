package com.codesync.dto;

/**
 * One text edit: replace the characters in [from, to) with {@code text}.
 * An insert has from == to; a delete has empty text.
 */
public record Op(int from, int to, String text) {

    public Op {
        if (text == null) {
            text = "";
        }
    }
}
