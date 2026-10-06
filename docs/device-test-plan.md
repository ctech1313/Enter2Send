# Enter2Send candidate device gate

Status: NOT RUN. No ADB device was attached to TECHBOX on 2026-10-06. JVM tests exercise production bytecode with Android API fakes; they do not establish OEM behavior.

## Record before testing

- Candidate commit SHA, APK SHA-256, signing certificate SHA-256 and application version/code; confirm the installed APK is that candidate.
- Device model, Android/One UI/MIUI version, physical keyboard model/layout, phone versus DeX mode, display/window arrangement, app versions and locale.
- Use disposable drafts/conversations and synthetic messages in approved destinations. Preserve unrelated drafts. Obtain explicit permission before pairing/authorizing ADB, installing, changing accessibility/settings, or sending test messages.
- Keep rollback APK and its checksum available. Never uninstall merely to bypass a signing mismatch; that can erase local state. Do not collect or upload real composer text, full accessibility dumps, or unrelated logcat output.

## Acceptance matrix

Run normal ChatGPT chat and Remote; Messenger with its profile enabled; Claude Remote Control with its profile enabled and exact Change mode marker. Run on phone and Samsung DeX. Samsung and Xiaomi reported compatibility issues require results from the affected device/app combinations; do not extrapolate a Samsung pass to Xiaomi.

| Scenario | Required result |
| --- | --- |
| Enter, Ctrl+Enter, numpad Enter | One synthetic message per deliberate press when the unique enabled Send control is exposed. |
| Held Enter / repeat key-down, then key-up | At most one service Send click per held press; key-up remains paired. |
| Shift+Enter, including Ctrl+Shift+Enter | Native newline behavior; no service Send click. |
| Rapid separate presses while Send remains enabled after first click | No second service click while confirmation is pending. Distinguish native app handling from a service click. |
| Send absent, disabled, ambiguous, or shows Stop/Voice/Resend | No service click and native Enter handling. |
| Nested Send icon with a clickable parent | Correct physical target clicks once; a conflicting child on that target blocks it. Test only layouts actually exposed by the app. |
| App-supported localized Send labels | Correct target behavior at the tested locale/version; record exact control semantics without draft content. |
| Composer recreation with reused class/view ID | Pending send remains protected; the replacement receives no speculative focus/click. Manual focus followed by a new confirmed draft can send. |
| Original source survives/reappears after Send clears | Focus returns only when original source identity is still provable for ChatGPT; no extra Send click. |
| Background events from another supported app, original window focused | Pending confirmation remains active in all three profiles; valid original-composer recovery remains possible. |
| Switch to a second window of the same app and return promptly | An observed window switch cancels old focus recovery, including delayed click fallback. |
| Switch to another supported/unsupported app; split screen; multiple displays | No click/focus action in a background window. Ambiguous or missing input focus fails open. |
| Master/per-app disable during confirmation or refocusing | Pending actions stop; enabling later does not revive the old operation. |
| Service interrupt/restart, unavailable composer, confirmation timeout | No stale focus/click callback; subsequent explicit input recovers without permanent lockout. |
| Search/settings/login; ordinary Claude chat/Dispatch; disabled profiles | Native behavior; no service click or composer text read. |
| Release diagnostics | No Enter/control-discovery/click-result activity logs; no message content captured. |

Record PASS/FAIL/NOT RUN for each row, device/app/mode, timestamp, candidate identity, synthetic message count, focus destination, and a minimal privacy-safe reproduction for failures. A visible message alone does not identify whether the app or accessibility service sent it.

## Merge and release gate

1. Reverify PR #10/#11 and the candidate branch heads, their ancestry and review coverage. Review changes after any rebase/integration; rerun relevant checks at the exact release candidate.
2. Complete the device matrix above on the affected physical devices with no unresolved send-target, duplicate-send, privacy, or foreground-window defect. Keep unsupported/unavailable device rows explicit.
3. Complete final readiness review. Existing green check names and older-head approvals are not evidence that a changed head was reviewed. Originals #7/#9 remain open until their replacement disposition is deliberately handled.
4. Choose release version/versionCode, update release notes to the actual tested scope, and build with the existing authorized signing identity. Verify signer continuity and APK checksum. If packaging changes after device validation, determine and run the necessary retest before final approval.
5. Only then perform the approved reviewed merges and GitHub release. This preparation does not authorize bypassing the physical-device or final-review gate.
