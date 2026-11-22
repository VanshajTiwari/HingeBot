package com.example.hingebot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import java.util.Locale;

/**
 * HingeBot Main Screen & Automation Controller Dashboard.
 *
 * <p>Observes {@link SessionViewModel} and manages system setup, overlay widget toggles,
 * statistics display, and explicit automation controls.</p>
 */
public class MainActivity extends AppCompatActivity {

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView tvServiceStatus;
    private TextView tvOverlayStatus;
    private TextView tvSessionState;
    private TextView tvRightCount;
    private TextView tvLeftCount;
    private TextView tvTotalCount;
    private TextView tvSessionDuration;
    private TextView tvStatsReason;
    private View     dotService;
    private View     dotOverlay;

    // Controls
    private Button   btnStart;
    private Button   btnPause;
    private Button   btnResume;
    private Button   btnStop;
    private Button   btnReset;
    private Button   btnShareSession;
    private Button   btnEnableAccessibility;
    private Button   btnEnableOverlay;
    private Button   btnToggleOverlayWidget;

    private boolean isOverlayServiceRunning = false;

    // ── ViewModel ─────────────────────────────────────────────────────────────
    private SessionViewModel viewModel;

    // ── Broadcast receiver for status updates ─────────────────────────────────
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HingeAutomationService.ACTION_STATUS_UPDATE.equals(intent.getAction())) return;

            String status = intent.getStringExtra(HingeAutomationService.EXTRA_STATUS);
            int right     = intent.getIntExtra(HingeAutomationService.EXTRA_RIGHT, 0);
            int left      = intent.getIntExtra(HingeAutomationService.EXTRA_LEFT,  0);
            int total     = intent.getIntExtra(HingeAutomationService.EXTRA_TOTAL, 0);
            String stName = intent.getStringExtra(HingeAutomationService.EXTRA_SESSION_STATE);
            SessionState st = SessionState.IDLE;
            try {
                if (stName != null) st = SessionState.valueOf(stName);
            } catch (IllegalArgumentException ignored) {}

            viewModel.onStatusUpdate(status, right, left, total, st);
        }
    };

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(SessionViewModel.class);

        bindViews();
        setupButtons();
        observeViewModel();

        // Checklist Item 2: Device Registration on App Launch
        BackendApiClient.registerDevice(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatuses();
        viewModel.onAccessibilityStateChanged(isAccessibilityServiceEnabled());

        IntentFilter filter = new IntentFilter(HingeAutomationService.ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void bindViews() {
        tvServiceStatus        = findViewById(R.id.tvServiceStatus);
        tvOverlayStatus        = findViewById(R.id.tvOverlayStatus);
        tvSessionState         = findViewById(R.id.tvSessionState);
        tvRightCount           = findViewById(R.id.tvRightCount);
        tvLeftCount            = findViewById(R.id.tvLeftCount);
        tvTotalCount           = findViewById(R.id.tvTotalCount);
        tvSessionDuration      = findViewById(R.id.tvSessionDuration);
        tvStatsReason          = findViewById(R.id.tvStatsReason);
        dotService             = findViewById(R.id.dotService);
        dotOverlay             = findViewById(R.id.dotOverlay);

        btnStart               = findViewById(R.id.btnStart);
        btnPause               = findViewById(R.id.btnPause);
        btnResume              = findViewById(R.id.btnResume);
        btnStop                = findViewById(R.id.btnStop);
        btnReset               = findViewById(R.id.btnReset);
        btnShareSession        = findViewById(R.id.btnShareSession);

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        btnEnableOverlay       = findViewById(R.id.btnEnableOverlay);
        btnToggleOverlayWidget = findViewById(R.id.btnToggleOverlayWidget);
    }

    private void setupButtons() {
        // 1. Accessibility Settings
        btnEnableAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // 2. Overlay Permission Settings
        btnEnableOverlay.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        // 3. Floating Overlay Toggle
        btnToggleOverlayWidget.setOnClickListener(v -> toggleOverlayWidget());

        // Automation Control Actions
        btnStart.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Please enable HingeBot in Accessibility Settings!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            viewModel.requestStart();
            Toast.makeText(this, "Automation Started", Toast.LENGTH_SHORT).show();
        });

        btnPause.setOnClickListener(v -> {
            viewModel.requestPause();
            Toast.makeText(this, "Automation Paused", Toast.LENGTH_SHORT).show();
        });

        btnResume.setOnClickListener(v -> {
            viewModel.requestResume();
            Toast.makeText(this, "Automation Resumed", Toast.LENGTH_SHORT).show();
        });

        btnStop.setOnClickListener(v -> {
            viewModel.requestStop("User stopped session");
            Toast.makeText(this, "Automation Stopped", Toast.LENGTH_SHORT).show();
        });

        btnReset.setOnClickListener(v -> {
            viewModel.requestReset();
            Toast.makeText(this, "Session Reset", Toast.LENGTH_SHORT).show();
        });

        btnShareSession.setOnClickListener(v -> shareSessionReport());
    }

    private void shareSessionReport() {
        int right = viewModel.getRightCount().getValue() != null ? viewModel.getRightCount().getValue() : 0;
        int left  = viewModel.getLeftCount().getValue()  != null ? viewModel.getLeftCount().getValue()  : 0;
        int total = viewModel.getTotalCount().getValue() != null ? viewModel.getTotalCount().getValue() : 0;
        long durationSec = viewModel.getSessionDuration().getValue() != null ? viewModel.getSessionDuration().getValue() : 0;
        long mins = durationSec / 60;
        long secs = durationSec % 60;

        SessionState state = viewModel.getSessionState().getValue() != null
                ? viewModel.getSessionState().getValue() : SessionState.IDLE;
        String status = viewModel.getStatusText().getValue() != null
                ? viewModel.getStatusText().getValue() : "Idle";

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        String timestamp = sdf.format(new java.util.Date());

        StringBuilder report = new StringBuilder();
        report.append("🤖 HingeBot Session Report\n");
        report.append("═════════════════════════\n");
        report.append("📅 Date: ").append(timestamp).append("\n");
        report.append("⏱ Session State: ").append(state.name()).append("\n");
        report.append("⏳ Duration: ").append(String.format(Locale.ROOT, "%02d:%02d", mins, secs)).append("\n");
        report.append("ℹ️ Status: ").append(status).append("\n\n");
        report.append("📊 Swipe Statistics:\n");
        report.append("• ❤️ Likes (Right): ").append(right).append(" / ").append(SequenceConfig.MAX_RIGHT_ACTIONS).append("\n");
        report.append("• ❌ Passes (Left): ").append(left).append("\n");
        report.append("• 🔄 Total Swipes: ").append(total).append(" / ").append(SequenceConfig.MAX_TOTAL_ACTIONS).append("\n\n");
        report.append("Generated by HingeBot App");

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "HingeBot Session Report");
        shareIntent.putExtra(Intent.EXTRA_TEXT, report.toString());

        startActivity(Intent.createChooser(shareIntent, "Share Session Report via"));
    }

    private void observeViewModel() {
        viewModel.getRightCount().observe(this, right -> refreshCounts());
        viewModel.getLeftCount().observe(this, left -> refreshCounts());
        viewModel.getTotalCount().observe(this, total -> refreshCounts());

        viewModel.getStatusText().observe(this, status ->
                tvStatsReason.setText("Status: " + status));

        viewModel.getSessionState().observe(this, state -> {
            tvSessionState.setText("Session State: " + state.name());
            updateButtonStates(state);
        });

        viewModel.getSessionDuration().observe(this, seconds -> {
            long mins = seconds / 60;
            long secs = seconds % 60;
            tvSessionDuration.setText(String.format(Locale.ROOT, "Session Duration: %02d:%02d", mins, secs));
        });

        viewModel.isAccessibilityEnabled().observe(this, enabled -> {
            if (enabled) {
                tvServiceStatus.setText("Accessibility Service: ENABLED");
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
                tintDot(dotService, R.color.status_green);
            } else {
                tvServiceStatus.setText("Accessibility Service: DISABLED");
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red));
                tintDot(dotService, R.color.status_red);
            }
        });
    }

    private void refreshCounts() {
        int right = viewModel.getRightCount().getValue()  != null
                ? viewModel.getRightCount().getValue() : 0;
        int left  = viewModel.getLeftCount().getValue()   != null
                ? viewModel.getLeftCount().getValue()  : 0;
        int total = viewModel.getTotalCount().getValue()  != null
                ? viewModel.getTotalCount().getValue() : 0;

        tvRightCount.setText(right + " / " + SequenceConfig.MAX_RIGHT_ACTIONS);
        tvLeftCount.setText(String.valueOf(left));
        tvTotalCount.setText(total + " / " + SequenceConfig.MAX_TOTAL_ACTIONS);
    }

    private void updateButtonStates(SessionState state) {
        switch (state) {
            case RUNNING:
                btnStart.setEnabled(false);
                btnPause.setEnabled(true);
                btnResume.setEnabled(false);
                btnStop.setEnabled(true);
                break;
            case PAUSED:
                btnStart.setEnabled(false);
                btnPause.setEnabled(false);
                btnResume.setEnabled(true);
                btnStop.setEnabled(true);
                break;
            case IDLE:
            case STOPPED:
            case ERROR:
            default:
                btnStart.setEnabled(true);
                btnPause.setEnabled(false);
                btnResume.setEnabled(false);
                btnStop.setEnabled(false);
                break;
        }
    }

    // ── Floating Overlay Widget toggle ────────────────────────────────────────

    private void toggleOverlayWidget() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }

        Intent intent = new Intent(this, FloatingOverlayService.class);
        if (!isOverlayServiceRunning) {
            try {
                startService(intent);
                isOverlayServiceRunning = true;
                btnToggleOverlayWidget.setText("3. Hide Floating Widget");
                Toast.makeText(this, "Floating Overlay Widget Active", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error starting overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            stopService(intent);
            isOverlayServiceRunning = false;
            btnToggleOverlayWidget.setText("3. Show Floating Widget on Screen");
            Toast.makeText(this, "Floating Overlay Widget Hidden", Toast.LENGTH_SHORT).show();
        }
    }

    /** Colours the small status dot to match its status text (UI only). */
    private void tintDot(View dot, int colorRes) {
        if (dot == null) return;
        ViewCompat.setBackgroundTintList(dot,
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    // ── Permission status helpers ─────────────────────────────────────────────

    private boolean isAccessibilityServiceEnabled() {
        if (HingeAutomationService.isServiceEnabled()) return true;

        String serviceName = getPackageName() + "/" + HingeAutomationService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices != null) {
            return enabledServices.contains(serviceName) || enabledServices.contains(getPackageName());
        }
        return false;
    }

    private void updatePermissionStatuses() {
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        if (serviceEnabled) {
            tvServiceStatus.setText("Accessibility Service: ENABLED");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
            tintDot(dotService, R.color.status_green);
        } else {
            tvServiceStatus.setText("Accessibility Service: DISABLED");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red));
            tintDot(dotService, R.color.status_red);
        }

        boolean overlayGranted = Settings.canDrawOverlays(this);
        if (overlayGranted) {
            tvOverlayStatus.setText("Overlay Permission: GRANTED");
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
            tintDot(dotOverlay, R.color.status_green);
        } else {
            tvOverlayStatus.setText("Overlay Permission: NOT GRANTED");
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red));
            tintDot(dotOverlay, R.color.status_red);
        }
    }
}
