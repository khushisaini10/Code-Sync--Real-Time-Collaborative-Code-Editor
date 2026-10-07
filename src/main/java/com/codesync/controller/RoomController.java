package com.codesync.controller;

import com.codesync.service.DocumentService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RoomController {

    private final DocumentService documents;

    public RoomController(DocumentService documents) {
        this.documents = documents;
    }

    /** Quick check that the server is up: GET /api/rooms */
    @GetMapping("/rooms")
    public Map<String, Map<String, Integer>> rooms() {
        return documents.summary();
    }
}
