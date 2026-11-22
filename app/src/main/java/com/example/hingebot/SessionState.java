package com.example.hingebot;

/**
 * Explicit states for the SmartDiscovery automation session lifecycle.
 */
public enum SessionState {
    /** No session is active. The controller is ready to start. */
    IDLE,
    /** A session is active and steps are being executed. */
    RUNNING,
    /** A session is temporarily paused. */
    PAUSED,
    /** A session was stopped cleanly by the user or by reaching a cap. */
    STOPPED,
    /** A session was halted due to an unrecoverable error. */
    ERROR
}
