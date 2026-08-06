package com.lokalno.config;

import com.lokalno.util.FileSystemUtil;

import java.io.*;

public class ConfigUtil {

    public static FileSystemUtil.TlsCredentials loadTlsConfig(AppConfig config) {
        try {
            InputStream serverCertStream = getResourceStream(config.serverCertFilePath());
            InputStream serverKeyStream = getResourceStream(config.serverKeyFilePath());
            InputStream clientCaCertStream = getResourceStream(config.clientCaCertFilePath());

            return new FileSystemUtil.TlsCredentials(serverCertStream, serverKeyStream, clientCaCertStream);

        } catch (IOException e) {

            throw new RuntimeException("Critical TLS files are missing or unreadable!", e);
        }

    }

    // Helper method that handles both classpath: resources and regular File paths
    private static InputStream getResourceStream(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }

        // 1. Handle Classpath (Embedded inside JAR)
        if (path.startsWith("classpath:")) {
            String cleanPath = path.substring("classpath:".length()).replaceAll("^/+", "");
            InputStream is = Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream(cleanPath);

            if (is == null) {
                throw new FileNotFoundException("Classpath resource missing: " + cleanPath);
            }
            return is;
        }

        // 2. Handle File System (External file on disk)
        File file = new File(path);
        if (!file.exists()) {
            throw new FileNotFoundException("File missing on disk: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("Permission denied reading file: " + file.getAbsolutePath());
        }
        return new FileInputStream(file);
    }
}
