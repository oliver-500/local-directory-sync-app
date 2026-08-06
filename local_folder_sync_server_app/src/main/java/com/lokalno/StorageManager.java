package com.lokalno;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.logging.Logger;

public class StorageManager {
    //private static final Logger logger = Logger.getLogger(StorageManager.class.getName());

    // Define an isolated directory name for your sync tool
    private static final String APP_FOLDER_NAME = "LocalFolderSyncServer";
    private static final String TOKEN_FILE_NAME = "pairing_code.txt";

    /**
     * Fetches the existing 4-digit pairing code from local storage.
     * If the file or directory does not exist, it generates a fresh code, saves it, and returns it.
     */
    public static String getOrGeneratePairingToken() {
        Path tokenFilePath = getSafeStoragePath();

        if (tokenFilePath == null) {
            System.out.println("⚠️ Could not resolve a safe storage path. Falling back to temporary in-memory token.");
            return generateNumericToken();
        }

        try {
            // 1. If the token file already exists, read it and return it instantly
            if (Files.exists(tokenFilePath)) {
                String storedToken = new String(Files.readAllBytes(tokenFilePath)).trim();
                // Basic validation to ensure data wasn't corrupted
                if (storedToken.length() == 4 && storedToken.matches("\\d+")) {
                    System.out.println("💾 Retrieved existing pairing code from secure storage.");
                    return storedToken;
                }
                System.out.println("⚠️ Stored token file was corrupted or modified. Regenerating...");
            }

            // 2. If it does not exist, create the nested folders safely
            Path parentDir = tokenFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // 3. Generate a fresh token
            String freshToken = generateNumericToken();

            // 4. Write it to the low-privilege file system path
            Files.write(tokenFilePath, freshToken.getBytes());
            System.out.println("✨ Generated and saved a fresh pairing code to local storage.");
            return freshToken;

        } catch (IOException e) {
            System.out.println("❌ Failed to access or write to token storage: " + e.getMessage());
            // Safe fallback so the server doesn't crash completely
            return generateNumericToken();
        }
    }

    /**
     * Resolves an OS-independent path that requires zero elevated admin permissions.
     */
    private static Path getSafeStoragePath() {
        String os = System.getProperty("os.name").toLowerCase();
        String baseDir;

        if (os.contains("win")) {
            // Windows: Points to C:\Users\<Username>\AppData\Local
            baseDir = System.getenv("LOCALAPPDATA");
            if (baseDir == null) {
                // Fallback to user home if environment variable missing
                baseDir = System.getProperty("user.home");
            }
        } else {
            // Linux / macOS fallback: Points to /home/<username>/.config
            baseDir = System.getProperty("user.home") + "/.config";
        }

        if (baseDir == null) return null;

        return Paths.get(baseDir, APP_FOLDER_NAME, TOKEN_FILE_NAME);
    }

    private static String generateNumericToken() {
        SecureRandom random = new SecureRandom();
        int code = 1000 + random.nextInt(9000); // Forces range 1000 - 9999
        return String.valueOf(code);
    }
}
