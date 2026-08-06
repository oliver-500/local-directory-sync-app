package com.lokalno.localfoldersyncclient;

import androidx.annotation.NonNull;

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

