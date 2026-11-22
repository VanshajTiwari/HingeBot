# CHANGES

## [Unreleased] — 2026-11-21

### ⚠️ Disclaimer
This project (SmartDiscovery) is an automation controller for use in **authorized test environments only**.  
Using automation tools against third-party services without permission may violate their Terms of Service.

---

### Added

#### Architecture
- **`SwipeDirection` enum** — typed `RIGHT` / `LEFT` values replacing magic strings
- **`SequenceConfig`** — single source of truth for the automation sequence (`RIGHT RIGHT LEFT LEFT LEFT`), hard caps (`MAX_RIGHT_ACTIONS = 8`, `MAX_TOTAL_ACTIONS = 40`), and all timing constants; no logic is duplicated elsewhere
- **`SessionState` enum** — explicit states: `IDLE`, `RUNNING`, `STOPPED`, `ERROR`
- **`SessionEvent`** — immutable event record with timestamp, type, and detail string for structured event history
- **`AutomationStateMachine`** — deterministic, Android-free state machine owning all sequence position and counter logic; supports `start`, `stop`, `error`, `reset`, `recordStepSuccess`, `recordStepSkipped`, and cap enforcement
- **`InMemoryEventLogger`** — thread-safe, Android-free `IEventLogger` implementation (no `android.util.Log` dependency; host-JVM testable)
- **`SessionRepository`** — `SharedPreferences`-backed `ISessionRepository` implementation for session state persistence
- **`SessionViewModel`** — MVVM `AndroidViewModel` with `LiveData` fields; `MainActivity` observes these instead of touching automation logic directly

#### Interfaces (layer boundaries)
- **`IAutomationController`** — `startAutomation()`, `stopAutomation(String)`, `isAutomationRunning()`
- **`ISessionRepository`** — counter persistence and session state read/write
- **`IEventLogger`** — `log(SessionEvent)`, `getEvents()`, `clear()`

#### Services
- **`AutomationForegroundService`** — `START_STICKY` foreground service with a persistent low-priority notification; keeps the session alive while the user freely uses their phone; shows current status (direction counts) and a **Stop** quick action in the notification

#### Tests
- **`AutomationStateMachineTest`** — 28 host-JVM unit tests covering:
  - Initial state
  - Sequence order and wrap-around (`RIGHT RIGHT LEFT LEFT LEFT → RIGHT …`)
  - Counter correctness after a full cycle
  - `start` / `stop` / `error` / `reset` state transitions
  - Idempotent `start`
  - `startFromError` guard (`IllegalStateException`)
  - RIGHT cap and total cap auto-stop
  - `isNextStepBlocked` logic
  - `recordStepSkipped` does not advance index
  - Event logger `clear`
  - Constructor null/empty sequence guard

---

### Changed

#### `HingeAutomationService`
- Implements `IAutomationController`
- Replaced inline `String[]` sequence and scattered counters with `AutomationStateMachine`
- Sequence labels changed from `LIKE` / `SKIP` to `RIGHT` / `LEFT` (matching spec)
- `onAccessibilityEvent` no longer triggers steps — automation runs on its own `Handler.postDelayed` loop (non-blocking, never stalls the main thread)
- Added `EXTRA_RIGHT`, `EXTRA_LEFT`, `EXTRA_SESSION_STATE` to status broadcast; legacy `EXTRA_LIKES` / `EXTRA_SKIPS` keys preserved for compatibility
- RIGHT step now backs out gracefully (global back) if the confirmation sheet does not appear
- LEFT step now searches for `"X"` in addition to `"Skip"` / `"Remove"` / `"Pass"`
- Random inter-step delay sourced from `SequenceConfig` constants

#### `FloatingOverlayService`
- Tapping the pill now also starts/stops `AutomationForegroundService` (foreground session keeper)
- Overlay label updated: `▶ Start` / `⏹ Stop (N→)`

#### `MainActivity`
- Observes `SessionViewModel` LiveData instead of directly reading service state
- Stats display updated: **Rights / Lefts / Total** replacing Likes / Skips
- Sequence header shows `→ → ← ← ←`
- Removed in-Activity automation state; control delegated to ViewModel

#### `AndroidManifest.xml`
- Registered `AutomationForegroundService` with `foregroundServiceType="specialUse"`
- Added `FOREGROUND_SERVICE_SPECIAL_USE` permission (API 34+)

#### `strings.xml`
- `app_name` → **SmartDiscovery**
- `accessibility_service_label` → **SmartDiscovery Automation**
- Updated service description

#### `libs.versions.toml` / `build.gradle.kts`
- Added `androidx.lifecycle` (`lifecycle-viewmodel`, `lifecycle-livedata`, `lifecycle-runtime`) at version `2.8.7`

---

### Removed
- `AppConfig.java` — superseded by `SessionRepository` (kept on disk but no longer used in the active flow)
- Hardcoded `MAX_LIKES = 8` / `MAX_TOTAL = 40` magic numbers inside `HingeAutomationService` — moved to `SequenceConfig`
- Sequence logic distributed across service fields — consolidated into `AutomationStateMachine`

---

### Build & Test results

```
Task :app:testDebugUnitTest
  28 passed, 0 skipped, 0 failed

Task :app:assembleDebug
  BUILD SUCCESSFUL
```
