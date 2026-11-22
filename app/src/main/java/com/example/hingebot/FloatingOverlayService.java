package com.example.hingebot;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Draggable Floating Overlay Widget Service.
 *
 * <p>Displays a floating pill on top of the screen allowing the user
 * to start, pause, resume, or stop automation directly while on Hinge or any other app.</p>
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "FloatingOverlayService";

    private WindowManager windowManager;
    private View overlayView;
    private TextView tvOverlayIcon;
    private TextView tvOverlayText;
    private WindowManager.LayoutParams params;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HingeAutomationService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                String status = intent.getStringExtra(HingeAutomationService.EXTRA_STATUS);
                int right     = intent.getIntExtra(HingeAutomationService.EXTRA_RIGHT, 0);
                int left      = intent.getIntExtra(HingeAutomationService.EXTRA_LEFT, 0);
                int total     = intent.getIntExtra(HingeAutomationService.EXTRA_TOTAL, 0);
                String stName = intent.getStringExtra(HingeAutomationService.EXTRA_SESSION_STATE);
                SessionState state = SessionState.IDLE;
                try {
                    if (stName != null) state = SessionState.valueOf(stName);
                } catch (IllegalArgumentException ignored) {}

                refreshOverlayUI(status, right, left, total, state);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView   = LayoutInflater.from(this).inflate(R.layout.layout_floating_overlay, null);
        tvOverlayIcon = overlayView.findViewById(R.id.tvOverlayIcon);
        tvOverlayText = overlayView.findViewById(R.id.tvOverlayText);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        setupDragAndClick();

        try {
            windowManager.addView(overlayView, params);
            Log.i(TAG, "Floating overlay window added successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay window", e);
        }

        IntentFilter filter = new IntentFilter(HingeAutomationService.ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }

        // Initial sync
        SessionRepository repo = new SessionRepository(this);
        refreshOverlayUI("Ready", repo.getRightCount(), repo.getLeftCount(), repo.getTotalCount(), repo.loadSessionState());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
        Log.i(TAG, "Floating overlay destroyed.");
    }

    private void setupDragAndClick() {
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float initRawX, initRawY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX    = params.x;
                        initY    = params.y;
                        initRawX = event.getRawX();
                        initRawY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initX + (int) (event.getRawX() - initRawX);
                        params.y = initY + (int) (event.getRawY() - initRawY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - downTime;
                        float dx     = Math.abs(event.getRawX() - initRawX);
                        float dy     = Math.abs(event.getRawY() - initRawY);
                        if (elapsed < 300 && dx < 15 && dy < 15) {
                            onOverlayTapped();
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private void onOverlayTapped() {
        if (!HingeAutomationService.isServiceEnabled()) {
            Toast.makeText(this, "Please enable HingeBot in Accessibility Settings!", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }

        HingeAutomationService svc = HingeAutomationService.getInstance();
        if (svc == null) return;

        if (svc.isAutomationRunning()) {
            svc.pauseAutomation();
            Toast.makeText(this, "Paused", Toast.LENGTH_SHORT).show();
        } else {
            SessionRepository repo = new SessionRepository(this);
            SessionState state = repo.loadSessionState();
            if (state == SessionState.PAUSED) {
                svc.resumeAutomation();
                Toast.makeText(this, "Resumed", Toast.LENGTH_SHORT).show();
            } else {
                svc.startAutomation();
                Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshOverlayUI(String status, int right, int left, int total, SessionState state) {
        if (tvOverlayText == null || tvOverlayIcon == null) return;
        switch (state) {
            case RUNNING:
                tvOverlayIcon.setText("⏸");
                tvOverlayText.setText("Running (R:" + right + " L:" + left + ")");
                break;
            case PAUSED:
                tvOverlayIcon.setText("▶");
                tvOverlayText.setText("Paused (Total: " + total + ")");
                break;
            case IDLE:
            case STOPPED:
            case ERROR:
            default:
                tvOverlayIcon.setText("▶");
                tvOverlayText.setText("Start (Total: " + total + ")");
                break;
        }
    }
}
