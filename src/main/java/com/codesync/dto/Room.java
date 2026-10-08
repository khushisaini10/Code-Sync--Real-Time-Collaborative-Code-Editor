package com.codesync.dto;

import java.util.List;

/** A room as stored in Firestore (collection "rooms", document id = the 6-character room code). */
public record Room(String code,
                   String name,
                   String language,
                   String ownerUid,
                   String ownerName,
                   long createdAt,
                   List<String> memberUids) {

    public boolean hasMember(String uid) {
        return memberUids.contains(uid);
    }
}
