package com.codesync.service;

import com.codesync.dto.Op;
import java.util.ArrayList;
import java.util.List;

/** One shared document (a "room"): the current text plus the ordered list of edits applied to it. */
public class SharedDocument {

    private final StringBuilder text;
    private final List<Op> history = new ArrayList<>();

    public SharedDocument(String initial) {
        this.text = new StringBuilder(initial);
    }

    public synchronized String text() {
        return text.toString();
    }

    /** Version = number of edits applied so far. */
    public synchronized int version() {
        return history.size();
    }

    /**
     * Applies an edit that its author made against document version {@code baseVersion}.
     * Edits that landed in between are transformed over first. Returns the edit as actually applied.
     */
    public synchronized Op apply(int baseVersion, Op incoming) {
        int base = Math.max(0, Math.min(baseVersion, history.size()));
        Op op = incoming;
        for (int i = base; i < history.size(); i++) {
            op = OtEngine.transform(op, history.get(i), true);
        }
        int from = Math.max(0, Math.min(op.from(), text.length()));
        int to = Math.max(from, Math.min(op.to(), text.length()));
        op = new Op(from, to, op.text());
        text.replace(from, to, op.text());
        history.add(op);
        return op;
    }
}
