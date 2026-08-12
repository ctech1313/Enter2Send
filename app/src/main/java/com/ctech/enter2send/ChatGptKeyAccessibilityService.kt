package com.ctech.enter2send

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ChatGptKeyAccessibilityService : AccessibilityService() {
    private var consumedKeyCode: Int? = null
    private var sendOperation: SendOperation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sendPoll: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val operationPackage = sendOperation?.composerAnchor?.packageName ?: return
        if (event?.packageName?.toString() != operationPackage) {
            resetSendOperation()
        }
    }

    override fun onInterrupt() {
        consumedKeyCode = null
        resetSendOperation()
    }

    override fun onDestroy() {
        consumedKeyCode = null
        resetSendOperation()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!BridgePreferences.isMasterEnabled(this)) {
            consumedKeyCode = null
            resetSendOperation()
            return false
        }

        if (event.keyCode != KeyEvent.KEYCODE_ENTER &&
            event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return false
        }

        if (event.action == KeyEvent.ACTION_UP) {
            val consume = consumedKeyCode == event.keyCode
            consumedKeyCode = null
            return consume
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.isShiftPressed) return false
        if (event.repeatCount > 0) return consumedKeyCode == event.keyCode

        val activeApp = activeAppRoot() ?: run {
            resetSendOperation()
            return false
        }

        refreshSendOperation(activeApp.root, activeApp.profile)
        if (sendOperation != null) return false

        val composer = findFocusedComposer(activeApp.root, activeApp.profile) ?: return false
        val sendButton =
            findUniqueSendButtonNearComposer(composer, activeApp.profile) ?: return false
        val composerAnchor = createComposerAnchor(composer, activeApp.profile)
        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            consumedKeyCode = event.keyCode
            beginSendConfirmation(composerAnchor)
        }
        return clicked
    }

    private fun activeAppRoot(): ActiveAppRoot? {
        val focusedWindow = InputFocusedWindowSelector.select(interactiveWindows()) {
            it.isFocused
        } ?: return null
        val root = focusedWindow.root ?: return null
        if (root.windowId != focusedWindow.id) return null
        val profile = SupportedAppProfiles.forPackage(root.packageName?.toString()) ?: return null
        if (!BridgePreferences.isAppEnabled(this, profile)) return null
        if (!hasRequiredWindowIdentity(root, profile)) return null
        return ActiveAppRoot(profile, root)
    }

    private fun interactiveWindows(): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return windows

        val windowsByDisplay = windowsOnAllDisplays
        return buildList {
            for (index in 0 until windowsByDisplay.size()) {
                addAll(windowsByDisplay.valueAt(index))
            }
        }
    }

    private fun hasRequiredWindowIdentity(
        root: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): Boolean {
        if (!profile.requiresWindowIdentity) return true

        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.packageName?.toString() == profile.packageName &&
                node.isVisibleToUser &&
                node.isEnabled &&
                !node.isEditable
            ) {
                val semanticMatch =
                    profile.hasRequiredWindowIdentity(node.contentDescription) ||
                        node.actionList.any {
                            profile.hasRequiredWindowIdentity(it.label)
                        }
                if (semanticMatch) return true
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (visit(child)) return true
            }
            return false
        }

        return visit(root)
    }

    private fun beginSendConfirmation(composerAnchor: ComposerAnchor) {
        val now = SystemClock.uptimeMillis()
        sendOperation = SendOperation.AwaitingClear(
            composerAnchor = composerAnchor,
            deadline = now + SEND_CONFIRM_TIMEOUT_MS
        )
        startSendPolling()
    }

    private fun refreshSendOperation(
        root: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ) {
        val operation = sendOperation ?: return
        if (profile.packageName != operation.composerAnchor.packageName ||
            root.windowId != operation.composerAnchor.windowId
        ) {
            resetSendOperation()
            return
        }

        val focusedComposer = findFocusedComposer(root, profile)
        if (focusedComposer != null &&
            !matchesComposerAnchor(focusedComposer, operation.composerAnchor)
        ) {
            resetSendOperation()
            return
        }

        val now = SystemClock.uptimeMillis()
        when (operation) {
            is SendOperation.AwaitingClear -> {
                if (now >= operation.deadline) {
                    resetSendOperation()
                    return
                }

                val composer = findAnchoredComposer(root, profile, operation.composerAnchor)
                if (composer == null) {
                    operation.clearPolls = 0
                    return
                }

                when (findSendButtonNearComposer(composer, profile)) {
                    SendButtonMatch.Absent -> {
                        operation.clearPolls += 1
                        if (operation.clearPolls >= SEND_CLEAR_CONFIRM_POLLS) {
                            beginRefocusing(operation.composerAnchor, composer, now)
                        }
                    }

                    is SendButtonMatch.Unique,
                    SendButtonMatch.Ambiguous -> operation.clearPolls = 0
                }
            }

            is SendOperation.Refocusing -> {
                if (now >= operation.deadline) {
                    resetSendOperation()
                    return
                }

                val composer = findAnchoredComposer(root, profile, operation.composerAnchor)
                    ?: return
                advanceRefocus(operation, composer, now)
            }
        }
    }

    private fun beginRefocusing(
        composerAnchor: ComposerAnchor,
        composer: AccessibilityNodeInfo,
        now: Long
    ) {
        val operation = SendOperation.Refocusing(
            composerAnchor = composerAnchor,
            deadline = now + FOCUS_RESTORE_TIMEOUT_MS,
            clickAllowedAt = now + FOCUS_CLICK_FALLBACK_DELAY_MS
        )
        sendOperation = operation
        advanceRefocus(operation, composer, now)
    }

    private fun advanceRefocus(
        operation: SendOperation.Refocusing,
        composer: AccessibilityNodeInfo,
        now: Long
    ) {
        if (composer.isFocused) {
            resetSendOperation()
            return
        }

        if (!operation.focusAttempted) {
            operation.focusAttempted = true
            if (supportsAction(composer, AccessibilityNodeInfo.ACTION_FOCUS)) {
                composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            return
        }

        if (!operation.clickAttempted &&
            now >= operation.clickAllowedAt &&
            supportsAction(composer, AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            operation.clickAttempted = true
            composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun startSendPolling() {
        cancelSendPolling()
        val poll = object : Runnable {
            override fun run() {
                if (sendOperation == null) {
                    sendPoll = null
                    return
                }
                if (!BridgePreferences.isMasterEnabled(this@ChatGptKeyAccessibilityService)) {
                    resetSendOperation()
                    return
                }

                val activeApp = activeAppRoot()
                if (activeApp == null) {
                    resetSendOperation()
                    return
                }

                refreshSendOperation(activeApp.root, activeApp.profile)
                if (sendOperation != null) {
                    mainHandler.postDelayed(this, SEND_POLL_INTERVAL_MS)
                } else {
                    sendPoll = null
                }
            }
        }
        sendPoll = poll
        mainHandler.post(poll)
    }

    private fun cancelSendPolling() {
        sendPoll?.let(mainHandler::removeCallbacks)
        sendPoll = null
    }

    private fun resetSendOperation() {
        cancelSendPolling()
        sendOperation = null
    }

    private fun findFocusedComposer(
        root: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): AccessibilityNodeInfo? {
        val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null &&
            isEditableComposerCandidate(inputFocus, profile, requireFocus = true)
        ) {
            return inputFocus
        }
        return findUniqueEditableNode(root, profile, requireFocus = true)
    }

    private fun findAnchoredComposer(
        root: AccessibilityNodeInfo,
        profile: SupportedAppProfile,
        composerAnchor: ComposerAnchor
    ): AccessibilityNodeInfo? {
        val composer = findUniqueEditableNode(root, profile, requireFocus = false) ?: return null
        return composer.takeIf { matchesComposerAnchor(it, composerAnchor) }
    }

    private fun findUniqueEditableNode(
        root: AccessibilityNodeInfo,
        profile: SupportedAppProfile,
        requireFocus: Boolean
    ): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo) {
            if (matches.size > 1) return
            if (isEditableComposerCandidate(node, profile, requireFocus)) matches += node

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
                if (matches.size > 1) return
            }
        }

        visit(root)
        return matches.singleOrNull()
    }

    private fun isEditableComposerCandidate(
        node: AccessibilityNodeInfo,
        profile: SupportedAppProfile,
        requireFocus: Boolean
    ): Boolean = node.packageName?.toString() == profile.packageName &&
        node.isVisibleToUser &&
        node.isEnabled &&
        node.isEditable &&
        (!requireFocus || node.isFocused)

    private fun createComposerAnchor(
        composer: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): ComposerAnchor =
        ComposerAnchor(
            packageName = profile.packageName,
            windowId = composer.windowId,
            className = composer.className?.toString(),
            viewIdResourceName = composer.viewIdResourceName,
            ancestorClassNames = composerAncestorClassNames(composer)
        )

    private fun matchesComposerAnchor(
        composer: AccessibilityNodeInfo,
        anchor: ComposerAnchor
    ): Boolean = composer.packageName?.toString() == anchor.packageName &&
        composer.className?.toString() == anchor.className &&
        composer.viewIdResourceName == anchor.viewIdResourceName &&
        composerAncestorClassNames(composer) == anchor.ancestorClassNames

    private fun composerAncestorClassNames(composer: AccessibilityNodeInfo): List<String?> {
        val classNames = mutableListOf<String?>()
        var ancestor = composer.parent
        repeat(MAX_COMPOSER_ANCHOR_ANCESTORS) {
            val current = ancestor ?: return@repeat
            classNames += current.className?.toString()
            ancestor = current.parent
        }
        return classNames
    }

    private fun findUniqueSendButtonNearComposer(
        composer: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): AccessibilityNodeInfo? =
        (findSendButtonNearComposer(composer, profile) as? SendButtonMatch.Unique)?.node

    private fun findSendButtonNearComposer(
        composer: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): SendButtonMatch {
        var ancestor: AccessibilityNodeInfo? = composer
        repeat(MAX_COMPOSER_ANCESTOR_LEVELS) {
            ancestor = ancestor?.parent
            val scope = ancestor ?: return SendButtonMatch.Absent
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectIdentityTargets(
                scope,
                null,
                profile,
                candidates
            )
            when (candidates.size) {
                1 -> return SendButtonMatch.Unique(candidates.single())
                in 2..Int.MAX_VALUE -> return SendButtonMatch.Ambiguous
            }
        }
        return SendButtonMatch.Absent
    }

    private fun collectIdentityTargets(
        node: AccessibilityNodeInfo,
        clickableAncestor: AccessibilityNodeInfo?,
        profile: SupportedAppProfile,
        matches: MutableList<AccessibilityNodeInfo>
    ) {
        if (matches.size > 1) return

        val clickableTarget =
            if (isClickableActionNode(node, profile)) node else clickableAncestor
        if (clickableTarget != null &&
            isIdentityBearingActionNode(node, profile) &&
            profile.hasSendIdentity(
                node.contentDescription,
                node.viewIdResourceName,
                node.actionList.map { it.label }
            ) &&
            matches.none { it == clickableTarget }
        ) {
            matches += clickableTarget
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectIdentityTargets(
                child,
                clickableTarget,
                profile,
                matches
            )
            if (matches.size > 1) return
        }
    }

    private fun isClickableActionNode(
        node: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): Boolean =
        node.packageName?.toString() == profile.packageName &&
            node.isVisibleToUser &&
            node.isEnabled &&
            node.isClickable &&
            supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)

    private fun isIdentityBearingActionNode(
        node: AccessibilityNodeInfo,
        profile: SupportedAppProfile
    ): Boolean {
        if (node.packageName?.toString() != profile.packageName ||
            !node.isVisibleToUser ||
            !node.isEnabled ||
            node.isEditable
        ) return false

        // Compose and React Native may expose an action's exact semantic label on
        // a non-clickable icon child while ACTION_CLICK lives on its ancestor.
        return true
    }

    private fun supportsAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        node.actionList.any { it.id == action }

    private sealed class SendOperation {
        abstract val composerAnchor: ComposerAnchor
        abstract val deadline: Long

        data class AwaitingClear(
            override val composerAnchor: ComposerAnchor,
            override val deadline: Long,
            var clearPolls: Int = 0
        ) : SendOperation()

        data class Refocusing(
            override val composerAnchor: ComposerAnchor,
            override val deadline: Long,
            val clickAllowedAt: Long,
            var focusAttempted: Boolean = false,
            var clickAttempted: Boolean = false
        ) : SendOperation()
    }

    private sealed class SendButtonMatch {
        data object Absent : SendButtonMatch()
        data class Unique(val node: AccessibilityNodeInfo) : SendButtonMatch()
        data object Ambiguous : SendButtonMatch()
    }

    private data class ComposerAnchor(
        val packageName: String,
        val windowId: Int,
        val className: String?,
        val viewIdResourceName: String?,
        val ancestorClassNames: List<String?>
    )

    private data class ActiveAppRoot(
        val profile: SupportedAppProfile,
        val root: AccessibilityNodeInfo
    )

    companion object {
        private const val MAX_COMPOSER_ANCESTOR_LEVELS = 8
        private const val MAX_COMPOSER_ANCHOR_ANCESTORS = 3
        private const val SEND_POLL_INTERVAL_MS = 100L
        private const val SEND_CONFIRM_TIMEOUT_MS = 3_000L
        private const val SEND_CLEAR_CONFIRM_POLLS = 2
        private const val FOCUS_RESTORE_TIMEOUT_MS = 3_000L
        private const val FOCUS_CLICK_FALLBACK_DELAY_MS = 250L
    }
}
