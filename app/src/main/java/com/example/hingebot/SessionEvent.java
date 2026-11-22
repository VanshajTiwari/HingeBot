package com.example.hingebot;

/**
 * Immutable record of a single automation event for the structured event history.
 */
public final class SessionEvent {

    public enum Type {
        SESSION_STARTED,
        STEP_RIGHT,
        STEP_LEFT,
        STEP_SKIPPED,   // target node not found on this attempt
        SESSION_STOPPED,
        SESSION_ERROR,
        CAP_REACHED
    }

    private final long timestampMs;
    private final Type type;
    private final String detail;

    public SessionEvent(Type type, String detail) {
        this.timestampMs = System.currentTimeMillis();
        this.type = type;
        this.detail = detail;
    }

    public long getTimestampMs() { return timestampMs; }
    public Type getType()        { return type; }
    public String getDetail()    { return detail; }

    @Override
    public String toString() {
        return "[" + timestampMs + "] " + type.name() + ": " + detail;
    }
}
