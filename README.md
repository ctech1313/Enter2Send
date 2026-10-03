<div align="center">
  <h1>&#9000; Enter2Send</h1>
  <p><strong>Desktop-style sending for physical keyboards on Android and Samsung DeX.</strong></p>
  <p><code>Enter &rarr; Send</code> &nbsp;&middot;&nbsp; <code>Shift+Enter &rarr; New line</code> &nbsp;&middot;&nbsp; No custom keyboard</p>
</div>

![A playful Enter key connecting generic chat bubbles, a desktop chat window, a physical keyboard, and a phone](docs/assets/enter2send-hero.png)

Enter2Send is a small Android accessibility utility that fixes inconsistent physical-keyboard behavior in explicitly supported chat composers. It activates the app's own visible **Send** control—without replacing the app, reading message contents, or relying on screen coordinates.

> [!IMPORTANT]
> Enter2Send is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by OpenAI, Meta, Anthropic, Samsung, or any supported app vendor.

## Supported apps

| App and scope | Default | Target-device status |
| --- | --- | --- |
| **ChatGPT** — normal Chat and Remote | On | Verified |
| **Messenger** — message composers | Off | Verified |
| **Claude Remote Control** — Remote Control windows carrying the exact `Change mode` semantic marker | Off | Verified |

Verification was performed on a Galaxy S23 Ultra running Android 16 / One UI 8.5 in Samsung DeX with:

- ChatGPT `1.2026.195`
- Messenger `571.0.0.41.92`
- Claude `1.260716.20`

Ordinary Claude chats and Dispatch remain native. Unsupported apps and screens are unaffected.

If an app already provides a reliable native **Enter-to-send** setting, use that first. Enter2Send adds integrations only when the app exposes enough accessibility semantics to act safely.

## What it changes

| Key or context | Result |
| --- | --- |
| **Enter** in an enabled supported composer | Sends exactly once |
| **Numpad Enter** | Uses the same explicit send path |
| **Shift+Enter** | Passes through for a new line |
| After a successful send | Waits for the Send control to clear, then restores composer focus |
| Empty, missing, or ambiguous composer | Leaves Enter to the active app |
| Search, settings, login fields, disabled apps, or another app | Unaffected |
| Enter2Send master switch disabled | All keyboard input passes through |

The service acts only when Android exposes all required signals:

1. The active package belongs to an enabled supported profile.
2. Exactly one visible, enabled, focused editable composer exists.
3. Exactly one nearby visible, enabled semantic Send action exists.
4. Any app-specific surface marker—such as Claude Remote Control's `Change mode` marker—is present.

If any requirement is missing or ambiguous, Enter2Send fails open and does nothing.

## Install

Download the signed APK and matching checksum from the [latest GitHub release](https://github.com/ctech1313/Enter2Send/releases/latest):

1. Download `Enter2Send-v0.3.1.apk` and `Enter2Send-v0.3.1.apk.sha256`.
2. Confirm the APK SHA-256 matches the checksum.
3. Install or update the APK.
4. Open **Enter2Send** and select **Open accessibility settings**.
5. Enable **Enter2Send** under installed accessibility apps.
6. Return to Enter2Send and confirm **Accessibility service: ON**.
7. Keep the master switch on and enable the individual apps you want handled.

Verify the download in PowerShell:

```powershell
(Get-FileHash .\Enter2Send-v0.3.1.apk -Algorithm SHA256).Hash
```

See the [changelog](CHANGELOG.md) for release history.

Every official update uses the same release-signing identity. The release notes publish both the APK checksum and signing-certificate SHA-256 fingerprint.

<details>
<summary><strong>Quick verification checklist</strong></summary>

1. Type a non-empty message and press Enter. It should send once.
2. Type two lines with Shift+Enter. Nothing should send until plain Enter is pressed.
3. Without clicking the composer again, type and send a second message with one Enter press.
4. Disable that app's switch and confirm Enter passes through normally.
5. Disable the master switch and confirm every integration pauses immediately.
6. Press Enter in app search/settings and in another app; behavior should remain native.
7. If your keyboard has a numpad, confirm Numpad Enter sends once.

</details>

## Privacy and safety

Enter2Send intentionally keeps a small trust boundary:

- No network permission
- No analytics, telemetry, advertising, or crash reporting
- No backend, account, API integration, or database
- No access to `AccessibilityNodeInfo.text`
- No message-content logging, storage, comparison, or transmission
- No fixed-coordinate taps or gesture injection
- No custom keyboard or input method
- No generalized remapping or user-entered package IDs

Only handled Enter and Numpad Enter events are consumed. Every other key returns immediately.

## Request support for another app

[Open an app-support request](https://github.com/ctech1313/Enter2Send/issues/new?template=app-support.yml) with the app package/version, device and DeX details, affected chat surface, current Enter behavior, and whether the app already offers a native setting.

Support is added per app and remains opt-in unless there is strong evidence for a different default. Enter2Send will not ship coordinate taps, structural guesses, prompt-content inspection, an IME, or a generalized remapper to force compatibility.

## Troubleshooting

### Enter still inserts a new line

- Confirm both the Android accessibility service and Enter2Send master switch are enabled.
- Confirm the individual app switch is enabled.
- Confirm the actual message composer is focused and a Send button is visible.
- If the supported app was updated, its accessibility hierarchy may have changed.

### Enter behaves unexpectedly elsewhere

- Disable the individual app switch or master switch immediately.
- If needed, disable the Android accessibility service completely.
- Report the affected screen, Android/One UI version, and app version—never include message contents.

### The first click only activates a DeX window

That is DeX inactive-window behavior and can occur even with the Enter2Send accessibility service disabled. Enter2Send does not process mouse or touch events.

## Build from source

Build with JDK 17 and Android SDK 35:

```powershell
git clone https://github.com/ctech1313/Enter2Send.git
cd Enter2Send
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Local unit tests exercise the compiled accessibility service using deterministic Android API fakes in `app/src/test`. The fakes reject message-text reads and cover action selection, nested controls, and hardware-key event handling; they are not packaged into the APK. These tests do not replace native-app and physical-keyboard validation on target devices.

Release builds require signing properties outside the repository. Copy `keystore.properties.example` to the configured external path and never commit private signing material.

## Roadmap

- [x] ChatGPT normal Chat and Remote
- [x] Messenger
- [x] Claude Remote Control with ordinary-Claude pass-through
- [x] Consecutive-send focus restoration
- [x] Per-app opt-in switches
- [x] Galaxy S23 Ultra / DeX validation
- [ ] Physical Numpad Enter verification
- [ ] Additional apps that lack reliable native behavior and expose safe semantic controls
- [ ] Optional dictation hotkey support when ChatGPT exposes a unique accessible control

## Project boundary

Enter2Send is not a replacement chat client, browser wrapper, API client, custom keyboard, or general-purpose remapper. It makes a physical keyboard feel natural in a small, explicitly verified set of Android message composers.

Licensed under the [Apache License 2.0](LICENSE).
