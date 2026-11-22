package com.example.hingebot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dedicated Left Swipe (Rejection) Executor for Hinge.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Keeps the soft keyboard hidden for the duration of the step (never uses BACK).</li>
 *   <li>Smooth profile scroll, then waits for the scroll to actually settle.</li>
 *   <li>Locates the X / Skip button (label, view-id, or bottom-left clickable) and clicks it,
 *       retrying a few times while the screen settles.</li>
 *   <li>Last resort: a coordinate tap, which is only counted as success if the on-screen
 *       content really changed afterwards.</li>
 * </ol>
 *
 * <p><b>Honest reporting:</b> {@code success=true} is reported only when a click was really
 * performed. If the X button could not be clicked, {@code success=false} is reported so the
 * LEFT counter is not advanced for a skip that never happened.</p>
 */
public class LeftSwipeExecutor {

    private static final String TAG = "LeftSwipeExecutor";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final long SCROLL_DURATION_MS    = 450;
    /** Scroll stroke + inertia must finish before we read the tree again. */
    private static final long SCROLL_SETTLE_MS      = 750;
    private static final int  FIND_MAX_ATTEMPTS     = 3;
    private static final long FIND_RETRY_MS         = 300;
    private static final long POST_CLICK_SETTLE_MS  = 1200;
    private static final long VERIFY_DELAY_MS       = 1200;

    // ── Where the X lives (fractions of the screen) ───────────────────────────
    private static final float REGION_MAX_X = 0.50f;
    private static final float REGION_MIN_Y = 0.60f;
    private static final float REGION_MAX_Y = 0.92f;   // stay above the bottom tab bar

    /**
     * Blind-tap position, used only when the X cannot be found in the accessibility tree.
     * This is a best guess — calibrate it from the "skip region dump" in logcat (tag
     * LeftSwipeExecutor) if you ever see "fallback tap" in the status line.
     */
    private static final float FALLBACK_X = 0.12f;
    private static final float FALLBACK_Y = 0.88f;

    // ── Matching ──────────────────────────────────────────────────────────────
    private static final int MAX_LABEL_LENGTH = 30; // buttons have short labels; profile prose doesn't

    private static final Pattern SKIP_LABEL =
            Pattern.compile("\\b(skip|pass|remove|dislike|reject|not interested)\\b");

    private static final Set<String> SKIP_GLYPHS =
            new HashSet<>(Arrays.asList("x", "\u2715", "\u2716", "\u00D7", "\u2717", "\u2573"));

    private static final Set<String> SKIP_ID_TOKENS =
            new HashSet<>(Arrays.asList("skip", "pass", "dislike", "reject", "remove", "cross", "x"));

    /** Nodes whose own text / description / view-id contains one of these are never candidates. */
    private static final List<String> EXCLUDE = Arrays.asList("rose", "comment", "tab", "nav");

    public interface CompletionCallback {
        void onLeftSwipeCompleted(boolean success, String statusMessage);
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final int score;
        Candidate(AccessibilityNodeInfo node, int score) { this.node = node; this.score = score; }
    }

    private final AccessibilityService service;
    private final ProfileScrollHelper scrollHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Soft-keyboard show mode to restore once the step ends. */
    private int previousKeyboardMode = AccessibilityService.SHOW_MODE_AUTO;

    public LeftSwipeExecutor(AccessibilityService service) {
        this.service = service;
        this.scrollHelper = new ProfileScrollHelper(service);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Entry point
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Executes the Left Swipe (Rejection) workflow on Hinge.
     * {@code ignoredBounds} is unused; real display metrics are used (same as RightSwipeExecutor).
     */
    public void execute(AccessibilityNodeInfo root, Rect ignoredBounds, CompletionCallback callback) {
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        final int sw = dm.widthPixels;
        final int sh = dm.heightPixels;

        Log.i(TAG, "Executing Left Swipe with screen dimensions: " + sw + "x" + sh);

        suppressKeyboard();
        scrollHelper.scrollDown(sw, sh, SCROLL_DURATION_MS);

        handler.postDelayed(() -> attemptSkip(root, sw, sh, 0, callback), SCROLL_SETTLE_MS);
    }

    private void attemptSkip(AccessibilityNodeInfo root, int sw, int sh, int attempt,
                             CompletionCallback callback) {
        AccessibilityNodeInfo current = getBestRootNode(root);
        Candidate candidate = current != null ? findSkipNode(current, sw, sh) : null;

        if (candidate != null) {
            Log.i(TAG, "Skip target (score " + candidate.score + "): " + describe(candidate.node));

            if (performAccessibilityClick(candidate.node, sw, sh)) {
                Log.i(TAG, "Clicked X / Skip via accessibility action.");
                finish(true, "LEFT \u2713", callback);
                return;
            }

            // Found it, but no clickable node in its chain accepted the click: tap its centre.
            Rect r = new Rect();
            candidate.node.getBoundsInScreen(r);
            if (r.centerX() > 0 && r.centerY() > 0 && !isCoveredByKeyboard(r.centerX(), r.centerY())) {
                Log.i(TAG, "Tapping X / Skip at its on-screen centre.");
                tapThenFinish(r.centerX(), r.centerY(), null, callback);
                return;
            }
        }

        // Not found (or not clickable) yet — the screen may still be settling. Look again shortly.
        if (attempt + 1 < FIND_MAX_ATTEMPTS) {
            handler.postDelayed(() -> attemptSkip(root, sw, sh, attempt + 1, callback), FIND_RETRY_MS);
            return;
        }

        // Last resort. Dump what is in the bottom-left so the real X can be identified.
        if (current != null) {
            dumpSkipRegion(current, sw, sh);
        }

        int x = (int) (sw * FALLBACK_X);
        int y = (int) (sh * FALLBACK_Y);
        if (isCoveredByKeyboard(x, y)) {
            finish(false, "LEFT skipped: X not found (keyboard open)", callback);
            return;
        }

        Log.w(TAG, "X not found in accessibility tree; trying fallback tap at " + x + "," + y);
        tapThenFinish(x, y, current != null ? fingerprint(current) : null, callback);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Completion
    // ══════════════════════════════════════════════════════════════════════════

    private void finish(boolean success, String message, CompletionCallback callback) {
        Runnable report = () -> {
            restoreKeyboard();
            if (success) {
                Log.i(TAG, "Left swipe (Rejection) completed: " + message);
            } else {
                Log.w(TAG, "Left swipe FAILED: " + message);
            }
            if (callback != null) {
                callback.onLeftSwipeCompleted(success, message);
            }
        };
        if (success) {
            handler.postDelayed(report, POST_CLICK_SETTLE_MS); // let Hinge load the next profile
        } else {
            handler.post(report);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Target locators
    // ══════════════════════════════════════════════════════════════════════════

    private Candidate findSkipNode(AccessibilityNodeInfo root, int sw, int sh) {
        Candidate[] best = new Candidate[1];
        scan(root, sw, sh, best);
        return best[0];
    }

    private void scan(AccessibilityNodeInfo node, int sw, int sh, Candidate[] best) {
        if (node == null) return;

        int score = scoreNode(node, sw, sh);
        if (score > 0 && (best[0] == null || score > best[0].score)) {
            best[0] = new Candidate(node, score);
        }

        // Exclusions only disqualify the node itself — never the whole subtree.
        for (int i = 0; i < node.getChildCount(); i++) {
            scan(node.getChild(i), sw, sh, best);
        }
    }

    /** @return 0 if the node is not a plausible X / Skip button, otherwise a rank (higher = better). */
    private int scoreNode(AccessibilityNodeInfo node, int sw, int sh) {
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText")) return 0;

        String t = lower(node.getText());
        String d = lower(node.getContentDescription());
        String v = lower(node.getViewIdResourceName());

        String combined = t + " " + d + " " + v;
        for (String exclude : EXCLUDE) {
            if (combined.contains(exclude)) return 0;
        }

        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.width() <= 0 || b.height() <= 0 || !node.isVisibleToUser()) return 0;

        boolean label      = isSkipLabel(t) || isSkipLabel(d) || hasSkipIdToken(v);
        boolean inRegion   = isInSkipRegion(b, sw, sh);
        boolean buttonLike = isButtonSized(b, sw);
        boolean clickable  = node.isClickable();

        if (label) {
            // A label match must be actionable, or sit where the X lives.
            if (!inRegion && findClickableTarget(node, sw, sh) == null) return 0;
        } else if (!(inRegion && clickable && buttonLike)) {
            return 0; // unlabeled: only accept a button-sized clickable in the X region
        }

        int score = 0;
        if (label)      score += 100;
        if (inRegion)   score += 40;
        if (clickable)  score += 20;
        if (buttonLike) score += 10;
        return score;
    }

    private static boolean isSkipLabel(String s) {
        if (s == null || s.isEmpty()) return false;
        String trimmed = s.trim();
        if (trimmed.length() > MAX_LABEL_LENGTH) return false;
        if (SKIP_GLYPHS.contains(trimmed)) return true;
        return SKIP_LABEL.matcher(trimmed).find();
    }

    /** Matches the resource-entry name by whole token, so "skip_button" hits but "textView" does not. */
    private static boolean hasSkipIdToken(String viewId) {
        if (viewId == null || viewId.isEmpty()) return false;
        int slash = viewId.lastIndexOf('/');
        String entry = slash >= 0 ? viewId.substring(slash + 1) : viewId;
        for (String token : entry.split("[^a-z0-9]+")) {
            if (SKIP_ID_TOKENS.contains(token)) return true;
        }
        return false;
    }

    private static boolean isInSkipRegion(Rect b, int sw, int sh) {
        return b.centerX() < sw * REGION_MAX_X
                && b.centerY() > sh * REGION_MIN_Y
                && b.centerY() < sh * REGION_MAX_Y;
    }

    private boolean isButtonSized(Rect b, int sw) {
        float density = service.getResources().getDisplayMetrics().density;
        int min = (int) (24 * density);
        int max = (int) (sw * 0.30f);
        return b.width() >= min && b.width() <= max && b.height() >= min && b.height() <= max;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clicking
    // ══════════════════════════════════════════════════════════════════════════

    /** First clickable node among {@code node} and its nearest ancestors that is not screen-sized. */
    private AccessibilityNodeInfo findClickableTarget(AccessibilityNodeInfo node, int sw, int sh) {
        AccessibilityNodeInfo n = node;
        for (int hops = 0; n != null && hops < 4; hops++) {
            if (n.isClickable() && n.isEnabled() && isReasonablySized(n, sw, sh)) return n;
            n = n.getParent();
        }
        return null;
    }

    /** Guards against clicking a huge container (e.g. the whole profile card) by mistake. */
    private boolean isReasonablySized(AccessibilityNodeInfo n, int sw, int sh) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        long area = (long) r.width() * r.height();
        return area > 0 && area <= (long) (sw * (double) sh * 0.15);
    }

    private boolean performAccessibilityClick(AccessibilityNodeInfo node, int sw, int sh) {
        AccessibilityNodeInfo target = findClickableTarget(node, sw, sh);
        return target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /**
     * Taps (x, y) and reports the result only when the gesture finishes.
     * If {@code beforeFingerprint} is non-null, success additionally requires the screen content
     * to have changed (used for blind taps, where nothing else proves a profile was skipped).
     */
    private void tapThenFinish(int x, int y, String beforeFingerprint, CompletionCallback callback) {
        boolean dispatched = dispatchTapAt(x, y, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (beforeFingerprint == null) {
                    finish(true, "LEFT \u2713", callback);
                    return;
                }
                handler.postDelayed(() -> {
                    AccessibilityNodeInfo after = getBestRootNode(null);
                    boolean changed = after != null && !beforeFingerprint.equals(fingerprint(after));
                    finish(changed,
                            changed ? "LEFT \u2713 (fallback tap)"
                                    : "LEFT not confirmed: screen unchanged after fallback tap",
                            callback);
                }, VERIFY_DELAY_MS);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                finish(false, "LEFT skipped: tap gesture cancelled", callback);
            }
        });

        if (!dispatched) {
            finish(false, "LEFT skipped: tap gesture could not be dispatched", callback);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Gestures
    // ══════════════════════════════════════════════════════════════════════════

    private boolean dispatchTapAt(int x, int y, AccessibilityService.GestureResultCallback callback) {
        if (x <= 0 || y <= 0) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return service.dispatchGesture(gesture, callback, handler);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Soft-keyboard safety (no BACK key — it can navigate away from the profile / exit Hinge)
    // ══════════════════════════════════════════════════════════════════════════

    private void suppressKeyboard() {
        try {
            AccessibilityService.SoftKeyboardController c = service.getSoftKeyboardController();
            if (c == null) return;
            int prev = c.getShowMode();
            previousKeyboardMode = (prev == AccessibilityService.SHOW_MODE_HIDDEN)
                    ? AccessibilityService.SHOW_MODE_AUTO : prev;
            c.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN);
        } catch (Exception e) {
            Log.w(TAG, "Could not suppress soft keyboard", e);
        }
    }

    private void restoreKeyboard() {
        try {
            AccessibilityService.SoftKeyboardController c = service.getSoftKeyboardController();
            if (c != null) c.setShowMode(previousKeyboardMode);
        } catch (Exception e) {
            Log.w(TAG, "Could not restore soft keyboard", e);
        }
    }

    private boolean isCoveredByKeyboard(int x, int y) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (r.contains(x, y)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Diagnostics / helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Logs everything in the bottom-left half of the screen so the real X can be identified. */
    private void dumpSkipRegion(AccessibilityNodeInfo root, int sw, int sh) {
        Log.w(TAG, "---- skip region dump (X not found) ----");
        int[] budget = {40};
        dumpRecursive(root, sw, sh, budget);
        Log.w(TAG, "---- end skip region dump ----");
    }

    private void dumpRecursive(AccessibilityNodeInfo node, int sw, int sh, int[] budget) {
        if (node == null || budget[0] <= 0) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.width() > 0 && b.height() > 0
                && b.centerX() < sw * REGION_MAX_X && b.centerY() > sh * 0.50f) {
            Log.w(TAG, "  " + describe(node));
            budget[0]--;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpRecursive(node.getChild(i), sw, sh, budget);
        }
    }

    private String describe(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return n.getClassName()
                + " text=\"" + n.getText() + "\""
                + " desc=\"" + n.getContentDescription() + "\""
                + " id=" + n.getViewIdResourceName()
                + " clickable=" + n.isClickable()
                + " bounds=" + b.toShortString();
    }

    /** Cheap "what is on screen" signature: the visible text, in tree order. */
    private String fingerprint(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        return sb.toString();
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null || sb.length() > 2000) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0 && node.isVisibleToUser()) {
            sb.append(text).append('|');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), sb);
        }
    }

    private static String lower(CharSequence cs) {
        return cs != null ? cs.toString().toLowerCase(Locale.ROOT) : "";
    }

    private AccessibilityNodeInfo getBestRootNode(AccessibilityNodeInfo defaultRoot) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null ? root : defaultRoot;
    }
}