package com.lokalno;
//
//import java.nio.file.*;
//
//public class FolderWatcher implements Runnable {
//
//    private final Path folder;
//    private final FileChangeHandler handler;
//
//    public interface FileChangeHandler {
//        void onFileChanged(Path filePath);
//    }
//
//    public FolderWatcher(Path folder, FileChangeHandler handler) {
//        this.folder = folder;
//        this.handler = handler;
//    }
//
//    @Override
//    public void run() {
//        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
//            folder.register(watchService,
//                    StandardWatchEventKinds.ENTRY_CREATE,
//                    StandardWatchEventKinds.ENTRY_MODIFY,
//                    StandardWatchEventKinds.ENTRY_DELETE);
//
//            while (true) {
//                WatchKey key = watchService.take(); // blocking
//                for (WatchEvent<?> event : key.pollEvents()) {
//                    WatchEvent.Kind<?> kind = event.kind();
//                    Path changed = folder.resolve((Path) event.context());
//                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
//                        handler.onFileChanged(changed);
//                    }
//                }
//                key.reset();
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//}

import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.File;
import java.nio.file.Path;

public class FolderWatcher implements Runnable {

    private final Path folder;
    private final FileChangeHandler handler;
    private final long pollIntervalMs = 2000; // Check every 2 seconds

    public interface FileChangeHandler {
        void onFileChanged(Path filePath);
    }

    public FolderWatcher(Path folder, FileChangeHandler handler) {
        this.folder = folder;
        this.handler = handler;
    }

    @Override
    public void run() {
        FileAlterationObserver observer = getFileAlterationObserver();

        System.out.println("Started watching folder: " + folder);

        try {
            observer.initialize();
            while (!Thread.currentThread().isInterrupted()) {
                // Manually trigger a check against file system snapshot
                observer.checkAndNotify();
                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            System.out.println("Watcher thread interrupted, stopping.");
            Thread.currentThread().interrupt(); // Restore status
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private @NonNull FileAlterationObserver getFileAlterationObserver() {
        File directory = folder.toFile();
        FileAlterationObserver observer = new FileAlterationObserver(directory);

        // Map Apache Commons events to your custom handler
        observer.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onFileChange(File file) {
                handler.onFileChanged(file.toPath());
            }
            @Override
            public void onFileCreate(File file) {
                handler.onFileChanged(file.toPath());
            }
        });
        return observer;
    }
}
