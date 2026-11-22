package com.example.hingebot;

/**
 * Externalized sequence configuration for the SmartDiscovery automation workflow.
 *
 * <p>Standard cycle size is 10. In each cycle of 10 swipes, the number of RIGHT swipes
 * is strictly less than the number of LEFT swipes (e.g. 1-4 rights, 6-9 lefts)
 * in a randomized order.</p>
 *
 * <p>User controls session stopping explicitly.</p>
 */
public final class SequenceConfig {

    /** Default fallback sequence: RIGHT RIGHT LEFT LEFT LEFT */
    public static final SwipeDirection[] SEQUENCE = {
            SwipeDirection.RIGHT,
            SwipeDirection.RIGHT,
            SwipeDirection.LEFT,
            SwipeDirection.LEFT,
            SwipeDirection.LEFT
    };

    /** Size of each randomized cycle (10 swipes per cycle). */
    public static final int CYCLE_SIZE = 10;

    /** High cap for user-controlled session stopping (user stops manually). */
    public static final int MAX_RIGHT_ACTIONS = 9999;

    /** High cap for user-controlled session stopping (user stops manually). */
    public static final int MAX_TOTAL_ACTIONS = 9999;

    /** Fast minimum delay between steps, in milliseconds. */
    public static final long MIN_STEP_DELAY_MS = 800;

    /** Fast maximum delay between steps, in milliseconds. */
    public static final long MAX_STEP_DELAY_MS = 1500;

    /** Delay before the first step after start, in milliseconds. */
    public static final long INITIAL_DELAY_MS = 300;

    /** Fast retry delay when target UI node is not found, in milliseconds. */
    public static final long RETRY_DELAY_MS = 400;

    /** Fast delay to wait for confirmation sheet ("Send Like") to appear, in milliseconds. */
    public static final long CONFIRMATION_SHEET_DELAY_MS = 350;

    private SequenceConfig() { /* utility class */ }
}
