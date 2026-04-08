# PanicLock

A rooted Android security app that instantly locks your device when shaken.

## Features

- Shake detection with adjustable sensitivity (5 levels)
- Screen lock via root (KernelSU / Magisk)
- Enable GPS and mobile data on trigger
- Silent mode on trigger
- Force-stop selected apps on trigger
- Panic alarm — flashlight strobe + loud alarm sound with configurable duration
- Fake power menu overlay (may not work with HyperOS, experimental feature)
- Trigger log — last 10 activations with timestamps and actions fired
- Battery auto-stop threshold
- Hide notification icon option
- Auto-starts on device reboot
- Minimal battery usage (2 samples/sec, sensor unregisters during cooldown)

## Requirements

- Rooted Android device (KernelSU or Magisk)
- Android 8.0+ (API 26+)

## Installation

1. Clone the repo
2. Open in Android Studio
3. Build and install on your rooted device
4. Grant root access when prompted on first launch
5. Enable Shake Detection with the master toggle
6. Configure your preferred trigger actions

## Building

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
app/src/main/java/com/security/paniclock/
├── MainActivity.kt          # UI — Settings + Log tabs
├── ShakeDetector.kt         # Accelerometer shake logic
├── LockService.kt           # Foreground service, runs in background
├── TriggerActions.kt        # All on-trigger actions + log writing
├── BootReceiver.kt          # Auto-start on device reboot
├── PowerMenuReceiver.kt     # Fake power menu broadcast receiver
└── FakePowerMenuService.kt  # Fake power menu overlay
```

## Built With

Kotlin · Android SDK · KernelSU root

## Credits

Developed by Svetoslav Izov  
with great help from Claude
