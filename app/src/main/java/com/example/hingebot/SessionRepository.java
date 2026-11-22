package com.example.hingebot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences-backed implementation of {@link ISessionRepository}.
 * All operations are idempotent and safe to call from any thread.
 */
public class SessionRepository implements ISessionRepository {

    private static final String PREF_NAME  = "smartdiscovery_session";
    private static final String KEY_STATE  = "session_state";
    private static final String KEY_RIGHT  = "count_right";
    private static final String KEY_LEFT   = "count_left";

    private final SharedPreferences prefs;

    public SessionRepository(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void saveSessionState(SessionState state) {
        prefs.edit().putString(KEY_STATE, state.name()).apply();
    }

    @Override
    public SessionState loadSessionState() {
        String name = prefs.getString(KEY_STATE, SessionState.IDLE.name());
        try {
            return SessionState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SessionState.IDLE;
        }
    }

    @Override
    public void incrementRightCount() {
        prefs.edit().putInt(KEY_RIGHT, getRightCount() + 1).apply();
    }

    @Override
    public void incrementLeftCount() {
        prefs.edit().putInt(KEY_LEFT, getLeftCount() + 1).apply();
    }

    @Override
    public int getRightCount() {
        return prefs.getInt(KEY_RIGHT, 0);
    }

    @Override
    public int getLeftCount() {
        return prefs.getInt(KEY_LEFT, 0);
    }

    @Override
    public int getTotalCount() {
        return getRightCount() + getLeftCount();
    }

    @Override
    public void resetSession() {
        prefs.edit()
                .putString(KEY_STATE, SessionState.IDLE.name())
                .putInt(KEY_RIGHT, 0)
                .putInt(KEY_LEFT, 0)
                .apply();
    }
}
