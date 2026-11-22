package com.example.hingebot;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * MVVM ViewModel for the SmartDiscovery main screen.
 *
 * <p>Bridges the UI with the {@link HingeAutomationService} via LiveData.
 * The UI observes these LiveData fields and never touches automation logic directly.</p>
 */
public class SessionViewModel extends AndroidViewModel {

    // ── LiveData observed by the UI ───────────────────────────────────────────

    private final MutableLiveData<SessionState> sessionState =
            new MutableLiveData<>(SessionState.IDLE);

    private final MutableLiveData<Integer> rightCount  = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> leftCount   = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> totalCount  = new MutableLiveData<>(0);
    private final MutableLiveData<String>  statusText  = new MutableLiveData<>("Idle");
    private final MutableLiveData<List<SessionEvent>> eventHistory =
            new MutableLiveData<>();
    private final MutableLiveData<Long> sessionDurationSeconds = new MutableLiveData<>(0L);

    // ── Accessibility service availability ────────────────────────────────────

    private final MutableLiveData<Boolean> accessibilityEnabled =
            new MutableLiveData<>(false);

    // ── Timer for session duration ────────────────────────────────────────────
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long startTimeMillis = 0L;
    private long pausedDurationMillis = 0L;
    private Runnable durationRunnable;

    public SessionViewModel(@NonNull Application application) {
        super(application);
        // Load initial counts from repository if any
        SessionRepository repo = new SessionRepository(application);
        rightCount.setValue(repo.getRightCount());
        leftCount.setValue(repo.getLeftCount());
        totalCount.setValue(repo.getTotalCount());
        sessionState.setValue(repo.loadSessionState());
    }

    // ── Exposed LiveData ──────────────────────────────────────────────────────

    public LiveData<SessionState> getSessionState()       { return sessionState; }
    public LiveData<Integer>      getRightCount()          { return rightCount; }
    public LiveData<Integer>      getLeftCount()           { return leftCount; }
    public LiveData<Integer>      getTotalCount()          { return totalCount; }
    public LiveData<String>       getStatusText()          { return statusText; }
    public LiveData<List<SessionEvent>> getEventHistory()  { return eventHistory; }
    public LiveData<Boolean>      isAccessibilityEnabled() { return accessibilityEnabled; }
    public LiveData<Long>         getSessionDuration()     { return sessionDurationSeconds; }

    // ── Update methods called by HingeAutomationService via broadcast ─────────

    public void onStatusUpdate(String status, int right, int left, int total, SessionState state) {
        sessionState.postValue(state);
        rightCount.postValue(right);
        leftCount.postValue(left);
        totalCount.postValue(total);
        statusText.postValue(status != null ? status : "Idle");

        if (state == SessionState.RUNNING) {
            startTimerIfNeeded();
        } else if (state == SessionState.PAUSED) {
            pauseTimer();
        } else if (state == SessionState.STOPPED || state == SessionState.IDLE) {
            stopTimer();
        }
    }

    public void onAccessibilityStateChanged(boolean enabled) {
        accessibilityEnabled.postValue(enabled);
    }

    public void onEventHistoryUpdated(List<SessionEvent> events) {
        eventHistory.postValue(events);
    }

    // ── Timer management ──────────────────────────────────────────────────────

    private void startTimerIfNeeded() {
        if (durationRunnable != null) return;
        if (startTimeMillis == 0L) {
            startTimeMillis = System.currentTimeMillis();
        } else if (pausedDurationMillis > 0L) {
            startTimeMillis += (System.currentTimeMillis() - pausedDurationMillis);
            pausedDurationMillis = 0L;
        }

        durationRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000L;
                sessionDurationSeconds.postValue(Math.max(0L, elapsed));
                timerHandler.postDelayed(this, 1000L);
            }
        };
        timerHandler.post(durationRunnable);
    }

    private void pauseTimer() {
        if (durationRunnable != null) {
            timerHandler.removeCallbacks(durationRunnable);
            durationRunnable = null;
            pausedDurationMillis = System.currentTimeMillis();
        }
    }

    private void stopTimer() {
        if (durationRunnable != null) {
            timerHandler.removeCallbacks(durationRunnable);
            durationRunnable = null;
        }
        startTimeMillis = 0L;
        pausedDurationMillis = 0L;
        sessionDurationSeconds.postValue(0L);
    }

    // ── Control actions ───────────────────────────────────────────────────────

    public void requestStart() {
        HingeAutomationService service = HingeAutomationService.getInstance();
        if (service != null) {
            service.startAutomation();
        } else {
            statusText.setValue("Error: Accessibility service not enabled");
        }
    }

    public void requestPause() {
        HingeAutomationService service = HingeAutomationService.getInstance();
        if (service != null) {
            service.pauseAutomation();
        }
    }

    public void requestResume() {
        HingeAutomationService service = HingeAutomationService.getInstance();
        if (service != null) {
            service.resumeAutomation();
        }
    }

    public void requestStop(String reason) {
        HingeAutomationService service = HingeAutomationService.getInstance();
        if (service != null) {
            service.stopAutomation(reason);
        } else {
            sessionState.setValue(SessionState.STOPPED);
            statusText.setValue("Stopped: " + reason);
            stopTimer();
        }
    }

    public void requestReset() {
        requestStop("Reset");
        SessionRepository repo = new SessionRepository(getApplication());
        repo.resetSession();
        rightCount.setValue(0);
        leftCount.setValue(0);
        totalCount.setValue(0);
        sessionState.setValue(SessionState.IDLE);
        statusText.setValue("Idle");
        stopTimer();
    }
}
