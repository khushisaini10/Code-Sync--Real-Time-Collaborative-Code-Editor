package com.codesync.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the server to your Firebase project using a service-account key.
 *
 * <p>The key is read from, in this order:
 * <ol>
 *   <li>the environment variable FIREBASE_CREDENTIALS_JSON (the whole JSON text - used when deployed),</li>
 *   <li>the file at firebase.credentials-path (default C:/keys/codesync-key.json - used on your PC).</li>
 * </ol>
 * The key is never stored in the project or on GitHub.
 */
@Configuration
public class FirebaseConfig {

    @Value("${firebase.credentials-path:}")
    private String credentialsPath;

    @Value("${FIREBASE_CREDENTIALS_JSON:}")
    private String credentialsJson;

    @PostConstruct
    void init() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(loadCredentials())
                .build();
        FirebaseApp.initializeApp(options);
    }

    private GoogleCredentials loadCredentials() throws IOException {
        if (!credentialsJson.isBlank()) {
            try (InputStream in = new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(in);
            }
        }
        if (credentialsPath.isBlank() || !Files.exists(Path.of(credentialsPath))) {
            throw new IllegalStateException(
                    "Firebase key not found at '" + credentialsPath + "'. "
                    + "Save your service-account key there, or set FIREBASE_KEY_PATH to its location.");
        }
        try (InputStream in = new FileInputStream(credentialsPath)) {
            return GoogleCredentials.fromStream(in);
        }
    }

    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }

    @Bean
    public Firestore firestore() {
        return FirestoreClient.getFirestore();
    }
}
