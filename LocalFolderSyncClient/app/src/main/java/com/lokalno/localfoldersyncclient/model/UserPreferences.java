package com.lokalno.localfoldersyncclient.model;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class UserPreferences {

    private String pairingCode;
    private Uri targetFolderUri;



    private boolean isWorkingInBackgroundAllowed;
    private boolean isPausedInForeground;
    private InetAddress defaultDeviceIP;



    public String getPairingCode() {
        return pairingCode;
    }
    public Uri getTargetFolderUri() { return targetFolderUri; }
    public boolean isWorkingInBackgroundAllowed() {
        return isWorkingInBackgroundAllowed;
    }
    public boolean isPausedInForeground() {
        return isPausedInForeground;
    }
    public InetAddress getDefaultDeviceIP() {
        return defaultDeviceIP;
    }


    public void setPairingCode(String pairingCode) {
        this.pairingCode = pairingCode;
    }
    public void setTargetFolderUri(Uri targetFolderUri) {
        this.targetFolderUri = targetFolderUri;
    }

    public void setWorkingInBackgroundAllowed(boolean workingInBackgroundAllowed) {
        isWorkingInBackgroundAllowed = workingInBackgroundAllowed;
    }

    public UserPreferences() {}

    public UserPreferences(
            String pairingCode,
            Uri targetFolderUri,
            boolean isWorkingInBackgroundAllowed,
            InetAddress defaultDeviceIP) {
        this.isWorkingInBackgroundAllowed = isWorkingInBackgroundAllowed;
        this.targetFolderUri = targetFolderUri;
        this.pairingCode = pairingCode;
        this.defaultDeviceIP = defaultDeviceIP;
    }

    public void loadData(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE);

        String uriString = prefs.getString("target_folder_uri", null);
        if (uriString != null) targetFolderUri = Uri.parse(uriString);
        pairingCode = prefs.getString("pairing_code", null);
        isWorkingInBackgroundAllowed = prefs.getBoolean("work_in_background", false);
        isPausedInForeground =  prefs.getBoolean("is_paused_in_foreground", false);
        String defaultDeviceIPString = prefs.getString("default_device_ip", null);
        if (defaultDeviceIPString != null) {
            try {
                defaultDeviceIP = InetAddress.getByName(defaultDeviceIPString);
            }
            catch (UnknownHostException e) {
                Log.d("MyService444", "IP address loaded from user preferences is not tied to any known host.");
            }
        }
    }

    public void savePairingCode(Context context, String pairingCode) {
        this.pairingCode = pairingCode;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("pairing_code", pairingCode)
                .apply();
    }
    public void saveTargetFolderUri(Context context, Uri targetFolderUri) {
        this.targetFolderUri = targetFolderUri;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("target_folder_uri", targetFolderUri.toString())
                .apply();
    }
    public void saveWorkingInBackgroundFlag(Context context, boolean isWorkingInBackgroundAllowed) {
        this.isWorkingInBackgroundAllowed = isWorkingInBackgroundAllowed;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("work_in_background", isWorkingInBackgroundAllowed)
                .apply();
    }
    public void saveIsPausedInForeground(Context context, boolean isPausedInForeground) {
        this.isPausedInForeground = isPausedInForeground;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("was_processing", isPausedInForeground)
                .apply();
    }
    public void saveDefaultDeviceIP(Context context, InetAddress defaultDeviceIP) {
        this.defaultDeviceIP = defaultDeviceIP;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("default_device_ip", defaultDeviceIP.toString())
                .apply();
    }
    public void deleteTargetFolderUri(Context context) {
        targetFolderUri = null;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .remove("target_folder_uri")
                .apply();
    }

    public void deleteIsPausedInForeground(Context context) {
        isPausedInForeground = false;
        context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .remove("is_paused_in_foreground")
                .apply();
    }

}
