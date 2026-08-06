package com.lokalno.localfoldersyncclient.grpc;

import com.lokalno.foldersync.FolderSyncProto;

import io.grpc.Status;

public interface SyncCallback {
    void onConnectionReady();
    void onFileMessageReceived(FolderSyncProto.FileMessage msg);
    void onErrorEncountered(Status.Code code, String description, boolean isLocalShutdown);
    void onStreamCompleted();
}
