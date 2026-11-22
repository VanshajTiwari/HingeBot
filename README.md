# hingebot

A minimal, production-quality CLI automation tool for Hinge running inside Termux on an Android phone, controlling THAT SAME phone over ADB Wireless Debugging (`localhost`).

It repeats the pattern: **LIKE, LIKE, SKIP, SKIP, SKIP** cleanly and safely.

---

## Termux & Device Setup

> [!IMPORTANT]
> Install Termux and Termux:API from **F-Droid** (do NOT use Google Play Store versions as they are deprecated).

1. **Install Android Tools & Dependencies in Termux:**
   ```sh
   pkg update && pkg install openjdk-17 maven android-tools termux-api termux-wake-lock
   ```

2. **Enable Wireless Debugging on Phone:**
   - Go to Android **Settings > Developer options**.
   - Enable **Wireless debugging** (phone must be connected to Wi-Fi).
   - Tap **Wireless debugging > Pair device with pairing code**. Keep this pairing screen visible (e.g., using split-screen or floating Termux window).

3. **Pair and Connect ADB inside Termux:**
   ```sh
   # Pair using pairing port and 6-digit code shown on screen
   adb pair localhost:<PAIRING_PORT>

   # Connect using the Wireless debugging port
   adb connect localhost:<CONNECT_PORT>

   # Confirm status shows device
   adb devices
   ```

4. **Battery & Screen Settings:**
   - Set Termux app battery usage to **Unrestricted**.
   - Set Display screen timeout to maximum or keep screen awake.

---

## Build

To build the executable JAR target `target/hingebot.jar`:

```sh
# Full build with tests and coverage gate (LINE >= 90%)
mvn -q package

# Quick build on phone skipping tests
mvn -q -DskipTests package
```

---

## Re-pairing & Port Changes

Whenever Wi-Fi reconnects or Wireless Debugging restarts, Android generates a new dynamic port for ADB Wireless debugging:
1. Check current port in **Settings > Developer options > Wireless debugging**.
2. Connect ADB: `adb connect localhost:<NEW_PORT>`
3. Update `adb_target` in `config.properties` or pass `--target localhost:<NEW_PORT>`.

---

## UI Calibration

Hinge layout and labels can vary across regions and app updates. To inspect the exact UI nodes on your phone:
1. Open Hinge and navigate to a profile card.
2. In Termux, dump the current UI hierarchy:
   ```sh
   adb exec-out uiautomator dump /dev/tty
   ```
3. Inspect the `content-desc` or `text` attributes for the heart icon, 'X' skip icon, and 'Send Like' button.
4. Update `labels.like`, `labels.skip`, `labels.send_like`, and `labels.out_of_likes` in `config.properties`.

---

## Usage & Execution

### 1. Dry-Run Mode (Default / Safe)
Simulates taps without sending input commands to the device:
```sh
./run.sh --config config.properties
```

### 2. Live Mode
Sends real taps on your device:
```sh
./run.sh --config config.properties --live --start-delay 15
```

### Command Line Flags
| Flag | Description | Default |
|---|---|---|
| `--config PATH` | Configuration file path | `config.properties` |
| `--dry-run` | Enable dry-run simulation mode | `true` |
| `--live` | Enable live mode (sends real taps) | `false` |
| `--cycles N` | Number of cycles to run (`0` = infinite) | `1` |
| `--target HOST:PORT` | ADB target device address | `localhost:5555` |
| `--start-delay SECONDS` | Countdown before execution begins | `15` |

---

## How to Stop

- Press `Ctrl+C` in Termux (triggers a graceful shutdown hook that finishes the current action and writes log summaries).
- Or run in another Termux session:
  ```sh
  touch ~/hingebot.stop
  ```

---

## Exit Codes

| Code | Meaning |
|---|---|
| `0` | Success / cleanly finished (completed cycles, stop file, Ctrl+C) |
| `1` | Invalid configuration or command-line arguments |
| `2` | ADB error (connection failed after retries, Wi-Fi dropped) |
| `3` | Preflight check failure, foreground app lost, UI element target not found, or action failed |
| `4` | Daily like limit reached (`out of likes` detected) |
| `5` | Unexpected system error |

---

## Troubleshooting

- **Unauthorized device:** Run `adb kill-server && adb start-server`, then check phone for 'Allow USB Debugging' prompt.
- **Connection refused / Port changed:** Re-check Wireless Debugging port in Developer options and run `adb connect localhost:<PORT>`.
- **Termux killed in background:** Ensure Termux battery optimization is set to **Unrestricted** in Android settings.
- **Hinge not detected in foreground:** Open Hinge app before starting or during the countdown period.
- **Slow JVM start on mobile:** On first run in Termux, Java JIT compilation may take a few seconds; subsequent runs will be faster.

---

## Design Choices & Safe Defaults

- **Strict Localhost Binding:** `adb_target` MUST be `localhost` or `127.0.0.1`. Remote IP addresses are strictly rejected to ensure hingebot never controls another phone.
- **Dry-Run Default:** `dry_run` defaults to `true`. Live mode requires explicit `--live` or `dry_run=false`.
- **No Blind Tapping:** If buttons cannot be located in UI XML and `use_fallback_ratios=false`, hingebot aborts rather than tapping blindly.
- **Hard Caps Enforced:** `max_total_actions` (default 40) and `max_likes` (default 8) are hard caps that stop the bot even when `cycles=0` (infinite mode).
- **Single Process Draining:** `ProcessCommandRunner` uses stream drain threads and explicit timeouts (`destroyForcibly`) to eliminate thread leaks and deadlock risks.

---

## Compliance & Disclaimer

> [!WARNING]
> Using automated tools on Hinge violates Hinge's Terms of Service and can result in account suspension or banning. Likes sent go to real people. Use responsibly and on a test/low-stakes account.
