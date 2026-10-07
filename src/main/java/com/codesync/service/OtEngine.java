package com.codesync.service;

import com.codesync.dto.Op;

/**
 * Tiny operational-transform helper for single-range edits.
 * The browser (static/app.js) contains the same maths, so keep the two in sync.
 */
public final class OtEngine {

    private OtEngine() {}

    /**
     * Rewrites {@code op} so it still means the same thing after {@code against} has been applied.
     *
     * @param againstFirst true when {@code against} came first in server order. If both edits
     *                     insert at the same spot, the earlier edit's text stays on the left.
     */
    public static Op transform(Op op, Op against, boolean againstFirst) {
        int from = map(op.from(), against, againstFirst, true);
        int to = map(op.to(), against, againstFirst, false);
        if (to < from) {
            to = from;
        }
        return new Op(from, to, op.text());
    }

    private static int map(int p, Op ag, boolean againstFirst, boolean isFrom) {
        int af = ag.from();
        int at = ag.to();
        if (p < af) {
            return p;
        }
        if (p > at) {
            return p + ag.text().length() - (at - af);
        }
        // p touches or sits inside the range that 'against' replaced
        if (!isFrom) {
            return af;
        }
        return (p == af && !againstFirst) ? af : af + ag.text().length();
    }
}
