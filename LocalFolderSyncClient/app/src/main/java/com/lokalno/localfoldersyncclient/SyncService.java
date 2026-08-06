package com.lokalno.localfoldersyncclient;

import static android.widget.Toast.LENGTH_SHORT;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.lokalno.foldersync.FolderSyncProto;

import java.io.IOException;
import java.io.InputStream;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import com.lokalno.foldersync.FolderSyncProto.FileMessage;
import com.lokalno.localfoldersyncclient.grpc.GrpcSyncController;
import com.lokalno.localfoldersyncclient.grpc.SyncCallback;
import com.lokalno.localfoldersyncclient.util.Util;

import javax.net.ssl.SSLSocketFactory;

public class SyncService extends Service {
    private DocumentFile targetFolder;
    private String pairingCode;
    private StreamObserver<FileMessage> requestObserver; //za slanje klijent-server

    private SSLSocketFactory sslSocketFactory;
    private GrpcSyncController syncController;
    private FileSyncManager fileSyncManager;
    private final String channelId = "sync_channel_active_v1";

    public enum ServiceState {
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        STOPPED
    }

    public static ServiceState serviceState = ServiceState.STOPPED;
    public static final String ACTION_SERVICE_STATE = "com.lokalno.localfoldersyncclient.SERVICE_STATE";

    private void sendStateBroadcast() {
        Intent intent = new Intent(ACTION_SERVICE_STATE);
        intent.putExtra("service_state", serviceState.name());
        // Restricts the broadcast strictly inside your app's sandbox
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private final BroadcastReceiver appStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MainActivity.ACTION_APP_STATE.equals(intent.getAction())) {
                String name = intent.getStringExtra("app_state");
                MainActivity.AppState appState = MainActivity.AppState.valueOf(name);

                if (appState == MainActivity.AppState.DISCONNECTING) {
                    Intent closeIntent = new Intent("ACTION_CLOSE");
                    // Call the receiver directly on the main thread
                    notificationActionReceiver.onReceive(SyncService.this, closeIntent);
                }
            }
        }
    };

    private Notification createNotification(String title, String text, boolean showResumeButton) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "File Sync Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pendingIntent);

        if (showResumeButton) {
            Intent resumeIntent = new Intent("ACTION_RESUME");
            PendingIntent resumePendingIntent = PendingIntent.getBroadcast(
                    this, 1, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            resumeIntent.setPackage(getPackageName());
            // Adds a clickable text button to the notification layout
            //builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent);
        } else {
            Intent pauseIntent = new Intent("ACTION_PAUSE");
            PendingIntent pausePendingIntent = PendingIntent.getBroadcast(
                    this, 2, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            pauseIntent.setPackage(getPackageName());
            //builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent);
        }
        Intent closeIntent = new Intent("ACTION_CLOSE");
        closeIntent.setPackage(getPackageName());
        PendingIntent closePendingIntent = PendingIntent.getBroadcast(
                this, 3, closeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", closePendingIntent);

        return builder.build();
    }
    private void showShuttingDownNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Folder Sync")
                    .setContentText("Stopping...")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true); // Keep it locked until stopSelf() naturally removes it

            manager.notify(1, builder.build());
        }
    }

    public final BroadcastReceiver notificationActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("ACTION_CLOSE".equals(action)) {
                showShuttingDownNotification();

                serviceState = ServiceState.DISCONNECTING;
                sendStateBroadcast();

                new Thread(() -> {
                    if(syncController !=  null) syncController.stop();
                    serviceState = ServiceState.STOPPED;
                    sendStateBroadcast();

                    // 3. Demote the service out of foreground status and strip the notification
                    stopForeground(STOP_FOREGROUND_REMOVE);

                    // 4. Tell the Android OS to completely kill this service instance
                    stopSelf();

                }).start();
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();

        // Create filters matching the exact string actions from your PendingIntents
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_PAUSE");
        filter.addAction("ACTION_RESUME");
        filter.addAction("ACTION_CLOSE");
        IntentFilter filter2 = new IntentFilter(MainActivity.ACTION_APP_STATE);

        // REGISTER: Listen to notification button clicks
        // Note: RECEIVER_NOT_EXPORTED ensures other malicious apps can't fake these intents
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(appStateReceiver, filter2, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationActionReceiver, filter);
            registerReceiver(appStateReceiver, filter2);
        }
        InputStream caInput = getResources().openRawResource(R.raw.ca);
        InputStream clientInput = null;
        String clientCertPassword = null;

        try {
            sslSocketFactory = Util.getSslSocketFactory(
                    caInput,
                    clientInput,
                    clientCertPassword
            );
        } catch (Exception e) {
            Toast.makeText(this, "Error in app configuration. Contact an administrator.", LENGTH_SHORT);
            return;
        }

        try {
            caInput.close();
        }
        catch (IOException e) {
            Log.e("SyncService", "Failed to close ca certificate file open stream");
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        serviceState = ServiceState.STOPPED;
        sendStateBroadcast();
        // UNREGISTER: Prevent memory leaks when the service is stopped!
        try {
            unregisterReceiver(notificationActionReceiver);
        } catch (IllegalArgumentException e) {
            // Safe check in case it was already unregistered
            e.printStackTrace();
        }
        try {
            unregisterReceiver(appStateReceiver);
        } catch (IllegalArgumentException e) {
            // Safe check in case it was already unregistered
            e.printStackTrace();
        }
        if(syncController != null) {
            syncController.stop();
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w("SyncService33", "Service resurrected by OS without an Intent. Shutting down...");

            // We must display a fallback notification to satisfy the OS contract before closing
            Notification fallbackNotification = createNotification(
                    "Sync Halted",
                    "App was restarted. Please open the app to reconnect.",
                    false
            );
            startForeground(1, fallbackNotification);

            // Shut down this specific instance of the service completely
            stopSelf(startId);

            // Tell the OS: "Don't bother recreating this again; we are done."
            return START_NOT_STICKY;
        }
        serviceState = ServiceState.CONNECTING;
        sendStateBroadcast();

        Notification notification = createNotification("Syncing Folder","Connected to PC",false);
        try {
            String uriString = intent.getStringExtra("target_folder_uri");
            pairingCode = intent.getStringExtra("pairing_code");
            Log.d("sinc", "passed " + uriString + " " + pairingCode);

            if(uriString == null || pairingCode == null) {
                Log.d("sinc", "op1");
                Toast.makeText(this, "Internal App Error2. Contact an administrator.", LENGTH_SHORT).show();
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            // Re-create the Uri from the string
            Uri folderUri = Uri.parse(uriString);

            Log.d("sinc", folderUri + "");
            // Now, re-create the DocumentFile object using the Uri
            targetFolder = DocumentFile.fromTreeUri(this, folderUri);

            if (targetFolder == null || !targetFolder.exists()) {
                Toast.makeText(this, "Internal App Error1. Contact an administrator.", LENGTH_SHORT).show();
                Log.d("sinc", "op2" + targetFolder.exists() + targetFolder);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            Log.d("Local_folder_sync_client_v1", "Saving to:" + targetFolder.getName());

            fileSyncManager = new FileSyncManager(getContentResolver(), targetFolder);

            syncController = new GrpcSyncController("90", 0, sslSocketFactory, pairingCode, new SyncCallback() {
                @Override
                public void onConnectionReady() {
                    // Update Foreground Notification here
                    serviceState = ServiceState.CONNECTED;
                    sendStateBroadcast();
                    updateNotificationText("Connected & Syncing", "Monitoring your destination folder...", false);
                }

                @Override
                public void onFileMessageReceived(FolderSyncProto.FileMessage msg) {
                    // Save the chunk to storage
                    fileSyncManager.saveChunkToDocumentFile(msg);
                }

                @Override
                public void onErrorEncountered(Status.Code code, String description, boolean isLocalShutdown) {
                    if (!isLocalShutdown) {
                        updateNotificationText("Connection Lost", "Attempting to reconnect...", false);
                    }
                }

                @Override
                public void onStreamCompleted() {
                    Log.d("Service", "Stream complete");
                }
            });
            syncController.start();

        } catch (Exception e) {
            e.printStackTrace();
        }

        startForeground(1, notification);
        return START_REDELIVER_INTENT;
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    private void updateNotificationText(String title, String text, boolean showResume) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, createNotification(title, text, showResume));
        }
    }

}
