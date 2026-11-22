package com.example.hingebot;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityWindowInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SmartDiscovery Automation Controller Service.
 *
 * <p>Modular AccessibilityService that coordinates automation lifecycle and delegates
 * Right Swipes (Likes) to {@link RightSwipeExecutor} and Left Swipes (Rejections) to {@link LeftSwipeExecutor}.</p>
 */
public class HingeAutomationService extends AccessibilityService implements IAutomationController {

    // ── Broadcast contract ────────────────────────────────────────────────────
    public static final String ACTION_STATUS_UPDATE = "com.example.hingebot.STATUS_UPDATE";
    public static final String EXTRA_STATUS        = "status";
    public static final String EXTRA_RIGHT         = "right";
    public static final String EXTRA_LEFT          = "left";
    public static final String EXTRA_TOTAL         = "total";
    public static final String EXTRA_SESSION_STATE = "session_state";
    // Legacy keys kept for compatibility
    public static final String EXTRA_LIKES  = "likes";
    public static final String EXTRA_SKIPS  = "skips";

    private static final String TAG = "SmartDiscovery";

    // ── Static accessor ───────────────────────────────────────────────────────
    private static HingeAutomationService instance;

    public static HingeAutomationService getInstance() { return instance; }
    public static boolean isServiceEnabled()           { return instance != null; }

    // ── Core objects ──────────────────────────────────────────────────────────
    private AutomationStateMachine stateMachine;
    private InMemoryEventLogger    eventLogger;
    private SessionRepository      sessionRepository;

    private RightSwipeExecutor rightSwipeExecutor;
    private LeftSwipeExecutor  leftSwipeExecutor;

    private final Handler handler  = new Handler(Looper.getMainLooper());
    private final Random  random   = new Random();

    private static final int MAX_CONSECUTIVE_LEFT_FAILURES = 5;
    private int consecutiveLeftFailures = 0;

    private boolean stepInFlight = false;
    private AccessibilityNodeInfo lastEventRoot = null;

    // ── AccessibilityService lifecycle ────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        eventLogger        = new InMemoryEventLogger();
        sessionRepository  = new SessionRepository(this);
        stateMachine       = new AutomationStateMachine(
                SequenceConfig.SEQUENCE,
                SequenceConfig.MAX_RIGHT_ACTIONS,
                SequenceConfig.MAX_TOTAL_ACTIONS,
                eventLogger,
                true);

        rightSwipeExecutor = new RightSwipeExecutor(this);
        leftSwipeExecutor  = new LeftSwipeExecutor(this);

        Log.i(TAG, "Service connected — SmartDiscovery ready.");
        broadcastStatus("Ready");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        instance = null;
        lastEventRoot = null;
        Log.i(TAG, "Service destroyed.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                lastEventRoot = source;
            }
        }
    }

    @Override
    public void onInterrupt() {
        stopAutomation("Service interrupted");
    }

    // ── IAutomationController ─────────────────────────────────────────────────

    @Override
    public void startAutomation() {
        if (!isServiceEnabled()) {
            broadcastStatus("Error: Accessibility service not enabled");
            return;
        }
        if (stateMachine.getState() == SessionState.RUNNING) return; // idempotent

        if (stateMachine.getState() == SessionState.PAUSED) {
            resumeAutomation();
            return;
        }

        stepInFlight = false;
        consecutiveLeftFailures = 0;
        stateMachine.start();
        sessionRepository.resetSession();
        sessionRepository.saveSessionState(SessionState.RUNNING);
        broadcastStatus("Running");
        Log.i(TAG, "Automation started — sequence: " + Arrays.toString(SequenceConfig.SEQUENCE));
        scheduleStep(SequenceConfig.INITIAL_DELAY_MS);
    }

    @Override
    public void pauseAutomation() {
        if (!stateMachine.isRunning()) return;
        handler.removeCallbacksAndMessages(null);
        rightSwipeExecutor.cancel();
        stepInFlight = false;
        stateMachine.pause();
        sessionRepository.saveSessionState(SessionState.PAUSED);
        broadcastStatus("Paused");
        Log.i(TAG, "Automation paused.");
    }

    @Override
    public void resumeAutomation() {
        if (stateMachine.getState() != SessionState.PAUSED) return;
        stateMachine.resume();
        sessionRepository.saveSessionState(SessionState.RUNNING);
        broadcastStatus("Running");
        Log.i(TAG, "Automation resumed.");
        scheduleStep(SequenceConfig.INITIAL_DELAY_MS);
    }

    @Override
    public void stopAutomation(String reason) {
        if (stateMachine.getState() == SessionState.IDLE || stateMachine.getState() == SessionState.STOPPED) return;
        handler.removeCallbacksAndMessages(null);
        rightSwipeExecutor.cancel();
        stepInFlight = false;
        stateMachine.stop(reason);
        sessionRepository.saveSessionState(SessionState.STOPPED);
        broadcastStatus("Stopped: " + reason);
        Log.i(TAG, "Automation stopped — " + reason);
    }

    @Override
    public boolean isAutomationRunning() {
        return stateMachine != null && stateMachine.isRunning();
    }

    // ── Step scheduling ───────────────────────────────────────────────────────

    private void scheduleStep(long delayMs) {
        if (!stateMachine.isRunning() || stepInFlight) return;
        stepInFlight = true;
        handler.postDelayed(this::executeStep, delayMs);
    }

    private void executeStep() {
        stepInFlight = false;

        if (!stateMachine.isRunning()) return;

        if (stateMachine.isNextStepBlocked()) {
            stopAutomation("Cap reached");
            return;
        }

        AccessibilityNodeInfo root = getBestRootNode();
        if (root == null) {
            stateMachine.recordStepSkipped("Root window unavailable");
            scheduleStep(SequenceConfig.RETRY_DELAY_MS);
            return;
        }

        if (findNodeByPhrases(root, List.of("out of likes", "no likes left", "run out of likes", "get more likes")) != null) {
            stopAutomation("Daily like limit reached");
            return;
        }

        // Run the executor that matches the step the state machine is about to count, so the
        // R / L counters always reflect what was actually performed on screen.
        if (stateMachine.peekNextDirection() == SwipeDirection.LEFT) {
            executeLeftStep(root);
        } else {
            executeRightStep(root);
        }
    }

    // ── Delegated Step Execution ──────────────────────────────────────────────

    private void executeRightStep(AccessibilityNodeInfo root) {
        Rect screenBounds = getActiveWindowBounds(root);
        broadcastStatus("RIGHT — 1st click (heart) & confirmation…");

        rightSwipeExecutor.execute(root, screenBounds, (success, statusMessage) -> {
            if (success) {
                stateMachine.recordStepSuccess();
                sessionRepository.incrementRightCount();
                broadcastStatus("RIGHT ✓ (" + stateMachine.getRightCount() + " rights)");
            } else {
                stateMachine.recordStepSkipped(statusMessage);
            }

            if (stateMachine.isRunning()) {
                scheduleStep(randomDelay());
            } else {
                broadcastStatus("Stopped: " + stateMachine.getState().name());
            }
        });
    }

    private void executeLeftStep(AccessibilityNodeInfo root) {
        Rect screenBounds = getActiveWindowBounds(root);

        broadcastStatus("LEFT — locating X (skip) button…");

        leftSwipeExecutor.execute(root, screenBounds, (success, statusMessage) -> {
            if (success) {
                consecutiveLeftFailures = 0;
                stateMachine.recordStepSuccess();
                sessionRepository.incrementLeftCount();
                broadcastStatus("LEFT ✓ (" + stateMachine.getLeftCount() + " lefts, total "
                        + stateMachine.getTotalCount() + ")");
            } else {
                stateMachine.recordStepSkipped(statusMessage);
                consecutiveLeftFailures++;
                if (consecutiveLeftFailures >= MAX_CONSECUTIVE_LEFT_FAILURES) {
                    stopAutomation("X button not clickable after " + consecutiveLeftFailures
                            + " tries — see logcat tag LeftSwipeExecutor");
                    return;
                }
                broadcastStatus(statusMessage + " (" + consecutiveLeftFailures + "/"
                        + MAX_CONSECUTIVE_LEFT_FAILURES + ")");
            }

            if (stateMachine.isRunning()) {
                scheduleStep(randomDelay());
            } else {
                broadcastStatus("Stopped: " + stateMachine.getState().name());
            }
        });
    }

    // ── Root Recovery Helper ──────────────────────────────────────────────────

    private AccessibilityNodeInfo getBestRootNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) return root;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window != null) {
                        AccessibilityNodeInfo wRoot = window.getRoot();
                        if (wRoot != null) return wRoot;
                    }
                }
            }
        }

        return lastEventRoot;
    }

    private Rect getActiveWindowBounds(AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        if (root != null) {
            root.getBoundsInScreen(bounds);
        }
        if (bounds.right <= 0 || bounds.bottom <= 0) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getMetrics(dm);
                bounds.set(0, 0, dm.widthPixels, dm.heightPixels);
            }
        }
        return bounds;
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    private AccessibilityNodeInfo findNodeByPhrases(AccessibilityNodeInfo parent, List<String> phrases) {
        if (parent == null) return null;

        CharSequence text = parent.getText();
        CharSequence desc = parent.getContentDescription();
        String viewId     = parent.getViewIdResourceName();

        String t = text != null ? text.toString().toLowerCase(Locale.ROOT) : "";
        String d = desc != null ? desc.toString().toLowerCase(Locale.ROOT) : "";
        String v = viewId != null ? viewId.toLowerCase(Locale.ROOT) : "";

        for (String phrase : phrases) {
            String lower = phrase.toLowerCase(Locale.ROOT);
            if (t.contains(lower) || d.contains(lower) || v.contains(lower)) return parent;
        }

        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            AccessibilityNodeInfo match = findNodeByPhrases(child, phrases);
            if (match != null) return match;
        }
        return null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private long randomDelay() {
        long range = SequenceConfig.MAX_STEP_DELAY_MS - SequenceConfig.MIN_STEP_DELAY_MS;
        return SequenceConfig.MIN_STEP_DELAY_MS + (long) (random.nextFloat() * range);
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        int right = stateMachine != null ? stateMachine.getRightCount() : 0;
        int left  = stateMachine != null ? stateMachine.getLeftCount()  : 0;
        int total = stateMachine != null ? stateMachine.getTotalCount() : 0;
        intent.putExtra(EXTRA_RIGHT, right);
        intent.putExtra(EXTRA_LEFT,  left);
        intent.putExtra(EXTRA_TOTAL, total);
        intent.putExtra(EXTRA_LIKES, right);
        intent.putExtra(EXTRA_SKIPS, left);
        SessionState st = stateMachine != null ? stateMachine.getState() : SessionState.IDLE;
        intent.putExtra(EXTRA_SESSION_STATE, st.name());
        sendBroadcast(intent);
    }
}