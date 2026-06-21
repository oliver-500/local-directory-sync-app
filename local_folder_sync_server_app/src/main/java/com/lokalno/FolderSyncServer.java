package com.lokalno;


import com.google.protobuf.ByteString;
import com.lokalno.foldersync.FolderSyncGrpc;
import com.lokalno.foldersync.FolderSyncProto;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import com.lokalno.foldersync.FolderSyncProto.FileMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;


public class FolderSyncServer {

    static class FolderSyncServerImpl extends FolderSyncGrpc.FolderSyncImplBase {

        private final BlockingQueue<Path> filePaths;
        private final List<Thread> messageSenders =  new LinkedList<>();
        private final String serverPairingCode;
        private static final Logger logger = Logger.getLogger(FolderSyncServer.FolderSyncServerImpl.class.getName());

        public FolderSyncServerImpl(BlockingQueue<Path> filePaths, String serverPairingCode) {
            this.filePaths = filePaths;
            this.serverPairingCode = serverPairingCode;
        }
        private final Set<BlockingQueue<FileMessage>> activeClientQueues = ConcurrentHashMap.newKeySet();


        // Call this inside your gRPC sync() method when a client connects
        public void registerClientQueue(BlockingQueue<FileMessage> queue) {
            activeClientQueues.add(queue);
        }

        // Call this inside your gRPC onCompleted/onError to prevent memory leaks
        public void unregisterClientQueue(BlockingQueue<FileMessage> queue) {
            activeClientQueues.remove(queue);
        }


        private FileMessage constructMessage(String fileName, byte[] byteChunk, int position, boolean isEnd) {
            return FileMessage.newBuilder()
                    .setPath(fileName)
                    .setContent(ByteString.copyFrom(byteChunk, 0, byteChunk.length))
                    .setPosition(position)
                    .setIsChunkEnd(isEnd)
                    .setChunkSize(maxChunkSize * numberOfChunksToSend)
                    .build();
        }

        private final ExecutorService broadcastPool = Executors.newCachedThreadPool();
        int numberOfChunksToSend = 100;
        int maxChunkSize = 32 * 1024;

        public void startMainServerWorker() {
            BlockingQueue<FileProcessor.ChunkElement> chunkQueue =
                    new ArrayBlockingQueue<>(50);
            FileProcessor fileProcessor = new FileProcessor(chunkQueue);

            broadcastPool.submit(() -> {
                System.out.println("Message broadcaster running...");
                while (!Thread.currentThread().isInterrupted()) {
                    Path targetPath;

                    try {
                        System.out.println("Waiting for PATH to be processed...");
                        targetPath = filePaths.take(); // Blocks until a file arrives
                        System.out.println("PATH taken: " + targetPath);

                        broadcastPool.submit(() -> {
                            try {
                                fileProcessor.processFileWithRetries(targetPath);
                            }
                            catch (IOException e) {
                                System.out.println("Error while processing file");
                                chunkQueue.clear();
                            }
                        });


                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    }
                    int position = 0;
                    ByteArrayOutputStream multiChunkBuffer = new ByteArrayOutputStream(numberOfChunksToSend * maxChunkSize);
                    int collectedCount = 0;

                    while(true) {
                        FileProcessor.ChunkElement element;
                        try {
                            //System.out.println("Waiting for chunk");
                            element = chunkQueue.take(); // Blocks until a file arrives
                            //System.out.println("chunk taken");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }

                        boolean isEnd = element.chunk().length == 0;


                        if (!isEnd) {
                            try {
                                multiChunkBuffer.write(element.chunk());
                                collectedCount++;
                            } catch (IOException e) {
                                System.out.println("Failed to write chunk to memory buffer.");
                            }
                        }

                        if (collectedCount >= numberOfChunksToSend || isEnd) {
                            if (multiChunkBuffer.size() > 0) {
                                // Extract the precise byte array containing all bundled chunks
                                byte[] mergedPayload = multiChunkBuffer.toByteArray();

                                // --- CALL YOUR GRPC SEND LOGIC HERE ---
                                // sendToGrpc(mergedPayload, element.isLast());
                                System.out.println("Sent batch of " + collectedCount + " chunks. Total size: " + mergedPayload.length + " bytes.");

                                FileMessage fileMessage = constructMessage(
                                        targetPath.getFileName().toString(),
                                        mergedPayload,
                                        position,
                                        false
                                );

                                position++;

                                //at least 1
                                while (activeClientQueues.isEmpty()) {
                                    try{
                                        Thread.sleep(100);
                                    } catch(InterruptedException e){

                                    }
                                }

                                for (BlockingQueue<FileMessage> messageQueue : activeClientQueues) {
                                    broadcastPool.submit(() -> {
                                        try {
                                            //System.out.println(Thread.currentThread().getName() + " :submitting fileMessage");
                                            messageQueue.put(fileMessage);
                                            //System.out.println(":fileMessage submitted");
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                                }

                                // Reset the stream buffer to start fresh for the next 100 chunks
                                multiChunkBuffer.reset();
                                collectedCount = 0;
                            }
                        }

                        if(isEnd) {
                            FileMessage fileMessage = constructMessage(
                                    targetPath.getFileName().toString(),
                                    new byte[0],
                                    position,
                                    true
                            );

                            for (BlockingQueue<FileMessage> messageQueue : activeClientQueues) {
                                broadcastPool.submit(() -> {
                                    try {
                                        //System.out.println(Thread.currentThread().getName() + " :submitting fileMessage");
                                        messageQueue.put(fileMessage);
                                        //System.out.println(":fileMessage submitted");
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                            }
                            break;
                        }


                    }
                }

            });

        }

        public Thread initMessageSender(
                BlockingQueue<FileMessage> messages,
                ServerCallStreamObserver<FileMessage> serverObserver
        ) {
            return new Thread(() -> {
                while(!Thread.currentThread().isInterrupted()) {
                    FileMessage nextChunk;

                    try {
                        System.out.println("waiting for next message");
                        nextChunk = messages.take();
                        System.out.println("Message taken");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    }

                    while (!serverObserver.isReady()) {
                        try {
                            System.out.println("Waiting for server ready 100ms");
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (serverObserver.isReady()) {
                        serverObserver.onNext(nextChunk);
                        System.out.println("Sending chunk to client");
                    }
                }
            });
        }






        @Override
        public void verifyToken(FolderSyncProto.AuthRequest request, StreamObserver<FolderSyncProto.AuthResponse> responseObserver) {
            String clientProvidedToken = request.getToken();
            boolean isMatch = clientProvidedToken != null && clientProvidedToken.equals(serverPairingCode);

            FolderSyncProto.AuthResponse response = FolderSyncProto.AuthResponse.newBuilder()
                    .setSuccess(isMatch)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<FileMessage> sync(StreamObserver<FileMessage> responseObserver) {
            //client specific
            BlockingQueue<FileMessage> messageQueue = new LinkedBlockingQueue<>(50);
            registerClientQueue(messageQueue);

            ServerCallStreamObserver<FileMessage> serverObserver =
                    (ServerCallStreamObserver<FileMessage>) responseObserver;

            Thread messageSender = initMessageSender(messageQueue, serverObserver);
            messageSender.start();

            // Return handler for receiving files from client
            return new StreamObserver<>() {
                @Override
                public void onNext(FolderSyncProto.FileMessage msg) {
                }

                @Override public void onError(Throwable t) {
                    messageSender.interrupt();
                    messageQueue.clear();
                    unregisterClientQueue(messageQueue);

                    responseObserver.onCompleted();
                }
                @Override public void onCompleted() {
                    messageSender.interrupt();
                    messageQueue.clear();
                    unregisterClientQueue(messageQueue);

                    responseObserver.onCompleted();
                }
            };
        }



        private void stopMessageSenders() {
            for (Thread messageSender : messageSenders) {
                messageSender.interrupt();
            }
            messageSenders.clear();
        }

    }
}
