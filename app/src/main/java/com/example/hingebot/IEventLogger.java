package com.example.hingebot;

import java.util.List;

/**
 * Interface for structured event logging.
 * Consumers receive an ordered, immutable snapshot of recent events.
 */
public interface IEventLogger {

    /** Record a new event. */
    void log(SessionEvent event);

    /** @return Ordered list of all events since the last {@link #clear()}, newest last. */
    List<SessionEvent> getEvents();

    /** Remove all stored events. */
    void clear();
}
