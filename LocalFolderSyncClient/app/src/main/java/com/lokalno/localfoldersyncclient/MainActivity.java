package com.lokalno.localfoldersyncclient;

import static android.widget.Toast.LENGTH_SHORT;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;

import com.lokalno.foldersync.FolderSyncGrpc;
import com.lokalno.foldersync.FolderSyncProto;
import com.lokalno.localfoldersyncclient.databinding.ActivityMainBinding;
import com.lokalno.localfoldersyncclient.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocketFactory;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private String pairingCode;
    private Uri targetFolderUri;
    private boolean isWorkingInBackgroundAllowed;

    private GrpcSyncController activitySyncController;
    private SSLSocketFactory sslSocketFactory;
    private FileSyncManager fileSyncManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        loadData();
        if (!initSslSocketFactory()) {
            finish();
            return;
        }

        if (isReadyForAutoBackgroundSync()) {
            appState.set(AppState.CONNECTING);
            updateButtonStates();
            checkNetworkAndRun();
        }

        if (pairingCode != null) {
            binding.etPairingCode.setText(pairingCode);
        }
        binding.switchNotification.setChecked(isWorkingInBackgroundAllowed);
        if(targetFolderUri != null) {
            binding.tvSelectedPath.setText(getHumanReadablePath(targetFolderUri));
            binding.btnChooseFolder.setText("Change directory");
        }

        updateButtonStates();

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        Toast.makeText(this, "Destination folder not chosen.", LENGTH_SHORT).show();
                        Log.d("MyService444", "Folder not chosen.");
                        return;
                    }
                    Intent intent = result.getData();

                    if (intent == null) {
                        Toast.makeText(this, "Internal app error. Contact an administrator.", LENGTH_SHORT).show();
                        return;
                    }
                    Uri treeUri = intent.getData();

                    if (treeUri == null) {
                        Toast.makeText(this, "Internal app error. Contact an administrator.", LENGTH_SHORT).show();
                        return;
                    }

                    getContentResolver().takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );

                    saveTargetFolderUri(treeUri);
                    binding.tvSelectedPath.setText(getHumanReadablePath(treeUri));
                    targetFolderUri = treeUri;

                    updateButtonStates();
                }
        );

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        Log.d("MyService444", "Notification permission granted by user.");
                        binding.layoutAttentionWarning.setVisibility(View.GONE); // Hide warning since they granted it
                        isWorkingInBackgroundAllowed = true;
                        binding.switchNotification.setChecked(true);
                    } else {
                        Log.w("MyService444", "Notification permission denied. Foreground service notification won't show.");
                        Toast.makeText(this, "You have not granted notification permission.", LENGTH_SHORT).show();
                        isWorkingInBackgroundAllowed = false;
                        binding.switchNotification.setChecked(false);
                    }
                    saveWorkingInBackgroundFlag();
                }
        );

        // 1. MANAGE SWITCH VISIBILITY STATES
        binding.switchNotification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Check if we already have system notification approval
                if (checkNotificationPermission()) {
                    isWorkingInBackgroundAllowed = true;
                }
                else {

                    isWorkingInBackgroundAllowed = false;
                    binding.layoutAttentionWarning.setVisibility(View.VISIBLE);
                }
            } else {
                // Hide warning completely if switch turned off


                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().remove("was_processing").apply();
                binding.layoutAttentionWarning.setVisibility(View.GONE);
                isWorkingInBackgroundAllowed = false;
            }
            saveWorkingInBackgroundFlag();
        });

        // 2. TRIGGER SYSTEM GRANT ACTION PROMPT
        binding.btnGrantPermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                // Below Android 13, permissions are granted at download time automatically
                binding.layoutAttentionWarning.setVisibility(View.GONE);
            }
        });

        binding.etPairingCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Run verification rules as the user types
                updateButtonStates();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        binding.btnChooseFolder.setOnClickListener(v -> {
            // Trigger your storage access framework intent here...
            openDirectoryChooser();
        });

        binding.btnStartSync.setOnClickListener(v -> {
            if (appState.compareAndSet(AppState.READY, AppState.CONNECTING)) {
                pairingCode = binding.etPairingCode.getText().toString();
                updateButtonStates();
                if(!isWorkingInBackgroundAllowed) {
                    saveWasProcessingFlag(true);
                }

                checkNetworkAndRun();
            }
            else if (appState.compareAndSet(AppState.CONNECTED, AppState.DISCONNECTING)) {
                updateButtonStates();

                new Thread(() -> {
                    if(activitySyncController != null)  {
                        activitySyncController.stop();
                        activitySyncController = null;
                    }
                    sendStateBroadcast();
                }).start();

                Log.d("sink", "1");
                //sendStateBroadcast();
                if(!isWorkingInBackgroundAllowed) {
                    saveWasProcessingFlag(false);
                }
            }
            else if(appState.compareAndSet(AppState.RECONNECTING, AppState.NOT_READY)) {
                updateButtonStates();
            }

        });

        IntentFilter filter = new IntentFilter(SyncService.ACTION_SERVICE_STATE);

        // Note: RECEIVER_NOT_EXPORTED ensures other apps cannot spoof these state updates
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

    }

    private boolean isReadyForAutoBackgroundSync() {
        return pairingCode != null
                && hasPersistedPermission(targetFolderUri)
                && isWorkingInBackgroundAllowed;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isWorkingInBackgroundAllowed) return;

        boolean userHadStartedProcess = getWasProcessingFlag();

        if (targetFolderUri != null && fileSyncManager == null) {
            DocumentFile targetFolder = DocumentFile.fromTreeUri(this, targetFolderUri);
            fileSyncManager = new FileSyncManager(getContentResolver(), targetFolder);
        }

        if (activitySyncController == null) {
            initSyncController();
        }

        if (userHadStartedProcess) {
            if (!activitySyncController.isRunning) {
                appState.set(AppState.CONNECTING);
                updateButtonStates();
                activitySyncController.start();
            } else {
                // SCENARIO 3: App was just in the background briefly.
                // The process survived, activitySyncController is still alive and running!
                Log.d("SyncActivity", "Process survived backgrounding. Doing nothing, letting it continue.");
            }

        } else {
            Log.d("SyncActivity", "User never started the process. Standing by.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop the client immediately when they leave to prevent background timeouts/errors
        if (activitySyncController != null) {
            activitySyncController.stop();
        }
    }

    private void initSyncController() {
        activitySyncController = new GrpcSyncController(sslSocketFactory, pairingCode, new SyncCallback() {
            @Override
            public void onConnectionReady() {
                appState.set(AppState.CONNECTED);
                runOnUiThread(() -> updateButtonStates());
            }

            @Override
            public void onFileMessageReceived(FolderSyncProto.FileMessage msg) {
                // The activity might just update a progress bar instead of saving files
                runOnUiThread(() -> fileSyncManager.saveChunkToDocumentFile(msg));
            }

            @Override
            public void onErrorEncountered(Status.Code code, String description, boolean isLocalShutdown) {
                if (isLocalShutdown) {
                    appState.set(AppState.NOT_READY);
                }
                else {
                    appState.set(AppState.RECONNECTING);
                }
                runOnUiThread(() -> updateButtonStates());
            }

            @Override
            public void onStreamCompleted() {
                appState.set(AppState.NOT_READY);
                runOnUiThread(() -> updateButtonStates());
            }
        });
    }

    private boolean initSslSocketFactory() {
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
            Toast.makeText(this, "Error in app configuration. Contact an administrator.", LENGTH_SHORT).show();
            return false;
        }

        try {
            caInput.close();
        }
        catch (IOException e) {
            Log.e("SyncService", "Failed to close ca certificate file open stream");
        }
        return true;
    }



    private void loadData() {
        pairingCode = getSavedPairingCode();
        targetFolderUri = getSavedFolderUri();
        isWorkingInBackgroundAllowed = getWorkInBackgroundFlag();
    }
    private boolean hasPersistedPermission(Uri uri) {
        if (uri == null) return false;

        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission perm : perms) {
            if (perm.getUri().equals(uri) && perm.isReadPermission() && perm.isWritePermission()) {
                return true;
            }
        }
        return false;
    }
    private boolean checkNotificationPermission() {
        // Check if Android version is 13 (API 33) or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
                // Already authorized: fire service up directly
        } else {
            // API levels under 33 grant notification rights implicitly at installation time
            return true;
        }
    }
    private void openDirectoryChooser() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void updateButtonStates() {
        String token = binding.etPairingCode.getText().toString().trim();
        boolean isTokenValid = token.length() == 4;

        // CONDITION: Sync can ONLY start if a directory is picked AND token is typed
        if (targetFolderUri != null && isTokenValid) {
            appState.compareAndSet(AppState.NOT_READY, AppState.READY);
        } else {
            appState.compareAndSet(AppState.READY, AppState.NOT_READY);
        }

        if(appState.get() == AppState.CONNECTING
                || appState.get()  == AppState.DISCONNECTING
                || appState.get() == AppState.NOT_READY) {
            binding.btnStartSync.setEnabled(false);
            binding.btnStartSync.setAlpha(0.5f); // Dimmed look indicating it's locked
        }
        else if (appState.get() == AppState.RECONNECTING) {
            binding.btnStartSync.setEnabled(true);
            binding.btnStartSync.setAlpha(1.0f); // Dimmed look indicating it's locked
        }

        if (appState.get() == AppState.CONNECTED
                || appState.get() == AppState.RECONNECTING
                || appState.get() == AppState.READY) {
            binding.btnStartSync.setEnabled(true);
            binding.btnStartSync.setAlpha(1.0f); // Dimmed look indicating it's locked
        }

        binding.btnStartSync.setText(appState.toString());
    }

    private final AtomicReference<AppState> appState = new AtomicReference<>(AppState.NOT_READY);

    public enum AppState {
        READY("START"),
        NOT_READY("START"),
        CONNECTED("STOP"),
        CONNECTING("CONNECTING"),
        DISCONNECTING("DISCONNECTING"),
        RECONNECTING("RECONNECTING (STOP NOW)");


        private final String label;

        // Private constructor (implicitly private in enums)
        AppState(String label) {
            this.label = label;
        }

        // Standard getter for the custom string
        public String getLabel() {
            return label;
        }

        // Overriding toString allows direct use in print statements
        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    private Uri getSavedFolderUri() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String uriString = prefs.getString("target_folder_uri", null);
        if (uriString == null) return null;
        return Uri.parse(uriString);
    }
    private void saveTargetFolderUri(Uri targetFolderUri) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("target_folder_uri", targetFolderUri.toString())
                .apply();
    }
    public void saveWorkingInBackgroundFlag() {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("work_in_background", isWorkingInBackgroundAllowed)
                .apply();
    }
    public void savePairingCode(String pairingCode) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("pairing_code", pairingCode)
                .apply();
    }
    public void saveWasProcessingFlag(boolean isProcessing) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("was_processing", isProcessing)
                .apply();
    }
    private String getSavedPairingCode() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        return prefs.getString("pairing_code", null);
    }
    private boolean getWorkInBackgroundFlag() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        return prefs.getBoolean("work_in_background", false);
    }

    private boolean getWasProcessingFlag() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        return prefs.getBoolean("was_processing", false);
    }

    public static String getHumanReadablePath(Uri uri) {
        if (uri == null) return "";

        String path = uri.getPath();
        if (path == null) return uri.toString();

        // 1. Decode URL characters (e.g., %20 becomes space, %2F becomes /)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        }

        // 2. Strip away SAF prefixes
        if (path.contains("/tree/")) {
            path = path.substring(path.indexOf("/tree/") + 6);
        } else if (path.contains("/document/")) {
            path = path.substring(path.indexOf("/document/") + 10);
        }

        // 3. Handle common storage types
        if (path.startsWith("primary:")) {
            path = path.replace("primary:", "Internal Storage > ");
        } else if (path.startsWith("raw:")) {
            path = path.replace("raw:", "");
        } else if (path.contains(":")) {
            // Handles SD Cards or specific volume names (e.g., "1A2B-3C4D:Folder")
            path = path.replace(":", " > ");
        }

        // 4. Clean up remaining slashes for a polished UI appearance
        path = path.replaceAll("/", " > ");

        // Remove trailing or leading " > " if they exist
        if (path.startsWith(" > ")) path = path.substring(3);
        if (path.endsWith(" > ")) path = path.substring(0, path.length() - 3);

        return path;
    }

    @Override
    protected void onStart() {
        super.onStart();


    }

    @Override
    protected void onStop(){
        super.onStop();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(serviceStateReceiver);
        activitySyncController.stop();
    }

    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SyncService.ACTION_SERVICE_STATE.equals(intent.getAction())) {
                String name = intent.getStringExtra("service_state");
                Log.d("sinc9", "jo");
                SyncService.ServiceState serviceState = SyncService.ServiceState.valueOf(name);

                switch (serviceState) {
                    case CONNECTED:
                        appState.set(AppState.CONNECTED);
                        updateButtonStates();
                        break;
                    case STOPPED:
                        appState.set(AppState.NOT_READY);
                        updateButtonStates();
                        break;
                    case DISCONNECTING:
                        appState.set(AppState.DISCONNECTING);
                        updateButtonStates();
                        break;
                }
            }
        }
    };

    public static final String ACTION_APP_STATE = "com.folder.sync.APP_STATE";

    private void sendStateBroadcast() {
        Intent intent = new Intent(ACTION_APP_STATE);
        intent.putExtra("app_state", appState.get().name());
        // Restricts the broadcast strictly inside your app's sandbox
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void checkNetworkAndRun() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if (cm == null) {
            showWifiError();
            return;
        }

        // 1. Define a request looking specifically for an active WI-FI transport layer
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        // 2. Request the system to find this network
        cm.requestNetwork(wifiRequest, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                Log.d("SyncNetwork", "Wi-Fi network found! Binding app process to it.");

                // 3. FORCE the entire app process to only use this Wi-Fi network interface
                cm.bindProcessToNetwork(network);

                // 4. Now it is safe to fire your gRPC thread
                try {
                    if (activitySyncController == null) initSyncController();
                    activitySyncController.initAuthBlockingStub();
                    FolderSyncGrpc.FolderSyncBlockingStub authStub = activitySyncController.getAuthBlockingStub();

                    FolderSyncProto.AuthResponse response = authStub.verifyToken(
                            FolderSyncProto.AuthRequest.newBuilder().setToken(pairingCode).build()
                    );

                    if (response.getSuccess()) {
                        runOnUiThread(() -> {
                            savePairingCode(pairingCode);
                            startActualSyncService();
                        });
                    } else {
                        runOnUiThread(() -> {
                            binding.etPairingCode.setError("Invalid pairing code!");
                            appState.set(AppState.NOT_READY);
                            updateButtonStates();
                        });
                    }
                } catch (StatusRuntimeException e) {
                    runOnUiThread(() -> {
                        if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                            binding.etPairingCode.setError("Invalid pairing code!");
                            appState.set(AppState.NOT_READY);
                            updateButtonStates();
                        } else {
                            Log.d("sinc", e.toString() + "_" + e.getTrailers());
                            Toast.makeText(getApplicationContext(), "PC unreachable", LENGTH_SHORT).show();
                            appState.set(AppState.NOT_READY);
                            updateButtonStates();
                        }
                    });
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                Log.e("SyncNetwork", "Wi-Fi network connection was lost.");
                cm.bindProcessToNetwork(null);
            }
        });
    }

    private void showWifiError() {
        Toast.makeText(this, "⚠️ Sync requires a Wi-Fi connection. Please connect to your local network.", Toast.LENGTH_LONG).show();
        appState.set(AppState.NOT_READY);
        updateButtonStates();
    }

    private void startActualSyncService() {
        if(isWorkingInBackgroundAllowed) {
            if (!checkNotificationPermission()) {
                Toast.makeText(this, "Grant notification permission before starting app in background", LENGTH_SHORT).show();
                appState.set(AppState.NOT_READY);
                updateButtonStates();
            }
            Intent intent = new Intent(this, SyncService.class);
            intent.putExtra("target_folder_uri", targetFolderUri.toString());
            intent.putExtra("pairing_code", pairingCode);

            ContextCompat.startForegroundService(this, intent);
        }
        else {
            DocumentFile targetFolder = DocumentFile.fromTreeUri(this, targetFolderUri);
            fileSyncManager = new FileSyncManager(getContentResolver(), targetFolder);

            activitySyncController.start();
        }
    }

}