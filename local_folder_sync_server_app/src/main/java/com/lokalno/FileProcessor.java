package com.lokalno;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.lokalno.foldersync.FolderSyncProto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lokalno.foldersync.FolderSyncProto.FileMessage;
import io.grpc.stub.ServerCallStreamObserver;

public class FileProcessor {

    private final static int CHUNK_SIZE = 1024 * 32; //32KB
    private int maxNumberOfReadAttempts = 500;
    private BlockingQueue<ChunkElement> destinationChunkQueue =  new LinkedBlockingQueue<>();
    public record ChunkElement(byte[] chunk, boolean isLastChunk) { }

    public FileProcessor() {}
    public FileProcessor(BlockingQueue<ChunkElement> destinationChunkQueue) {
        this.destinationChunkQueue = destinationChunkQueue;
    }

    public void processFileWithRetries(Path targetPath) throws IOException {
        int numberOfAttempts = this.maxNumberOfReadAttempts;
        while (true) {
            try (InputStream in = Files.newInputStream(targetPath)) {
                System.out.println("input stream processing");
                streamFileChunksToQueue(in, targetPath);
                break; // Success! Exit the retry loop.
            } catch (IOException e) {
                System.out.println("Cant access file: attempt #" + (500 - numberOfAttempts + 1));
                if (--numberOfAttempts <= 0) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    break;
                }
            }
        }
    }

    public void streamFileChunksToQueue(InputStream in, Path targetPath)  throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        int bytesRead;
        System.out.println("streaming file chunks");
        try {
            while ((bytesRead = in.read(buffer)) != -1) {
                byte[] chunkData = Arrays.copyOf(buffer, bytesRead);
                //System.out.println("copying and adding");
                ChunkElement el = new ChunkElement(chunkData, false);
                addElementToQueue(el);
            }
            System.out.println("adding last element");
            ChunkElement finalElement = new ChunkElement(new byte[0], true);
            addElementToQueue(finalElement);

        } catch (IOException e) {
            System.out.println("Cant read file: " + targetPath);
            throw e;
        }
    }

    private void addElementToQueue(ChunkElement el) {
        try {
            destinationChunkQueue.put(el);
        }
        catch (InterruptedException ex){
            Thread.currentThread().interrupt();
        }
    }


}
