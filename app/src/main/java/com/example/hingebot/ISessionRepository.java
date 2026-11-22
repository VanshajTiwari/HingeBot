package com.example.hingebot;

/**
 * Interface for session statistics persistence.
 * Abstracts storage from the automation and UI layers.
 */
public interface ISessionRepository {

    void saveSessionState(SessionState state);
    SessionState loadSessionState();

    void incrementRightCount();
    void incrementLeftCount();
    int getRightCount();
    int getLeftCount();
    int getTotalCount();

    void resetSession();
}
