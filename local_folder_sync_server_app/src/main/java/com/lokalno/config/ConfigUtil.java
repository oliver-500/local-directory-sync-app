package com.lokalno.config;

import com.lokalno.util.FileSystemUtil;

import java.io.File;

public class ConfigUtil {

    public static FileSystemUtil.TlsCredentials loadTlsConfig(AppConfig config) {
        File serverCertFile = new File(config.serverCertFilePath());
        File serverKeyFile = new File(config.serverKeyFilePath());
        File clientCaCertFile = new File(config.clientCaCertFilePath());

        // Explicitly check for existence and readability
        if (!serverCertFile.exists() || !serverKeyFile.exists() || !clientCaCertFile.exists()) {
            throw new RuntimeException("Critical TLS files are missing! Check the 'resources/certs/' directory.");
        }

        if (!serverCertFile.canRead()) {
            throw new RuntimeException("Permission denied: Cannot read TLS files.");
        }

        return new FileSystemUtil.TlsCredentials(serverCertFile, serverKeyFile, clientCaCertFile);
    }
}
