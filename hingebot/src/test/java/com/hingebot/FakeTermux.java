package com.hingebot;

public class FakeTermux extends Termux {
    private boolean wakeLocked = false;
    private boolean wakeUnlocked = false;
    private String lastNotificationTitle;
    private String lastNotificationContent;

    public FakeTermux() {
        super(new FakeCommandRunner());
    }

    @Override
    public void wakeLock() {
        wakeLocked = true;
    }

    @Override
    public void wakeUnlock() {
        wakeUnlocked = true;
    }

    @Override
    public void notify(String title, String content) {
        this.lastNotificationTitle = title;
        this.lastNotificationContent = content;
    }

    public boolean isWakeLocked() {
        return wakeLocked;
    }

    public boolean isWakeUnlocked() {
        return wakeUnlocked;
    }

    public String getLastNotificationTitle() {
        return lastNotificationTitle;
    }

    public String getLastNotificationContent() {
        return lastNotificationContent;
    }
}
