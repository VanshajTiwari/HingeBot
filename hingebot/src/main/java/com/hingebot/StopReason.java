package com.hingebot;

public enum StopReason {
    COMPLETED("completed cycles"),
    STOP_FILE("stop file detected"),
    STOP_FLAG("shutdown requested / ctrl+c"),
    MAX_TOTAL_ACTIONS("max total actions reached"),
    MAX_LIKES("max likes reached"),
    OUT_OF_LIKES("daily like limit reached");

    private final String description;

    StopReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
