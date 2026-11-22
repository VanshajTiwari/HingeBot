package com.example.hingebot;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {
    public static final String BACKEND_BASE_URL = "https://smartdiscovertbackend.vercel.app/api/v1";

    private static final String PREF_NAME = "hingebot_prefs";
    private static final String KEY_RUNNING = "automation_running";
    private static final String KEY_LIKES = "total_likes";
    private static final String KEY_SKIPS = "total_skips";
    private static final String KEY_TOTAL = "total_actions";

    public static boolean isRunning(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_RUNNING, false);
    }

    public static void setRunning(Context context, boolean running) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public static int getLikes(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_LIKES, 0);
    }

    public static int getSkips(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SKIPS, 0);
    }

    public static int getTotal(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_TOTAL, 0);
    }

    public static void incrementStats(Context context, boolean isLike) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int likes = prefs.getInt(KEY_LIKES, 0);
        int skips = prefs.getInt(KEY_SKIPS, 0);
        int total = prefs.getInt(KEY_TOTAL, 0);

        if (isLike) likes++;
        else skips++;
        total++;

        prefs.edit()
                .putInt(KEY_LIKES, likes)
                .putInt(KEY_SKIPS, skips)
                .putInt(KEY_TOTAL, total)
                .apply();
    }

    public static void resetStats(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LIKES, 0)
                .putInt(KEY_SKIPS, 0)
                .putInt(KEY_TOTAL, 0)
                .apply();
    }
}
