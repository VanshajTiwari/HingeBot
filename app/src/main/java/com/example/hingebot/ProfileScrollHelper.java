package com.example.hingebot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Dedicated Profile Scroll & Gesture Helper.
 *
 * <p>Encapsulates screen scrolling mechanics, human-like smooth profile scroll gestures,
 * duration/settle timing, and optional node-level scroll fallback actions for maintainability
 * across all automation executors.</p>
 */
public class ProfileScrollHelper {

    private static final String TAG = "ProfileScrollHelper";

    // ── Default Gesture Scroll Timing ─────────────────────────────────────────
    public static final long DEFAULT_SCROLL_DURATION_MS = 400;
    public static final long DEFAULT_SCROLL_SETTLE_MS   = 750;

    // ── Screen ratios for profile down-scroll ─────────────────────────────────
    private static final float CENTER_X_RATIO = 0.50f;
    private static final float START_Y_RATIO  = 0.70f;
    private static final float END_Y_RATIO    = 0.38f;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface ScrollCompletionCallback {
        void onScrollCompleted();
    }

    public ProfileScrollHelper(AccessibilityService service) {
        this.service = service;
    }

    /**
     * Performs a human-like smooth profile scroll down gesture.
     *
     * @param sw Screen width in pixels
     * @param sh Screen height in pixels
     */
    public void scrollDown(int sw, int sh) {
        scrollDown(sw, sh, DEFAULT_SCROLL_DURATION_MS, null);
    }

    /**
     * Performs a human-like smooth profile scroll down gesture with custom duration.
     *
     * @param sw         Screen width in pixels
     * @param sh         Screen height in pixels
     * @param durationMs Gesture stroke duration in milliseconds
     */
    public void scrollDown(int sw, int sh, long durationMs) {
        scrollDown(sw, sh, durationMs, null);
    }

    /**
     * Performs a human-like smooth profile scroll down gesture and notifies when complete.
     *
     * @param sw         Screen width in pixels
     * @param sh         Screen height in pixels
     * @param durationMs Gesture stroke duration in milliseconds
     * @param callback   Callback invoked after the scroll gesture finishes
     */
    public void scrollDown(int sw, int sh, long durationMs, ScrollCompletionCallback callback) {
        if (sw <= 0 || sh <= 0 || service == null) {
            if (callback != null) callback.onScrollCompleted();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (callback != null) callback.onScrollCompleted();
            return;
        }

        Path scrollPath = new Path();
        float startX = sw * CENTER_X_RATIO;
        float startY = sh * START_Y_RATIO;
        float endY   = sh * END_Y_RATIO;

        scrollPath.moveTo(startX, startY);
        scrollPath.lineTo(startX, endY);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(scrollPath, 0, durationMs))
                .build();

        AccessibilityService.GestureResultCallback resultCallback = null;
        if (callback != null) {
            resultCallback = new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    handler.postDelayed(callback::onScrollCompleted, DEFAULT_SCROLL_SETTLE_MS);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    handler.post(callback::onScrollCompleted);
                }
            };
        }

        boolean dispatched = service.dispatchGesture(gesture, resultCallback, handler);
        if (!dispatched) {
            Log.w(TAG, "Scroll gesture could not be dispatched.");
            if (callback != null) callback.onScrollCompleted();
        } else {
            Log.d(TAG, "Dispatched smooth profile scroll down gesture (" + durationMs + "ms).");
        }
    }

    /**
     * Performs a node-level scroll forward action if available on a scrollable accessibility node.
     *
     * @param node The node to scroll
     * @return true if ACTION_SCROLL_FORWARD was performed successfully
     */
    public boolean performNodeScrollForward(AccessibilityNodeInfo node) {
        if (node != null && node.isScrollable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        return false;
    }
}
