package com.codesync.dto;

/** What the browser is allowed to see about a room (no member ids). */
public record RoomView(String code,
                       String name,
                       String language,
                       String ownerName,
                       long createdAt,
                       int memberCount,
                       boolean owner) {

    public static RoomView of(Room room, String viewerUid) {
        return new RoomView(room.code(), room.name(), room.language(), room.ownerName(),
                room.createdAt(), room.memberUids().size(), room.ownerUid().equals(viewerUid));
    }
}
