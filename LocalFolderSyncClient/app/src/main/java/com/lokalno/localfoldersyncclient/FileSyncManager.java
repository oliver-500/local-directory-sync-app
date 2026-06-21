package com.lokalno.localfoldersyncclient;

import android.content.ContentResolver;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.lokalno.foldersync.FolderSyncProto;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class FileSyncManager {
    private final ContentResolver contentResolver;
    private final DocumentFile targetFolder;
    private final ConcurrentHashMap<String, ParcelFileDescriptor> openDescriptors = new ConcurrentHashMap<>();

    public FileSyncManager(ContentResolver contentResolver, DocumentFile targetFolder) {
        this.contentResolver = contentResolver;
        this.targetFolder = targetFolder;
    }
    public void saveChunkToDocumentFile(FolderSyncProto.FileMessage msg) {
        String fileName = msg.getPath();
        try {
            if (!msg.getIsChunkEnd()) {
                ParcelFileDescriptor pfd = openDescriptors.computeIfAbsent(fileName, name -> {
                    try {
                        DocumentFile targetFile = targetFolder.findFile(name);
                        if (targetFile == null) {
                            targetFile = targetFolder.createFile("application/octet-stream", name);
                        }
                        if (targetFile == null) {
                            throw new IOException("Failed to create target file: " + name);
                        }
                        return contentResolver.openFileDescriptor(targetFile.getUri(), "rw");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                FileDescriptor fd = pfd.getFileDescriptor();
                long exactByteOffset = (long) msg.getPosition() * msg.getChunkSize();

                // Synchronize on the individual PFD instance to handle parallel chunk writes
                synchronized (pfd) {
                    Os.lseek(fd, exactByteOffset, OsConstants.SEEK_SET);
                    byte[] bytes = msg.getContent().toByteArray();
                    int written = 0;
                    while (written < bytes.length) {
                        written += Os.write(fd, bytes, written, bytes.length - written);
                    }
                }
                Log.d("FileSyncManager", "Wrote chunk at position: " + msg.getPosition());
            }

            if (msg.getIsChunkEnd()) {
                closeAndFlushFile(fileName);
            }

        } catch (Exception e) {
            Log.e("FileSyncManager", "Error assembling chunk out of order for: " + fileName, e);
            closeAndFlushFile(fileName); // Clean up immediately on failure
        }
    }

    /**
     * Call this explicitly if a sync job is cancelled or times out
     * to prevent resource leaks.
     */
    public void cancelAllPendingSyncs() {
        for (String fileName : openDescriptors.keySet()) {
            closeAndFlushFile(fileName);
        }
    }

    private void closeAndFlushFile(String fileName) {
        ParcelFileDescriptor pfd = openDescriptors.remove(fileName);
        if (pfd != null) {
            try {
                pfd.getFileDescriptor().sync(); // Force write to physical storage
                pfd.close();
                Log.d("FileSyncManager", "Closed and flushed file: " + fileName);
            } catch (Exception ignored) {}
        }
    }


}
