package com.example.hingebot;

/**
 * Interface for the automation controller layer.
 * Keeps the UI independent from execution logic.
 */
public interface IAutomationController {

    /** Start a new automation session. Idempotent if already running. */
    void startAutomation();

    /** Pause the current automation session. */
    void pauseAutomation();

    /** Resume a paused automation session. */
    void resumeAutomation();

    /**
     * Stop the current automation session cleanly.
     *
     * @param reason Human-readable reason for the stop, used in event history.
     */
    void stopAutomation(String reason);

    /** @return true if a session is currently active. */
    boolean isAutomationRunning();
}
