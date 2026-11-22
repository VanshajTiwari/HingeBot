package com.example.hingebot;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Manages user authentication, username, apiToken, and deviceId in HingeBotPrefs.
 */
public class AuthManager {

    private static final String PREF_NAME = "HingeBotPrefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_API_TOKEN = "api_token";

    private final SharedPreferences prefs;
    private final Context context;

    public AuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getDeviceId() {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id != null && !id.isEmpty() ? id : "3a7b9c1d8e5f2a4b";
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getApiToken() {
        return prefs.getString(KEY_API_TOKEN, "");
    }

    public void setApiToken(String token) {
        prefs.edit().putString(KEY_API_TOKEN, token).apply();
    }

    public void saveAuth(String token, String email, String name) {
        setApiToken(token);
        setUsername(email);
    }

    public boolean isLoggedIn() {
        return getUsername() != null && !getUsername().isEmpty();
    }

    public void logout() {
        prefs.edit().clear().apply();
    }
}
