package com.lokalno.localfoldersyncclient;

import android.util.Log;

import com.lokalno.foldersync.FolderSyncGrpc;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpChannelBuilder;

public class GrpcClient {
    public ManagedChannel channel;
    private FolderSyncGrpc.FolderSyncStub mainAsyncStub;
    private FolderSyncGrpc.FolderSyncBlockingStub authBlockingStub;
    private SSLSocketFactory socketFactory;
    private int retryDelaySeconds = 2;
    private CallCredentials callCredentials;

    public GrpcClient() {
    }

    public GrpcClient(
            SSLSocketFactory socketFactory,
            String pairingCode
    ) {
        this.socketFactory = socketFactory;

        createChannel();
        createCallCredentials(pairingCode);
    }

    private void createChannel() {
        channel = OkHttpChannelBuilder
                .forAddress("192.168.1.99", 50051)
                .sslSocketFactory(socketFactory)
                .maxInboundMessageSize(64 * 1024 * 1024)
                //.keepAliveTime(30, TimeUnit.SECONDS)
                //.keepAliveTimeout(10, TimeUnit.SECONDS)
                //.keepAliveWithoutCalls(true)
                .disableRetry()
                .build();
    }

    public FolderSyncGrpc.FolderSyncBlockingStub getAuthBlockingStub() {
        return authBlockingStub;
    }
    public void initAuthBlockingStub() {
        authBlockingStub = FolderSyncGrpc.newBlockingStub(channel).withCallCredentials(
                callCredentials
        ).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    public FolderSyncGrpc.FolderSyncStub getMainStub() {
        return mainAsyncStub;
    }

    public void initMainAsyncStub() {
        mainAsyncStub = FolderSyncGrpc.newStub(channel).withCallCredentials(
                callCredentials
        );
    }

    public void createCallCredentials(String pairingCode) {
        callCredentials = new CallCredentials() {
            @Override
            public void applyRequestMetadata(CallCredentials.RequestInfo requestInfo, Executor appExecutor, CallCredentials.MetadataApplier
            applier) {
                appExecutor.execute(() -> {
                    try {
                        Metadata headers = new Metadata();
                        Metadata.Key<String> authKey = Metadata.Key.of("pairing-code", Metadata.ASCII_STRING_MARSHALLER);
                        headers.put(authKey, pairingCode);
                        applier.apply(headers);
                    } catch (Throwable e) {
                        applier.fail(Status.UNAUTHENTICATED.withCause(e));
                    }
                });
            }
        };
    }

    public void restartGrpcClient() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
            channel = null;
        }


    }

    public void sleepBeforeRetry() {
        try {
            Log.d("SyncService", "Sleeping for " + retryDelaySeconds + " seconds before retrying...");
            Thread.sleep(retryDelaySeconds * 1000L);

            // Double the delay for the next failure, capping it at 5 minutes (300 seconds)
            retryDelaySeconds = Math.min(retryDelaySeconds * 2, 300);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Reset this counter back to 2 inside your successful gRPC onNext() stream startup!
    public void resetRetryDelay() {
        this.retryDelaySeconds = 2;
    }

    public void shutdownCurrentGrpcClient() {
        if (channel != null) {
            Log.d("SyncService33", "Shutting down active gRPC channel...");
            try {
                // 1. Stop accepting new RPCs and allow active streams to finish cleanly
                channel.shutdown();

                // 2. Wait a brief moment (e.g., 2 seconds) for active chunks to settle
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.w("SyncService33", "Channel failed to terminate gracefully. Forcing shutdown.");

                    // 3. Force-kill remaining connections if grace period expires
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                // Safeguard if the shutdown thread is interrupted
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                // Nullify reference so it's ready to be rebuilt on resume
                channel = null;
                Log.d("SyncService33", "gRPC channel closed successfully.");
            }
        }
    }


}
