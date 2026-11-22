package com.example.hingebot;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AutomationStateMachine}.
 * No Android dependencies — runs on the host JVM.
 */
public class AutomationStateMachineTest {

    // Demonstration sequence: RIGHT RIGHT LEFT LEFT LEFT
    private static final SwipeDirection[] SEQUENCE = SequenceConfig.SEQUENCE;
    private static final int MAX_RIGHT = 4;   // small cap for test speed
    private static final int MAX_TOTAL = 10;

    private InMemoryEventLogger    logger;
    private AutomationStateMachine sm;

    @Before
    public void setUp() {
        logger = new InMemoryEventLogger();
        sm     = new AutomationStateMachine(SEQUENCE, MAX_RIGHT, MAX_TOTAL, logger);
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    public void initialState_isIdle() {
        assertEquals(SessionState.IDLE, sm.getState());
        assertFalse(sm.isRunning());
        assertEquals(0, sm.getRightCount());
        assertEquals(0, sm.getLeftCount());
        assertEquals(0, sm.getTotalCount());
    }

    @Test
    public void peekNextDirection_whenIdle_returnsNull() {
        assertNull(sm.peekNextDirection());
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    @Test
    public void start_transitionsToRunning() {
        sm.start();
        assertEquals(SessionState.RUNNING, sm.getState());
        assertTrue(sm.isRunning());
    }

    @Test
    public void start_isIdempotent() {
        sm.start();
        sm.start(); // second call must not throw or reset counters
        assertEquals(SessionState.RUNNING, sm.getState());
    }

    @Test
    public void start_logsSessionStartedEvent() {
        sm.start();
        List<SessionEvent> events = logger.getEvents();
        assertFalse(events.isEmpty());
        assertEquals(SessionEvent.Type.SESSION_STARTED, events.get(0).getType());
    }

    // ── Sequence order ────────────────────────────────────────────────────────

    @Test
    public void sequence_firstStepIsRight() {
        sm.start();
        assertEquals(SwipeDirection.RIGHT, sm.peekNextDirection());
    }

    @Test
    public void sequence_secondStepIsRight() {
        sm.start();
        sm.recordStepSuccess(); // step 0 → RIGHT
        assertEquals(SwipeDirection.RIGHT, sm.peekNextDirection());
    }

    @Test
    public void sequence_thirdStepIsLeft() {
        sm.start();
        sm.recordStepSuccess(); // RIGHT
        sm.recordStepSuccess(); // RIGHT
        assertEquals(SwipeDirection.LEFT, sm.peekNextDirection());
    }

    @Test
    public void sequence_fourthStepIsLeft() {
        sm.start();
        sm.recordStepSuccess(); // RIGHT
        sm.recordStepSuccess(); // RIGHT
        sm.recordStepSuccess(); // LEFT
        assertEquals(SwipeDirection.LEFT, sm.peekNextDirection());
    }

    @Test
    public void sequence_fifthStepIsLeft() {
        sm.start();
        sm.recordStepSuccess(); // RIGHT
        sm.recordStepSuccess(); // RIGHT
        sm.recordStepSuccess(); // LEFT
        sm.recordStepSuccess(); // LEFT
        assertEquals(SwipeDirection.LEFT, sm.peekNextDirection());
    }

    @Test
    public void sequence_wrapsAfterFiveSteps() {
        sm.start();
        // Complete one full cycle (2R + 3L = 5 steps)
        for (int i = 0; i < 5; i++) sm.recordStepSuccess();
        // Should wrap back to RIGHT
        assertEquals(SwipeDirection.RIGHT, sm.peekNextDirection());
    }

    // ── Counters ──────────────────────────────────────────────────────────────

    @Test
    public void counters_incrementCorrectly_afterFullCycle() {
        sm.start();
        for (int i = 0; i < 5; i++) sm.recordStepSuccess();
        assertEquals(2, sm.getRightCount());
        assertEquals(3, sm.getLeftCount());
        assertEquals(5, sm.getTotalCount());
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    @Test
    public void stop_transitionsToStopped() {
        sm.start();
        sm.stop("User request");
        assertEquals(SessionState.STOPPED, sm.getState());
        assertFalse(sm.isRunning());
    }

    @Test
    public void stop_logsStoppedEvent() {
        sm.start();
        sm.stop("Test stop");
        List<SessionEvent> events = logger.getEvents();
        SessionEvent last = events.get(events.size() - 1);
        assertEquals(SessionEvent.Type.SESSION_STOPPED, last.getType());
        assertTrue(last.getDetail().contains("Test stop"));
    }

    @Test
    public void stop_fromIdle_isNoop() {
        sm.stop("No session"); // must not throw
        assertEquals(SessionState.IDLE, sm.getState());
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    @Test
    public void error_transitionsToError() {
        sm.start();
        sm.error("Something went wrong");
        assertEquals(SessionState.ERROR, sm.getState());
    }

    @Test
    public void startFromError_throwsIllegalStateException() {
        sm.start();
        sm.error("test");
        try {
            sm.start();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    public void reset_fromError_allowsRestart() {
        sm.start();
        sm.error("test");
        sm.reset();
        assertEquals(SessionState.IDLE, sm.getState());
        sm.start(); // must not throw
        assertEquals(SessionState.RUNNING, sm.getState());
    }

    @Test
    public void reset_clearsCounters() {
        sm.start();
        sm.recordStepSuccess();
        sm.recordStepSuccess();
        sm.stop("done");
        sm.reset();
        assertEquals(0, sm.getRightCount());
        assertEquals(0, sm.getLeftCount());
    }

    // ── Caps ─────────────────────────────────────────────────────────────────

    @Test
    public void maxRightCap_stopsSession() {
        // MAX_RIGHT = 4; sequence has 2 RIGHT per cycle, so 2 full cycles = 4 rights
        sm.start();
        for (int i = 0; i < 4 * 2 + 4 * 3; i++) { // won't get this far
            if (!sm.isRunning()) break;
            sm.recordStepSuccess();
        }
        // State should be STOPPED after right cap hit
        assertFalse(sm.isRunning());
        assertTrue(sm.getRightCount() <= MAX_RIGHT);
    }

    @Test
    public void maxTotalCap_stopsSession() {
        sm.start();
        int steps = 0;
        while (sm.isRunning() && steps < MAX_TOTAL + 5) {
            sm.recordStepSuccess();
            steps++;
        }
        assertFalse(sm.isRunning());
        assertTrue(sm.getTotalCount() <= MAX_TOTAL);
    }

    @Test
    public void isNextStepBlocked_whenNotRunning_returnsTrue() {
        assertTrue(sm.isNextStepBlocked());
    }

    @Test
    public void isNextStepBlocked_whenRunning_returnsFalse() {
        sm.start();
        assertFalse(sm.isNextStepBlocked());
    }

    // ── Skip recording ────────────────────────────────────────────────────────

    @Test
    public void recordStepSkipped_doesNotAdvanceIndex() {
        sm.start();
        SwipeDirection before = sm.peekNextDirection();
        sm.recordStepSkipped("node not found");
        assertEquals(before, sm.peekNextDirection());
    }

    @Test
    public void recordStepSkipped_logsEvent() {
        sm.start();
        sm.recordStepSkipped("test skip");
        boolean found = logger.getEvents().stream()
                .anyMatch(e -> e.getType() == SessionEvent.Type.STEP_SKIPPED);
        assertTrue(found);
    }

    // ── Event logger ─────────────────────────────────────────────────────────

    @Test
    public void eventLogger_clear_removesAllEvents() {
        sm.start();
        sm.recordStepSuccess();
        logger.clear();
        assertTrue(logger.getEvents().isEmpty());
    }

    // ── Pause and Resume ──────────────────────────────────────────────────────

    @Test
    public void pause_transitionsToPaused() {
        sm.start();
        sm.pause();
        assertEquals(SessionState.PAUSED, sm.getState());
        assertFalse(sm.isRunning());
        assertNull(sm.peekNextDirection());
    }

    @Test
    public void resume_fromPaused_transitionsToRunning() {
        sm.start();
        sm.pause();
        sm.resume();
        assertEquals(SessionState.RUNNING, sm.getState());
        assertTrue(sm.isRunning());
        assertNotNull(sm.peekNextDirection());
    }

    // ── Random 10-Swipe Cycle Generation ──────────────────────────────────────

    @Test
    public void generateRandomCycle_rightsAlwaysLesserThanLefts() {
        java.util.Random rand = new java.util.Random();
        for (int iteration = 0; iteration < 100; iteration++) {
            SwipeDirection[] cycle = AutomationStateMachine.generateRandomCycle(rand, 10);
            assertEquals(10, cycle.length);
            int rights = 0;
            int lefts = 0;
            for (SwipeDirection dir : cycle) {
                if (dir == SwipeDirection.RIGHT) rights++;
                else lefts++;
            }
            assertTrue("Rights (" + rights + ") must be less than Lefts (" + lefts + ")", rights < lefts);
            assertTrue("Rights must be between 1 and 4", rights >= 1 && rights <= 4);
        }
    }

    @Test
    public void dynamicRandomCycleMode_generatesCycleOnStart() {
        AutomationStateMachine dynamicSm = new AutomationStateMachine(
                SequenceConfig.SEQUENCE, 8, 40, logger, true);
        dynamicSm.start();
        SwipeDirection[] cycle = dynamicSm.getCurrentSequence();
        assertEquals(10, cycle.length);
        int rights = 0;
        int lefts = 0;
        for (SwipeDirection dir : cycle) {
            if (dir == SwipeDirection.RIGHT) rights++;
            else lefts++;
        }
        assertTrue(rights < lefts);
    }

    // ── Constructor guard ─────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void constructor_emptySequence_throws() {
        new AutomationStateMachine(new SwipeDirection[]{}, 8, 40, logger);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullSequence_throws() {
        new AutomationStateMachine(null, 8, 40, logger);
    }
}
