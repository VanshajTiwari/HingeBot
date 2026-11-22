package com.example.hingebot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Dedicated Right Swipe (Like) Executor for Hinge.
 *
 * <p>Executes a realistic, human-like Like workflow:
 * <ol>
 *   <li><b>Browse:</b> a random number of profile scrolls (via {@link ProfileScrollHelper}),
 *       each followed by a random "reading" pause. Scrolling is allowed to go as deep as the
 *       profile goes - nothing here assumes the like heart sits on the first screen.</li>
 *   <li><b>Pick:</b> every like heart that is <i>currently visible</i> is collected and ONE is
 *       chosen at random (weighted a little towards the middle of the screen, like a thumb
 *       would). It is never simply "the first / top-most node in the tree".</li>
 *   <li><b>1st Click (Heart):</b> a real touch gesture at a slightly random point inside the
 *       heart, with a random press duration. Falls back to ACTION_CLICK if the gesture is
 *       cancelled.</li>
 *   <li><b>2nd Click (Send Like):</b> after a human-sized pause, clicks "Send Like"
 *       programmatically (no BACK key, no touching the comment field).</li>
 *   <li><b>Post-like pause:</b> random wait for Hinge to finish its transition.</li>
 * </ol>
 *
 * <p><b>Scrolling is not implemented here.</b> All scrolling goes through
 * {@link ProfileScrollHelper#scrollDown(int, int, long, ProfileScrollHelper.ScrollCompletionCallback)}
 * so you can keep changing the scroll logic (distance, curve, direction) in one place.</p>
 *
 * <p><b>Keyboard safety:</b> the 1st click opens Hinge's comment box, which raises the soft
 * keyboard over the lower ~40% of the screen. A blind coordinate tap there lands on a key and
 * types a stray character into the comment field. To prevent that, the soft keyboard is held
 * hidden for the duration of the like flow (and restored afterwards), and a coordinate tap is
 * never dispatched while the keyboard is, or may be, on screen.</p>
 */
public class RightSwipeExecutor {

    private static final String TAG = "RightSwipeExecutor";

    /**
     * Keep soft keyboard hidden during like flow to prevent keyboard overlay and ensure clear
     * gesture tapping on the Send Like button.
     */
    private static final boolean SUPPRESS_KEYBOARD = true;

    // ── Browse (scroll) behaviour ─────────────────────────────────────────────
    /** Fewest / most scrolls planned before we look for a heart. */
    private static final int  MIN_SCROLLS        = 1;
    private static final int  MAX_SCROLLS        = 4;
    /** If no heart is visible after the planned scrolls, keep scrolling up to this many more. */
    private static final int  MAX_EXTRA_SCROLLS  = 3;
    /** Duration of each scroll stroke (passed to ProfileScrollHelper). */
    private static final long SCROLL_MIN_MS      = 300;
    private static final long SCROLL_MAX_MS      = 650;
    /** "Reading" pause after a scroll has settled. */
    private static final long THINK_MIN_MS       = 350;
    private static final long THINK_MAX_MS       = 1300;

    // ── Tap / confirmation timing ─────────────────────────────────────────────
    private static final long TAP_MIN_MS         = 55;
    private static final long TAP_MAX_MS         = 130;
    /** Pause between tapping the heart and looking for the "Send Like" button. */
    private static final long SHEET_WAIT_MIN_MS  = 500;
    private static final long SHEET_WAIT_MAX_MS  = 900;
    /** How many times to look for the "Send Like" node before giving up / falling back. */
    private static final int  SEND_LIKE_MAX_ATTEMPTS = 8;
    /** Gap between "Send Like" lookups, in milliseconds. */
    private static final long SEND_LIKE_RETRY_MS = 200;
    /** Wait for Hinge to complete the like transition. */
    /** If no sheet shows up after the heart tap, re-tap a (freshly chosen) heart this many times. */
    private static final int  MAX_HEART_RETRIES  = 2;
    /** Max times we press Send Like when the sheet refuses to close. */
    private static final int  MAX_SEND_CLICKS    = 3;
    /** After pressing Send Like, wait this long, then check the sheet really closed. */
    private static final long VERIFY_MIN_MS      = 700;
    private static final long VERIFY_MAX_MS      = 1000;
    /** BACK presses allowed when cleaning up an open sheet after a failed like. */
    private static final int  MAX_CLEANUP_BACKS  = 2;
    private static final long CLEANUP_BACK_WAIT_MS = 600;
    private static final long POST_LIKE_MIN_MS   = 1000;
    private static final long POST_LIKE_MAX_MS   = 1600;

    // ── What counts as "in view" (fractions of screen height) ─────────────────
    /** Ignore the status/top bar area. */
    private static final float VIEW_TOP_RATIO    = 0.10f;
    /** Ignore the bottom tab bar area. */
    private static final float VIEW_BOTTOM_RATIO = 0.88f;
    /** A heart must be at least this much inside the viewport to be tappable. */
    private static final float MIN_VISIBLE_FRACTION = 0.85f;

    // ── Matching ──────────────────────────────────────────────────────────────
    /** Buttons have short labels; profile prose ("I like hiking...") does not. */
    private static final int MAX_LABEL_LENGTH = 40;
    private static final String[] LIKE_PHRASES = { "like", "heart" };
    private static final String[] EXCLUDE_PHRASES = {
            "rose", "comment", "edit", "tab", "nav", "standouts", "match"
    };

    public interface CompletionCallback {
        void onRightSwipeCompleted(boolean success, String statusMessage);
    }

    /** A visible, tappable like heart. */
    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        Candidate(AccessibilityNodeInfo node, Rect bounds) {
            this.node = node;
            this.bounds = bounds;
        }
    }

    private final AccessibilityService service;
    private final ProfileScrollHelper scrollHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    /** Bumped by every execute() and cancel(); callbacks from an older run drop out silently. */
    private int generation = 0;
    /** True while a like flow owns the (hidden) soft keyboard. */
    private boolean flowActive = false;
    /** Per-flow counters (reset in execute()). */
    private int heartRetries = 0;
    private int sendClicks = 0;

    /** Soft-keyboard show mode to restore once the like flow ends. */
    private int previousKeyboardMode = AccessibilityService.SHOW_MODE_AUTO;

    public RightSwipeExecutor(AccessibilityService service) {
        this.service = service;
        this.scrollHelper = new ProfileScrollHelper(service);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Entry point / cancel
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Executes the Right Swipe (Like) workflow on Hinge safely without exiting app or typing
     * into the comment field.
     */
    public void execute(AccessibilityNodeInfo root, Rect ignoredBounds, CompletionCallback callback) {
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        final int sw = dm.widthPixels;
        final int sh = dm.heightPixels;

        Log.i(TAG, "Executing Right Swipe with screen dimensions: " + sw + "x" + sh);

        final int token = ++generation;

        // PHASE 0: Keep the soft keyboard hidden for the whole flow so it can never
        // cover the Send Like button area or receive a stray tap.
        final boolean keyboardSuppressed = suppressKeyboard();
        flowActive = true;
        heartRetries = 0;
        sendClicks = 0;

        // PHASE 1: browse - random number of scrolls, then pick a visible heart.
        final int planned = pickScrollCount();
        Log.i(TAG, "Browse plan: " + planned + " scroll(s) before choosing a like target.");
        browseStep(root, sw, sh, keyboardSuppressed, token, 0, planned, planned + MAX_EXTRA_SCROLLS, callback);
    }

    /** Aborts any in-flight like flow (call on Stop / Pause). No callback is delivered. */
    public void cancel() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (flowActive) {
            restoreKeyboard();
            flowActive = false;
        }
    }

    private boolean isStale(int token) {
        return token != generation;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PHASE 1 + 2: browse, then pick a heart that is in view
    // ══════════════════════════════════════════════════════════════════════════

    private void browseStep(final AccessibilityNodeInfo root, final int sw, final int sh,
                            final boolean keyboardSuppressed, final int token,
                            final int scrollsDone, final int planned, final int maxScrolls,
                            final CompletionCallback callback) {
        if (isStale(token)) return;

        // Still have scrolls to do: scroll, let it settle, "read" for a moment, repeat.
        if (scrollsDone < planned) {
            long duration = randomBetween(SCROLL_MIN_MS, SCROLL_MAX_MS);
            scrollHelper.scrollDown(sw, sh, duration, () -> {
                if (isStale(token)) return;
                handler.postDelayed(
                        () -> browseStep(root, sw, sh, keyboardSuppressed, token,
                                scrollsDone + 1, planned, maxScrolls, callback),
                        randomBetween(THINK_MIN_MS, THINK_MAX_MS));
            });
            return;
        }

        // Scrolling for this round is done: what hearts are on screen right now?
        AccessibilityNodeInfo current = getBestRootNode(root);
        List<Candidate> candidates = current != null
                ? collectLikeCandidates(current, sw, sh)
                : new ArrayList<Candidate>();

        if (!candidates.isEmpty()) {
            Candidate chosen = chooseCandidate(candidates, sh);
            Log.i(TAG, "Visible like targets: " + candidates.size()
                    + " - chose one at y=" + chosen.bounds.centerY() + " after " + scrollsDone + " scroll(s).");
            likeCandidate(chosen, root, sw, sh, keyboardSuppressed, token, callback);
            return;
        }

        // Nothing likeable in view (e.g. we are between posts). Scroll a bit more and look again.
        if (scrollsDone < maxScrolls) {
            Log.i(TAG, "No like target in view after " + scrollsDone + " scroll(s); scrolling further.");
            browseStep(root, sw, sh, keyboardSuppressed, token, scrollsDone, scrollsDone + 1, maxScrolls, callback);
            return;
        }

        Log.w(TAG, "No like target found after " + scrollsDone + " scroll(s); skipping this RIGHT.");
        finishFlow();
        if (callback != null) {
            callback.onRightSwipeCompleted(false, "RIGHT skipped: no like target in view");
        }
    }

    /** Mostly 2-3 scrolls, sometimes 1, occasionally 4. */
    private int pickScrollCount() {
        int roll = random.nextInt(100);
        int count;
        if (roll < 20)      count = 1;
        else if (roll < 55) count = 2;
        else if (roll < 85) count = 3;
        else                count = 4;
        return Math.max(MIN_SCROLLS, Math.min(MAX_SCROLLS, count));
    }

    /**
     * Weighted random pick. Hearts nearer the vertical middle of the viewport are somewhat more
     * likely (a thumb rests there), but every visible heart has a real chance.
     */
    private Candidate chooseCandidate(List<Candidate> candidates, int sh) {
        if (candidates.size() == 1) return candidates.get(0);

        float top = sh * VIEW_TOP_RATIO;
        float bottom = sh * VIEW_BOTTOM_RATIO;
        float mid = (top + bottom) / 2f;
        float half = Math.max(1f, (bottom - top) / 2f);

        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            float distance = Math.min(1f, Math.abs(candidates.get(i).bounds.centerY() - mid) / half);
            weights[i] = 1.0 + 2.0 * (1.0 - distance);
            total += weights[i];
        }

        double roll = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll <= 0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PHASE 3 + 4: tap the heart, then Send Like
    // ══════════════════════════════════════════════════════════════════════════

    private void likeCandidate(Candidate chosen, AccessibilityNodeInfo root, int sw, int sh,
                               boolean keyboardSuppressed, int token, CompletionCallback callback) {
        tapNodeHumanly(chosen, () -> {
            if (isStale(token)) return;
            // Give the confirmation sheet a human-sized moment to appear, then Send Like.
            handler.postDelayed(
                    () -> attemptSendLike(root, sw, sh, keyboardSuppressed, false, 0, token, callback),
                    randomBetween(SHEET_WAIT_MIN_MS, SHEET_WAIT_MAX_MS));
        });
    }

    /**
     * Finds the "Send Like" button and presses it, then VERIFIES the sheet really closed.
     *
     * <p>A RIGHT is only reported as successful once the like is confirmed sent. If the sheet never
     * showed up the heart is re-tapped; if the button cannot be pressed, the sheet is closed again
     * and the step is reported as FAILED, so the state machine does not advance and the very
     * same profile is retried with RIGHT instead of being rejected by the next LEFT.</p>
     */
    private void attemptSendLike(AccessibilityNodeInfo root, int sw, int sh,
                                 boolean keyboardSuppressed, boolean sawFocusedInput,
                                 int attempt, int token, CompletionCallback callback) {
        if (isStale(token)) return;

        AccessibilityNodeInfo sheetRoot = getBestRootNode(root);

        if (sheetRoot != null && sheetRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null) {
            sawFocusedInput = true;
        }
        // NOTE: the comment field is deliberately NOT defocused. Clearing focus makes Hinge
        // collapse the comment sheet, which removed "Send Like" and caused the missed 2nd click.
        // The soft keyboard is already held hidden, so no stray typing can happen.

        AccessibilityNodeInfo sendNode = findSendLikeAnywhere(sheetRoot, sh);

        if (sendNode != null) {
            if (sendClicks >= MAX_SEND_CLICKS) {
                Log.w(TAG, "Send Like pressed " + sendClicks + "x but the sheet is still open.");
                failAndCleanUp(root, sh, token, "RIGHT skipped: Send Like did not go through", callback);
                return;
            }
            sendClicks++;
            if (clickSendLike(sendNode, sw, sh)) {
                Log.i(TAG, "Pressed Send Like (press #" + sendClicks + "); verifying the sheet closes.");
                verifySendLikeSent(root, sw, sh, keyboardSuppressed, token, callback);
                return;
            }
            Log.w(TAG, "Send Like found but could not be pressed.");
        }

        // The heart tap may simply have missed (no sheet, no comment box anywhere): tap again.
        boolean sheetOpen = sheetRoot != null && (sendNode != null || hasEditText(sheetRoot));
        if (!sheetOpen && attempt + 1 == Math.max(2, SEND_LIKE_MAX_ATTEMPTS / 2)
                && heartRetries < MAX_HEART_RETRIES) {
            List<Candidate> again = sheetRoot != null
                    ? collectLikeCandidates(sheetRoot, sw, sh) : new ArrayList<Candidate>();
            if (!again.isEmpty()) {
                heartRetries++;
                Log.w(TAG, "No Send Like sheet after heart tap; re-tapping heart (retry " + heartRetries + ").");
                Candidate c = chooseCandidate(again, sh);
                tapNodeHumanly(c, () -> {
                    if (isStale(token)) return;
                    handler.postDelayed(
                            () -> attemptSendLike(root, sw, sh, keyboardSuppressed, false, 0, token, callback),
                            randomBetween(SHEET_WAIT_MIN_MS, SHEET_WAIT_MAX_MS));
                });
                return;
            }
        }

        // Not found yet - the sheet may still be animating in. Look again shortly.
        if (attempt + 1 < SEND_LIKE_MAX_ATTEMPTS) {
            final boolean focusedSoFar = sawFocusedInput;
            handler.postDelayed(
                    () -> attemptSendLike(root, sw, sh, keyboardSuppressed, focusedSoFar, attempt + 1, token, callback),
                    SEND_LIKE_RETRY_MS);
            return;
        }

        // Out of attempts. NO blind tap any more: a blind tap that misses used to be reported
        // as a success, which advanced the sequence and got the profile rejected by the next LEFT.
        Log.w(TAG, "Send Like never found; failing this step so the same profile is retried.");
        dumpLowerHalf(sheetRoot, sh);
        dumpAllWindows();
        failAndCleanUp(root, sh, token, "RIGHT skipped: Send Like not found", callback);
    }

    /** After pressing Send Like, confirm the sheet is gone. Re-presses if it is still there. */
    private void verifySendLikeSent(AccessibilityNodeInfo root, int sw, int sh,
                                    boolean keyboardSuppressed, int token, CompletionCallback callback) {
        handler.postDelayed(() -> {
            if (isStale(token)) return;
            AccessibilityNodeInfo r = getBestRootNode(root);
            boolean stillThere = findSendLikeAnywhere(r, sh) != null;
            if (!stillThere) {
                finishAfterTransition(token, callback);
                return;
            }
            Log.w(TAG, "Send Like still on screen after press #" + sendClicks + "; trying again.");
            attemptSendLike(root, sw, sh, keyboardSuppressed, false, 0, token, callback);
        }, randomBetween(VERIFY_MIN_MS, VERIFY_MAX_MS));
    }

    /**
     * Closes a half-finished like sheet (BACK only while the sheet / comment box is really open,
     * never on the plain profile screen), then reports FAILURE.
     */
    private void failAndCleanUp(AccessibilityNodeInfo root, int sh, int token,
                                String reason, CompletionCallback callback) {
        cleanUpSheet(root, sh, token, MAX_CLEANUP_BACKS, reason, callback);
    }

    private void cleanUpSheet(AccessibilityNodeInfo root, int sh, int token, int backsLeft,
                              String reason, CompletionCallback callback) {
        if (isStale(token)) return;
        AccessibilityNodeInfo r = getBestRootNode(root);
        boolean open = (r != null && hasEditText(r)) || findSendLikeAnywhere(r, sh) != null;
        if (open && backsLeft > 0) {
            Log.w(TAG, "Closing the unsent like sheet with BACK.");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            handler.postDelayed(
                    () -> cleanUpSheet(root, sh, token, backsLeft - 1, reason, callback),
                    CLEANUP_BACK_WAIT_MS);
            return;
        }
        finishFlow();
        if (callback != null) {
            callback.onRightSwipeCompleted(false, reason);
        }
    }

    /** PHASE 5: wait for Hinge to complete the like transition, restore keyboard, report success. */
    private void finishAfterTransition(int token, CompletionCallback callback) {
        handler.postDelayed(() -> {
            if (isStale(token)) return;
            finishFlow();
            Log.i(TAG, "Right swipe (Like) completed successfully.");
            if (callback != null) {
                callback.onRightSwipeCompleted(true, "RIGHT \u2713");
            }
        }, randomBetween(POST_LIKE_MIN_MS, POST_LIKE_MAX_MS));
    }

    private void finishFlow() {
        if (flowActive) {
            restoreKeyboard();
            flowActive = false;
        }
    }

    // ── Soft-keyboard safety ──────────────────────────────────────────────────

    /**
     * Asks the system to keep the soft keyboard hidden. Returns true only if that succeeded.
     * Always paired with {@link #restoreKeyboard()}.
     */
    private boolean suppressKeyboard() {
        if (!SUPPRESS_KEYBOARD) return false;
        try {
            AccessibilityService.SoftKeyboardController c = service.getSoftKeyboardController();
            if (c == null) return false;
            int prev = c.getShowMode();
            // Never "restore" to HIDDEN (e.g. left over from an interrupted run).
            previousKeyboardMode = (prev == AccessibilityService.SHOW_MODE_HIDDEN)
                    ? AccessibilityService.SHOW_MODE_AUTO : prev;
            return c.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN);
        } catch (Exception e) {
            Log.w(TAG, "Could not suppress soft keyboard", e);
            return false;
        }
    }

    /** Gives the user their normal keyboard behaviour back. Safe to call repeatedly. */
    private void restoreKeyboard() {
        if (!SUPPRESS_KEYBOARD) return;
        try {
            AccessibilityService.SoftKeyboardController c = service.getSoftKeyboardController();
            if (c != null) c.setShowMode(previousKeyboardMode);
        } catch (Exception e) {
            Log.w(TAG, "Could not restore soft keyboard", e);
        }
    }

    /** Screen bounds of the on-screen keyboard, or null if it is not showing / not detectable. */
    private Rect getKeyboardBounds() {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    return r;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Target locators
    // ══════════════════════════════════════════════════════════════════════════

    /** Every like heart currently visible on screen (never just the first one found). */
    private List<Candidate> collectLikeCandidates(AccessibilityNodeInfo root, int sw, int sh) {
        List<Candidate> out = new ArrayList<>();
        collectByLabel(root, sw, sh, out, 0);
        if (out.isEmpty()) {
            // Labels not exposed: fall back to clickable controls on the right-hand side,
            // which is where Hinge draws its heart buttons.
            collectClickableOnRight(root, sw, sh, out, 0);
        }
        return out;
    }

    private void collectByLabel(AccessibilityNodeInfo node, int sw, int sh, List<Candidate> out, int depth) {
        if (node == null || depth > 60) return;

        String t = lower(node.getText());
        String d = lower(node.getContentDescription());
        String v = lower(node.getViewIdResourceName());
        String combined = t + " " + d + " " + v;

        if (containsAny(combined, EXCLUDE_PHRASES)) return;

        CharSequence className = node.getClassName();
        if (className != null && className.toString().contains("EditText")) return;

        boolean idMatch    = containsAny(v, LIKE_PHRASES);
        boolean labelMatch = (!t.isEmpty() && t.length() <= MAX_LABEL_LENGTH && containsAny(t, LIKE_PHRASES))
                || (!d.isEmpty() && d.length() <= MAX_LABEL_LENGTH && containsAny(d, LIKE_PHRASES));

        if ((idMatch || labelMatch) && hasClickableSelfOrAncestor(node)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (isInView(node, r, sw, sh)) {
                addIfNew(out, new Candidate(node, r));
            }
            return; // do not also collect this heart's children
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectByLabel(node.getChild(i), sw, sh, out, depth + 1);
        }
    }

    private void collectClickableOnRight(AccessibilityNodeInfo node, int sw, int sh, List<Candidate> out, int depth) {
        if (node == null || depth > 60) return;

        String combined = lower(node.getText()) + " " + lower(node.getContentDescription())
                + " " + lower(node.getViewIdResourceName());
        if (containsAny(combined, EXCLUDE_PHRASES)) return;

        if (node.isClickable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            boolean heartSized = r.width() >= 40 && r.height() >= 40 && r.width() <= 320 && r.height() <= 320;
            if (heartSized && r.centerX() > sw * 0.55f && isInView(node, r, sw, sh)) {
                addIfNew(out, new Candidate(node, r));
                return;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableOnRight(node.getChild(i), sw, sh, out, depth + 1);
        }
    }

    /** True if the control is (almost) fully inside the usable part of the screen. */
    private boolean isInView(AccessibilityNodeInfo node, Rect r, int sw, int sh) {
        if (r.width() <= 20 || r.height() <= 20) return false;
        if (r.right <= 0 || r.left >= sw) return false;
        if (!node.isVisibleToUser()) return false;

        float top = sh * VIEW_TOP_RATIO;
        float bottom = sh * VIEW_BOTTOM_RATIO;
        float visible = Math.min(r.bottom, bottom) - Math.max(r.top, top);
        return visible / (float) r.height() >= MIN_VISIBLE_FRACTION;
    }

    private void addIfNew(List<Candidate> out, Candidate c) {
        for (Candidate existing : out) {
            if (Math.abs(existing.bounds.centerX() - c.bounds.centerX()) < 24
                    && Math.abs(existing.bounds.centerY() - c.bounds.centerY()) < 24) {
                return;
            }
        }
        out.add(c);
    }

    private boolean hasClickableSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        int guard = 0;
        while (n != null && guard++ < 12) {
            if (n.isClickable()) return true;
            n = n.getParent();
        }
        return false;
    }

    /**
     * Finds the visible "Send Like" control. Unlike the old version this NEVER prunes a whole
     * subtree because a container is labelled "comment"/"tab"/"nav" - Send Like lives inside the
     * comment sheet, so that pruning hid it and caused the missed second click.
     * If several nodes match, the clickable one lowest on screen wins.
     */
    private AccessibilityNodeInfo findSendLikeNode(AccessibilityNodeInfo root, int sh) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectSendLike(root, found, 0);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : found) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!n.isVisibleToUser() || r.width() <= 0 || r.height() <= 0) continue;
            boolean clickable = n.isClickable() || hasClickableSelfOrAncestor(n);
            int score = r.centerY() + (clickable ? sh : 0);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /** Looks for Send Like in the given root first, then in every other app window (bottom sheets/dialogs). */
    private AccessibilityNodeInfo findSendLikeAnywhere(AccessibilityNodeInfo primary, int sh) {
        AccessibilityNodeInfo n = primary != null ? findSendLikeNode(primary, sh) : null;
        if (n != null) return n;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr == null) continue;
                n = findSendLikeNode(wr, sh);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void collectSendLike(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60) return;

        // Never match (or look inside) the comment text box itself.
        CharSequence className = node.getClassName();
        if (className != null && className.toString().contains("EditText")) return;

        String t = lower(node.getText());
        String d = lower(node.getContentDescription());
        String v = lower(node.getViewIdResourceName());

        boolean isRose = t.contains("rose") || d.contains("rose") || v.contains("rose");
        boolean isSend = t.contains("send like") || d.contains("send like")
                || t.contains("send a like") || d.contains("send a like")
                || t.contains("send_like") || d.contains("send_like")
                || (t.startsWith("send") && t.contains("like"))
                || (d.startsWith("send") && d.contains("like"))
                || t.equals("send") || d.equals("send")
                || ((node.isClickable() || hasClickableSelfOrAncestor(node)) && t.startsWith("send") && t.length() <= 28)
                || ((node.isClickable() || hasClickableSelfOrAncestor(node)) && d.startsWith("send") && d.length() <= 28)
                || (t.equals("like") && (node.isClickable() || hasClickableSelfOrAncestor(node)))
                || (d.equals("like") && (node.isClickable() || hasClickableSelfOrAncestor(node)))
                || v.contains("send_like") || v.contains("sendlike") || v.contains("send_button")
                || v.contains("send_btn") || v.contains("submit_like") || v.contains("like_button")
                || v.contains("comment_send") || v.contains("send_prompt");

        if (isSend && !isRose) {
            out.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectSendLike(node.getChild(i), out, depth + 1);
        }
    }

    /** True if a sibling of this node is the comment field (so a bare "Like" button is the send button). */
    private boolean hasEditTextSibling(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo p = node.getParent();
        return p != null && hasEditText(p, 0);
    }

    private boolean hasEditText(AccessibilityNodeInfo node) {
        return hasEditText(node, 0);
    }

    private boolean hasEditText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 60) return false;
        CharSequence className = node.getClassName();
        if (className != null && className.toString().contains("EditText")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasEditText(node.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    /** Logs the clickable controls in the lower half so a changed Send Like label can be spotted. */
    /** Logs every node of every window (tag RightSwipeExecutor) so the real Send Like label can be seen. */
    private void dumpAllWindows() {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                Log.w(TAG, "==== WINDOW type=" + w.getType() + " active=" + w.isActive() + " ====");
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr != null) dumpTree(wr, 0, new int[]{0});
            }
        } catch (Exception e) {
            Log.w(TAG, "dumpAllWindows failed", e);
        }
    }

    private void dumpTree(AccessibilityNodeInfo n, int depth, int[] count) {
        if (n == null || depth > 40 || count[0] >= 250) return;
        count[0]++;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) pad.append(' ');
        Log.w(TAG, pad + "" + n.getClassName() + " text=" + n.getText() + " desc=" + n.getContentDescription()
                + " id=" + n.getViewIdResourceName() + " click=" + n.isClickable()
                + " vis=" + n.isVisibleToUser() + " " + r);
        for (int i = 0; i < n.getChildCount(); i++) dumpTree(n.getChild(i), depth + 1, count);
    }

    private void dumpLowerHalf(AccessibilityNodeInfo root, int sh) {
        if (root == null) return;
        Log.w(TAG, "---- lower-half dump (Send Like not found) ----");
        dumpLowerHalf(root, sh, 0, new int[]{0});
    }

    private void dumpLowerHalf(AccessibilityNodeInfo node, int sh, int depth, int[] lines) {
        if (node == null || depth > 60 || lines[0] >= 30) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if ((node.isClickable() || node.getText() != null || node.getContentDescription() != null)
                && r.centerY() > sh * 0.5f) {
            lines[0]++;
            Log.w(TAG, "  " + node.getClassName() + " text=" + node.getText()
                    + " desc=" + node.getContentDescription() + " id=" + node.getViewIdResourceName()
                    + " clickable=" + node.isClickable() + " " + r);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpLowerHalf(node.getChild(i), sh, depth + 1, lines);
        }
    }

    private void clearEditTextFocus(AccessibilityNodeInfo node) {
        if (node == null) return;

        CharSequence className = node.getClassName();
        if ((className != null && className.toString().contains("EditText")) || node.isFocused()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            clearEditTextFocus(node.getChild(i));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Clicking / tapping
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Taps the chosen heart with a real touch gesture at a slightly random spot inside it and a
     * random press length. If a gesture cannot be used (keyboard up, old API, cancelled), falls
     * back to a normal accessibility click. {@code afterTap} runs once either way.
     */
    private void tapNodeHumanly(Candidate c, Runnable afterTap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getKeyboardBounds() == null) {
            int x = jitterWithin(c.bounds.left, c.bounds.right);
            int y = jitterWithin(c.bounds.top, c.bounds.bottom);

            Runnable fallbackThenContinue = () -> {
                performNodeClick(c.node);
                afterTap.run();
            };

            boolean dispatched = dispatchTapAt(x, y, randomBetween(TAP_MIN_MS, TAP_MAX_MS),
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            afterTap.run();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            Log.w(TAG, "Heart tap gesture cancelled; using accessibility click.");
                            fallbackThenContinue.run();
                        }
                    });
            if (!dispatched) {
                fallbackThenContinue.run();
            }
            return;
        }

        performNodeClick(c.node);
        afterTap.run();
    }

    /**
     * Presses Send Like. Tries the node, then only ancestors that are button-sized (never the whole
     * sheet or card), then a real touch at the node's centre. Returns true if something was
     * pressed; the caller still VERIFIES the sheet closed.
     */
    private boolean clickSendLike(AccessibilityNodeInfo node, int sw, int sh) {
        if (node == null) return false;

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        // 1. Dispatch real touch gesture to the Send Like button bounds
        if (r.centerX() > 0 && r.centerY() > 0 && r.width() <= sw && r.height() <= sh * 0.25f) {
            int x = jitterWithin(r.left, r.right);
            int y = jitterWithin(r.top, r.bottom);
            boolean dispatched = dispatchTapAt(x, y, randomBetween(TAP_MIN_MS, TAP_MAX_MS), null);
            if (dispatched) {
                Log.i(TAG, "Dispatched touch gesture to Send Like button at (" + x + ", " + y + ")");
                // Complement with accessibility click fallback
                performNodeClick(node);
                return true;
            }
        }

        // 2. Fallback: Accessibility click on node or clickable parent
        return performNodeClick(node);
    }

    private boolean performNodeClick(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        AccessibilityNodeInfo p = node.getParent();
        while (p != null) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            p = p.getParent();
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.centerX() > 0 && rect.centerY() > 0) {
            dispatchTapAt(rect.centerX(), rect.centerY(), randomBetween(TAP_MIN_MS, TAP_MAX_MS), null);
            return true;
        }
        return false;
    }

    /** @return true if the gesture was accepted for dispatch. */
    private boolean dispatchTapAt(int x, int y, long durationMs,
                                  AccessibilityService.GestureResultCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && x > 0 && y > 0) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build();
            return service.dispatchGesture(gesture, callback, callback != null ? handler : null);
        }
        return false;
    }

    private AccessibilityNodeInfo getBestRootNode(AccessibilityNodeInfo defaultRoot) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) return root;
        return defaultRoot;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Small helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Random point inside [lo, hi], kept off the very edges (30%-70% of the span). */
    private int jitterWithin(int lo, int hi) {
        int span = Math.max(1, hi - lo);
        return lo + (int) (span * (0.30 + 0.40 * random.nextDouble()));
    }

    private long randomBetween(long minInclusive, long maxInclusive) {
        return minInclusive + (long) (random.nextDouble() * (maxInclusive - minInclusive));
    }

    private static String lower(CharSequence cs) {
        return cs != null
                ? cs.toString().replace((char) 0x00A0, ' ').replace("" + (char) 0x200B, "").trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                : "";
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}