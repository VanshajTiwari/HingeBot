package com.example.hingebot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java implementation of the HingeBot Backend Client matching the Username/Password system spec.
 */
public class BackendApiClient {

    private static final String TAG = "BackendApiClient";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface ApiCallback {
        void onSuccess(String responseJson);
        void onError(String errorMessage);
    }

    // ── 1. User Login ─────────────────────────────────────────────────────────

    public static void login(Context context, String username, String password, ApiCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("username", username);
                json.put("password", password);

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/users/login", json, false, context, new ApiCallback() {
                    @Override
                    public void onSuccess(String responseJson) {
                        AuthManager auth = new AuthManager(context);
                        auth.setUsername(username);
                        registerDevice(context, null); // Automatically register device after login
                        if (callback != null) callback.onSuccess(responseJson);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (callback != null) callback.onError(errorMessage);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in login", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    // ── User Register ─────────────────────────────────────────────────────────

    public static void register(Context context, String name, String username, String password, ApiCallback callback) {
        registerUser(context, username, password, callback);
    }

    public static void registerUser(Context context, String username, String password, ApiCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("username", username);
                json.put("password", password);

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/users/register", json, false, context, new ApiCallback() {
                    @Override
                    public void onSuccess(String responseJson) {
                        AuthManager auth = new AuthManager(context);
                        auth.setUsername(username);
                        registerDevice(context, null);
                        if (callback != null) callback.onSuccess(responseJson);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (callback != null) callback.onError(errorMessage);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in registerUser", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    // ── 2. Register Device ────────────────────────────────────────────────────

    public static void registerDevice(Context context, ApiCallback callback) {
        executor.execute(() -> {
            try {
                AuthManager auth = new AuthManager(context);
                String deviceId = auth.getDeviceId();
                String username = auth.getUsername();

                JSONObject json = new JSONObject();
                json.put("deviceId", deviceId);
                json.put("username", username != null ? username : "");
                json.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                json.put("model", Build.MODEL);
                json.put("androidVersion", Build.VERSION.RELEASE);
                json.put("appVersion", "1.0.0");

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/devices/register", json, false, context, new ApiCallback() {
                    @Override
                    public void onSuccess(String responseJson) {
                        try {
                            JSONObject root = new JSONObject(responseJson);
                            String token = "";
                            if (root.has("data")) {
                                JSONObject data = root.optJSONObject("data");
                                if (data != null) token = data.optString("apiToken", "");
                            }
                            if (token.isEmpty()) {
                                token = root.optString("apiToken", root.optString("token", "registered"));
                            }
                            auth.setApiToken(token);
                            Log.i(TAG, "Device registered successfully. apiToken saved.");
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing device register response", e);
                        }
                        if (callback != null) callback.onSuccess(responseJson);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        Log.e(TAG, "Device registration failed: " + errorMessage);
                        if (callback != null) callback.onError(errorMessage);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in registerDevice", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    // ── 3. Heartbeat Sync ─────────────────────────────────────────────────────

    public static void sendHeartbeat(Context context, String sessionId, SessionState state,
                                     int rightCount, int leftCount, int totalCount, long durationSeconds) {
        executor.execute(() -> {
            try {
                AuthManager auth = new AuthManager(context);
                JSONObject json = new JSONObject();
                json.put("sessionId", sessionId != null ? sessionId : "sess_" + System.currentTimeMillis());
                json.put("deviceId", auth.getDeviceId());
                json.put("state", state != null ? state.name() : "RUNNING");
                json.put("rightCount", rightCount);
                json.put("leftCount", leftCount);
                json.put("totalCount", totalCount);
                json.put("durationSeconds", durationSeconds);

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/sessions/heartbeat", json, true, context, null);
            } catch (Exception e) {
                Log.e(TAG, "Error sending heartbeat", e);
            }
        });
    }

    // ── 5. Start and Stop Session Hooks ───────────────────────────────────────

    public static void startSession(Context context, String sessionId, ApiCallback callback) {
        executor.execute(() -> {
            try {
                AuthManager auth = new AuthManager(context);
                JSONObject json = new JSONObject();
                json.put("sessionId", sessionId);
                json.put("deviceId", auth.getDeviceId());

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/sessions/start", json, true, context, callback);
            } catch (Exception e) {
                Log.e(TAG, "Error starting session", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    public static void stopSession(Context context, String sessionId, String stopReason, long durationSeconds, ApiCallback callback) {
        executor.execute(() -> {
            try {
                AuthManager auth = new AuthManager(context);
                JSONObject json = new JSONObject();
                json.put("sessionId", sessionId);
                json.put("deviceId", auth.getDeviceId());
                json.put("stopReason", stopReason != null ? stopReason : "MANUAL_STOP");
                json.put("durationSeconds", durationSeconds);

                sendPostRequest(AppConfig.BACKEND_BASE_URL + "/sessions/stop", json, true, context, callback);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping session", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    // ── Helper HTTP Post Request with Headers ─────────────────────────────────

    private static void sendPostRequest(String endpointUrl, JSONObject jsonPayload, boolean requireAuth, Context context, ApiCallback callback) {
        try {
            URL url = new URL(endpointUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");

            if (context != null) {
                AuthManager auth = new AuthManager(context);
                conn.setRequestProperty("x-device-id", auth.getDeviceId());
                if (requireAuth) {
                    String token = auth.getApiToken();
                    if (token != null && !token.isEmpty()) {
                        conn.setRequestProperty("x-api-token", token);
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                    }
                }
            }

            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            byte[] input = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "POST " + endpointUrl + " -> Response Code: " + responseCode);

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line.trim());
                }
            }

            String responseStr = response.toString();
            if (responseCode >= 200 && responseCode < 300) {
                if (callback != null) callback.onSuccess(responseStr);
            } else {
                if (callback != null) callback.onError("HTTP " + responseCode + ": " + responseStr);
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Backend request failed (" + endpointUrl + "): " + e.getMessage());
            if (callback != null) callback.onError(e.getMessage());
        }
    }
}
