package com.example.hingebot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe in-memory implementation of {@link IEventLogger}.
 *
 * <p>Intentionally contains NO Android dependencies so that it can be used
 * in host-JVM unit tests without mocking. The Android service layer
 * ({@link HingeAutomationService}) is responsible for forwarding events
 * to logcat if desired.</p>
 */
public class InMemoryEventLogger implements IEventLogger {

    private static final int MAX_EVENTS = 500;

    private final List<SessionEvent> events = new ArrayList<>();

    @Override
    public synchronized void log(SessionEvent event) {
        if (events.size() >= MAX_EVENTS) {
            events.remove(0); // drop oldest to prevent unbounded growth
        }
        events.add(event);
    }

    @Override
    public synchronized List<SessionEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    @Override
    public synchronized void clear() {
        events.clear();
    }
}
