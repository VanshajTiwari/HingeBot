package com.hingebot;

public enum ExitCode {
    SUCCESS(0),
    INVALID_CONFIG_OR_USAGE(1),
    ADB_ERROR(2),
    ACTION_OR_PREFLIGHT_FAILURE(3),
    OUT_OF_LIKES(4),
    UNEXPECTED_ERROR(5);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
