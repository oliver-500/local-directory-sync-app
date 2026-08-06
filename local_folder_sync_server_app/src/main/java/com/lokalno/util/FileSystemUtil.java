package com.lokalno.util;

import com.lokalno.config.AppConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileSystemUtil {

    public static Path createTargetDirectory(String directoryPath) throws IOException {
        // This will create the folder and any missing parent folders.
        // If the folder already exists, it does nothing.
        Path targetDirectoryPath = Paths.get(directoryPath);
        Files.createDirectories(targetDirectoryPath);
        System.out.println("Sync directory is ready.");

        return targetDirectoryPath;
    }

    public record TlsCredentials(InputStream cert, InputStream key, InputStream ca) {}


}
