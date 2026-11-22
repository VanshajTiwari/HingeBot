package com.example.hingebot;

import java.util.Random;

/**
 * Deterministic state machine for the SmartDiscovery automation session.
 *
 * <p>This class is the single owner of sequence position and counters.
 * All transitions are explicit and logged via {@link IEventLogger}.</p>
 */
public class AutomationStateMachine {

    private SwipeDirection[] sequence;
    private final int maxRightActions;
    private final int maxTotalActions;
    private final IEventLogger logger;
    private final boolean useDynamicRandomCycles;
    private final Random random = new Random();

    private SessionState state = SessionState.IDLE;
    private int stepIndex = 0;
    private int rightCount = 0;
    private int leftCount = 0;

    public AutomationStateMachine(
            SwipeDirection[] sequence,
            int maxRightActions,
            int maxTotalActions,
            IEventLogger logger) {
        this(sequence, maxRightActions, maxTotalActions, logger, false);
    }

    public AutomationStateMachine(
            SwipeDirection[] sequence,
            int maxRightActions,
            int maxTotalActions,
            IEventLogger logger,
            boolean useDynamicRandomCycles) {
        if (sequence == null || sequence.length == 0) {
            throw new IllegalArgumentException("Sequence must not be null or empty");
        }
        this.sequence = sequence;
        this.maxRightActions = maxRightActions;
        this.maxTotalActions = maxTotalActions;
        this.logger = logger;
        this.useDynamicRandomCycles = useDynamicRandomCycles;
    }

    // ── Random Cycle Generator ────────────────────────────────────────────────

    /**
     * Generates a randomized cycle of 10 swipes where the number of RIGHT swipes
     * is strictly less than the number of LEFT swipes (rights: 1..4, lefts: 6..9).
     */
    public static SwipeDirection[] generateRandomCycle(Random rand, int cycleSize) {
        if (cycleSize < 2) cycleSize = 10;
        int maxRights = (cycleSize - 1) / 2; // for 10, maxRights is 4
        int numRights = 1 + rand.nextInt(maxRights); // 1, 2, 3, or 4
        int numLefts = cycleSize - numRights;

        SwipeDirection[] cycle = new SwipeDirection[cycleSize];
        for (int i = 0; i < numRights; i++) {
            cycle[i] = SwipeDirection.RIGHT;
        }
        for (int i = numRights; i < cycleSize; i++) {
            cycle[i] = SwipeDirection.LEFT;
        }

        // Fisher-Yates shuffle
        for (int i = cycle.length - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            SwipeDirection temp = cycle[i];
            cycle[i] = cycle[j];
            cycle[j] = temp;
        }
        return cycle;
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    public synchronized void start() {
        if (state == SessionState.RUNNING) return;
        if (state == SessionState.ERROR) {
            throw new IllegalStateException("Cannot start from ERROR state; call reset() first.");
        }
        if (state == SessionState.PAUSED) {
            resume();
            return;
        }
        stepIndex = 0;
        rightCount = 0;
        leftCount = 0;
        if (useDynamicRandomCycles) {
            this.sequence = generateRandomCycle(random, SequenceConfig.CYCLE_SIZE);
        }
        state = SessionState.RUNNING;
        logger.log(new SessionEvent(SessionEvent.Type.SESSION_STARTED, "Session started"));
    }

    public synchronized void pause() {
        if (state != SessionState.RUNNING) return;
        state = SessionState.PAUSED;
        logger.log(new SessionEvent(SessionEvent.Type.SESSION_STOPPED, "Session paused"));
    }

    public synchronized void resume() {
        if (state != SessionState.PAUSED) return;
        state = SessionState.RUNNING;
        logger.log(new SessionEvent(SessionEvent.Type.SESSION_STARTED, "Session resumed"));
    }

    public synchronized void stop(String reason) {
        if (state == SessionState.IDLE) return;
        state = SessionState.STOPPED;
        logger.log(new SessionEvent(SessionEvent.Type.SESSION_STOPPED, reason));
    }

    public synchronized void error(String detail) {
        state = SessionState.ERROR;
        logger.log(new SessionEvent(SessionEvent.Type.SESSION_ERROR, detail));
    }

    public synchronized void reset() {
        stepIndex = 0;
        rightCount = 0;
        leftCount = 0;
        state = SessionState.IDLE;
    }

    // ── Step execution ────────────────────────────────────────────────────────

    public synchronized SwipeDirection peekNextDirection() {
        if (state != SessionState.RUNNING) return null;
        return sequence[stepIndex % sequence.length];
    }

    public synchronized void recordStepSuccess() {
        if (state != SessionState.RUNNING) return;

        SwipeDirection dir = sequence[stepIndex % sequence.length];

        if (dir == SwipeDirection.RIGHT) {
            rightCount++;
            logger.log(new SessionEvent(SessionEvent.Type.STEP_RIGHT,
                    "RIGHT executed (right=" + rightCount + ", total=" + getTotalCount() + ")"));
        } else {
            leftCount++;
            logger.log(new SessionEvent(SessionEvent.Type.STEP_LEFT,
                    "LEFT executed (left=" + leftCount + ", total=" + getTotalCount() + ")"));
        }

        stepIndex++;
        if (useDynamicRandomCycles && stepIndex % SequenceConfig.CYCLE_SIZE == 0) {
            this.sequence = generateRandomCycle(random, SequenceConfig.CYCLE_SIZE);
        }
        checkCaps();
    }

    public synchronized void recordStepSkipped(String reason) {
        if (state != SessionState.RUNNING) return;
        logger.log(new SessionEvent(SessionEvent.Type.STEP_SKIPPED, reason));
    }

    public synchronized boolean isNextStepBlocked() {
        if (state != SessionState.RUNNING) return true;
        if (getTotalCount() >= maxTotalActions) return true;
        SwipeDirection next = sequence[stepIndex % sequence.length];
        return next == SwipeDirection.RIGHT && rightCount >= maxRightActions;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public synchronized SessionState getState()   { return state; }
    public synchronized int getRightCount()        { return rightCount; }
    public synchronized int getLeftCount()         { return leftCount; }
    public synchronized int getTotalCount()        { return rightCount + leftCount; }
    public synchronized boolean isRunning()        { return state == SessionState.RUNNING; }
    public synchronized SwipeDirection[] getCurrentSequence() { return sequence.clone(); }

    private void checkCaps() {
        if (getTotalCount() >= maxTotalActions) {
            state = SessionState.STOPPED;
            logger.log(new SessionEvent(SessionEvent.Type.CAP_REACHED,
                    "Max total actions reached (" + maxTotalActions + ")"));
            return;
        }
        SwipeDirection next = sequence[stepIndex % sequence.length];
        if (next == SwipeDirection.RIGHT && rightCount >= maxRightActions) {
            state = SessionState.STOPPED;
            logger.log(new SessionEvent(SessionEvent.Type.CAP_REACHED,
                    "Max RIGHT actions reached (" + maxRightActions + ")"));
        }
    }
}
