package com.lokalno.localfoldersyncclient.grpc;

import android.util.Log;

import com.lokalno.foldersync.FolderSyncGrpc;
import com.lokalno.foldersync.FolderSyncProto;

import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import javax.net.ssl.SSLSocketFactory;

public class GrpcSyncController {
    private static final String TAG = "GrpcSyncController";

    private final SSLSocketFactory sslSocketFactory;
    private final String pairingCode;
    private final SyncCallback callback;

    private GrpcClient grpcClient;
    public boolean isRunning = false;
    public boolean isStopped = false;

    public GrpcSyncController(String ip, int port, SSLSocketFactory sslSocketFactory, String pairingCode, SyncCallback callback) {
        this.sslSocketFactory = sslSocketFactory;
        this.pairingCode = pairingCode;
        this.callback = callback;
        Log.d("opaas", "creating new client");
        initClient(ip, port);
    }

    public FolderSyncGrpc.FolderSyncBlockingStub getAuthBlockingStub() {
        return grpcClient.getAuthBlockingStub();
    }

    public void initAuthBlockingStub() {
        grpcClient.initAuthBlockingStub();
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        createMainThread().start();
    }

    public synchronized void stop() {
        isRunning = false;
        if (grpcClient != null) {
            Log.d("sink", "stopping");
            grpcClient.shutdownCurrentGrpcClient();
        }
    }

    public void initClient(String ip, int port) {
        grpcClient = new GrpcClient(sslSocketFactory, pairingCode, ip, port);
    }

    private Thread createMainThread() {
        return new Thread(() -> {
            // Check if stopped before creating a new client
            if (!isRunning) return;
            grpcClient.initMainAsyncStub();
            FolderSyncGrpc.FolderSyncStub mainAsyncStub = grpcClient.getMainStub();

            Log.d("sinc", "jooo");
            mainAsyncStub.sync(new ClientResponseObserver<FolderSyncProto.FileMessage, FolderSyncProto.FileMessage>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<FolderSyncProto.FileMessage> requestStream) {
                    requestStream.setOnReadyHandler(() -> {
                        if (isStopped) {
                            isStopped = false;
                            return;
                        }
                        Log.d(TAG, "Connection established! Stream is open and ready.");
                        callback.onConnectionReady();
                    });
                }

                @Override
                public void onNext(FolderSyncProto.FileMessage msg) {
                    grpcClient.resetRetryDelay();
                    callback.onFileMessageReceived(msg);
                }

                @Override
                public void onError(Throwable t) {
                    if (isStopped) {
                        isStopped = false;
                        return;
                    }
                    t.printStackTrace();
                    Status status = Status.fromThrowable(t);
                    Status.Code code = status.getCode();
                    String description = status.getDescription();

                    Log.e(TAG, "Stream error. Code: " + code + ", Description: " + description);

                    boolean isLocalShutdown = false;
                    if (code == Status.Code.UNAVAILABLE && grpcClient.channel == null) {
                        Log.d(TAG, "🛑 Stream stopped due to local channel shutdown.");
                        isLocalShutdown = true;
                    }

                    callback.onErrorEncountered(code, description, isLocalShutdown);

                    if (isLocalShutdown || !isRunning) {
                        return; // Halt retry loop
                    }

                    // Kill channel explicitly and execute backoff retry
                    grpcClient.shutdownCurrentGrpcClient();

                    new Thread(() -> {
                        grpcClient.sleepBeforeRetry();
                        if (isRunning) {
                            createMainThread().start();
                        }
                    }).start();
                }

                @Override
                public void onCompleted() {
                    callback.onStreamCompleted();
                    if (!isRunning) return;

                    grpcClient.sleepBeforeRetry();
                    grpcClient.restartGrpcClient();
                }
            });
            Log.d("sinc", "jooola");
        });
    }
}
