<div align="center">
  <h1>&#9000; Enter2Send</h1>
  <p><strong>Desktop-style physical-keyboard controls for ChatGPT on Android and Samsung DeX.</strong></p>
  <p><code>Enter &rarr; Send</code> &nbsp;&middot;&nbsp; <code>Shift+Enter &rarr; Newline</code> &nbsp;&middot;&nbsp; DeX verified</p>
</div>

Enter2Send is a small Android accessibility utility for people who use the official ChatGPT app with a physical keyboard. It makes the message composer behave like a desktop chat box without replacing ChatGPT, installing a custom keyboard, or reading prompt contents.

> [!IMPORTANT]
> Enter2Send is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by OpenAI. ChatGPT is a trademark of OpenAI.

[Overview](#overview) &middot; [Install](#install) &middot; [Compatibility](#compatibility) &middot; [Roadmap](#roadmap) &middot; [Privacy](#privacy-and-safety)

## Overview

| Key or context | Result |
| --- | --- |
| **Enter** in the focused ChatGPT composer | Sends the current message exactly once |
| **Numpad Enter** in the focused composer | Sends through the explicit Android numpad key path |
| **Shift+Enter** | Passes through to ChatGPT and inserts a newline |
| **F8** with the experimental toggle enabled | Starts dictation, or stops it and returns the transcript for review |
| **Enter while dictating** | Stops dictation and submits through ChatGPT's existing Send action |
| Empty or ambiguous composer | Leaves Enter to ChatGPT's normal behavior |
| Search, settings, login fields, or another app | Completely unaffected |
| Enter2Send switch disabled | All keyboard input passes through unchanged |

The service acts only when Android exposes all of the following unambiguously:

1. The active package is exactly `com.openai.chatgpt`.
2. There is one visible, enabled, focused editable composer.
3. There is one nearby visible, enabled Send action.

If any requirement is missing or ambiguous, Enter2Send does nothing and the key continues normally.

> [!NOTE]
> The signed v0.1.0 release contains only the verified Enter behavior. F8 support currently exists only on the isolated `experiment/remote-dictation` branch and is off by default. Its debug APK uses the separate package `com.ctech.enter2send.dictation`, allowing it to be installed beside v0.1.0; enable only one Enter2Send accessibility service at a time.

## Why this exists

The ChatGPT Android app can treat physical Enter as a newline, which interrupts keyboard-first workflows in DeX. General-purpose remappers can approximate Enter-to-send with macros or screen taps, but those approaches may require broad configuration or depend on a fixed screen layout.

Enter2Send is deliberately narrower: it recognizes the focused composer and ChatGPT's existing Send control through accessibility semantics, then activates that control without using screen coordinates.

## Compatibility

Real-device Samsung DeX testing passed with:

- **Device:** Galaxy S23 Ultra
- **OS:** Android 16 / One UI 8.5
- **ChatGPT:** `1.2026.195(12)`
- **Verified:** Enter sends exactly once; Shift+Enter inserts a newline
- **Numpad Enter:** Explicit keycode path emulator-tested; physical verification is pending because the target keyboard has no numpad

ChatGPT updates may change its accessibility hierarchy. When a future version no longer exposes a unique composer or Send control, Enter2Send is designed to fail open and leave the key untouched.

## Install

Download the signed APK from the [latest GitHub release](https://github.com/ctech1313/Enter2Send/releases/latest):

1. Download `Enter2Send-v0.1.0.apk` and its `.sha256` checksum file.
2. Confirm the APK's SHA-256 matches the published checksum.
3. Allow your browser or file manager to install unknown apps when Android prompts you.
4. Install and open **Enter2Send**.
5. Select **Open accessibility settings**.
6. Enable **Enter2Send** under installed accessibility apps.
7. Return to the app and confirm **Accessibility service: ON**.
8. Leave **Enter-to-send enabled** switched on.

Verify the download in PowerShell with:

```powershell
(Get-FileHash .\Enter2Send-v0.1.0.apk -Algorithm SHA256).Hash
```

The release notes also publish the signing-certificate SHA-256 fingerprint. Every official update will use the same signing identity.

### Build from source

Build the debug APK with JDK 17 and Android SDK 35:

```powershell
git clone https://github.com/ctech1313/Enter2Send.git
cd Enter2Send
.\gradlew.bat clean assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The in-app switch pauses interception without revoking accessibility access. Disabling the Android accessibility service stops Enter2Send completely.

<details>
<summary><strong>Quick verification checklist</strong></summary>

1. Type a non-empty ChatGPT message and press Enter. It should send once.
2. Type two lines with Shift+Enter. Nothing should send until plain Enter is pressed.
3. Press Enter in ChatGPT search/settings and in another application. Behavior should remain normal.
4. Disable the in-app switch and confirm Enter2Send stops intercepting immediately.
5. If your keyboard has a numpad, confirm Numpad Enter sends once.

</details>

## Privacy and safety

Enter2Send intentionally has a small trust boundary:

- No network permission
- No analytics, telemetry, advertising, or crash reporting
- No backend, account, API integration, or database
- No access to `AccessibilityNodeInfo.text`
- No prompt-content logging, storage, or transmission
- No fixed-coordinate taps or gesture injection
- No generalized key-remapping interface
- No custom keyboard or input method

The accessibility service is package-restricted to the official ChatGPT Android app. The stable release consumes only handled Enter and Numpad Enter events. The experimental branch may also consume F8 when its separate opt-in switch is enabled and a unique ChatGPT dictation action is present.

## Troubleshooting

**Enter still inserts a newline**

- Confirm the Android accessibility service and the in-app switch are both enabled.
- Confirm the actual ChatGPT message composer is focused and a Send button is available.
- If ChatGPT was recently updated, its accessibility hierarchy may have changed. Open an issue with the Android, One UI, and ChatGPT versions&mdash;never include prompt contents.

**Enter behaves unexpectedly elsewhere**

- Disable the in-app switch or accessibility service immediately.
- Report the affected screen and application version. The service should leave every non-composer field and every other app untouched.

## Roadmap

- [x] Enter sends from the focused ChatGPT composer
- [x] Shift+Enter inserts a newline
- [x] Samsung DeX verification on the target Galaxy device
- [ ] Physical Numpad Enter verification
- [ ] **Optional dictation hotkey support** for starting and stopping ChatGPT dictation from a physical keyboard *(experimental branch in progress)*

The experiment implements an opt-in hybrid flow: F8 starts or stops dictation, while Enter during recording uses ChatGPT's existing Send action. It will ship only after both normal Chat and Remote preserve the transcript, return cleanly to the composer, and pass repeated real-device DeX testing. The implementation does not use coordinate maps, gesture injection, a custom keyboard, or a separate speech-recognition stack.

### Emulator preflight (2026-07-21)

- `Pixel_9_Pro_XL_API_35` booted successfully on Android 15 / API 35 without wiping AVD data.
- The experimental debug APK installed, the accessibility service bound, and the status screen reported ON.
- The F8 switch was confirmed off on first launch, could be changed, persisted normally, and was returned to off.
- Injecting F8 outside ChatGPT left the service bound with no crash.
- The official ChatGPT package was not installed. Its Play Store page opened in the unauthenticated Play Store activity, so normal Chat, Remote, microphone pass-through, and ChatGPT key-flow testing could not be attempted without user credentials.

These results are preflight evidence only. Android 15 emulation cannot replace Android 16 / One UI / Samsung DeX acceptance on the target Galaxy device.

## Project boundaries

Enter2Send is not a replacement ChatGPT client, browser wrapper, API client, backend service, custom keyboard, or general-purpose remapper. Its purpose is one focused improvement: make a physical keyboard feel natural in the official ChatGPT Android composer.

## License

Licensed under the [Apache License 2.0](LICENSE).
