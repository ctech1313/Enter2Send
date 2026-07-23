package com.ctech.enter2send

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatGptKeyAccessibilityService : AccessibilityService() {
    private var consumedKeyCode: Int? = null
    private var sendOperation: SendOperation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sendPoll: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != CHATGPT_PACKAGE) {
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

        val root = chatGptRoot() ?: run {
            resetSendOperation()
            return false
        }

        refreshSendOperation(root)
        if (sendOperation != null) return false

        val composer = findFocusedComposer(root) ?: return false
        val sendButton = findUniqueSendButtonNearComposer(composer) ?: return false
        val composerAnchor = createComposerAnchor(composer)
        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            consumedKeyCode = event.keyCode
            beginSendConfirmation(composerAnchor)
        }
        return clicked
    }

    private fun chatGptRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == CHATGPT_PACKAGE }

    private fun beginSendConfirmation(composerAnchor: ComposerAnchor) {
        val now = SystemClock.uptimeMillis()
        sendOperation = SendOperation.AwaitingClear(
            composerAnchor = composerAnchor,
            deadline = now + SEND_CONFIRM_TIMEOUT_MS
        )
        startSendPolling()
    }

    private fun refreshSendOperation(root: AccessibilityNodeInfo) {
        val operation = sendOperation ?: return
        if (root.windowId != operation.composerAnchor.windowId) {
            resetSendOperation()
            return
        }

        val focusedComposer = findFocusedComposer(root)
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

                val composer = findAnchoredComposer(root, operation.composerAnchor)
                if (composer == null) {
                    operation.clearPolls = 0
                    return
                }

                when (findSendButtonNearComposer(composer)) {
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

                val composer = findAnchoredComposer(root, operation.composerAnchor)
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

                val root = chatGptRoot()
                if (root == null) {
                    resetSendOperation()
                    return
                }

                refreshSendOperation(root)
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

    private fun findFocusedComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && isEditableComposerCandidate(inputFocus, requireFocus = true)) {
            return inputFocus
        }
        return findUniqueEditableNode(root, requireFocus = true)
    }

    private fun findAnchoredComposer(
        root: AccessibilityNodeInfo,
        composerAnchor: ComposerAnchor
    ): AccessibilityNodeInfo? {
        val composer = findUniqueEditableNode(root, requireFocus = false) ?: return null
        return composer.takeIf { matchesComposerAnchor(it, composerAnchor) }
    }

    private fun findUniqueEditableNode(
        root: AccessibilityNodeInfo,
        requireFocus: Boolean
    ): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo) {
            if (matches.size > 1) return
            if (isEditableComposerCandidate(node, requireFocus)) matches += node

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
        requireFocus: Boolean
    ): Boolean = node.packageName?.toString() == CHATGPT_PACKAGE &&
        node.isVisibleToUser &&
        node.isEnabled &&
        node.isEditable &&
        (!requireFocus || node.isFocused)

    private fun createComposerAnchor(composer: AccessibilityNodeInfo): ComposerAnchor =
        ComposerAnchor(
            windowId = composer.windowId,
            className = composer.className?.toString(),
            viewIdResourceName = composer.viewIdResourceName,
            ancestorClassNames = composerAncestorClassNames(composer)
        )

    private fun matchesComposerAnchor(
        composer: AccessibilityNodeInfo,
        anchor: ComposerAnchor
    ): Boolean = composer.className?.toString() == anchor.className &&
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
        composer: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? =
        (findSendButtonNearComposer(composer) as? SendButtonMatch.Unique)?.node

    private fun findSendButtonNearComposer(
        composer: AccessibilityNodeInfo
    ): SendButtonMatch {
        var ancestor: AccessibilityNodeInfo? = composer
        repeat(MAX_COMPOSER_ANCESTOR_LEVELS) {
            ancestor = ancestor?.parent
            val scope = ancestor ?: return SendButtonMatch.Absent
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectIdentityTargets(
                scope,
                null,
                SEND_DESCRIPTIONS,
                SEND_VIEW_ID_SUFFIXES,
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
        descriptions: Set<String>,
        viewIdSuffixes: Set<String>,
        matches: MutableList<AccessibilityNodeInfo>
    ) {
        if (matches.size > 1) return

        val clickableTarget = if (isClickableActionNode(node)) node else clickableAncestor
        if (clickableTarget != null &&
            isIdentityBearingActionNode(node) &&
            hasActionIdentity(node, descriptions, viewIdSuffixes) &&
            matches.none { it == clickableTarget }
        ) {
            matches += clickableTarget
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectIdentityTargets(
                child,
                clickableTarget,
                descriptions,
                viewIdSuffixes,
                matches
            )
            if (matches.size > 1) return
        }
    }

    private fun isClickableActionNode(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() == CHATGPT_PACKAGE &&
            node.isVisibleToUser &&
            node.isEnabled &&
            node.isClickable &&
            supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)

    private fun isIdentityBearingActionNode(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() != CHATGPT_PACKAGE ||
            !node.isVisibleToUser ||
            !node.isEnabled ||
            node.isEditable
        ) return false

        val className = node.className?.toString()
        return node.isClickable || className == CLASS_BUTTON || className == CLASS_VIEW
    }

    private fun hasActionIdentity(
        node: AccessibilityNodeInfo,
        descriptions: Set<String>,
        viewIdSuffixes: Set<String>
    ): Boolean {
        val description = node.contentDescription?.toString()?.trim()
        if (description != null && descriptions.any {
                it.equals(description, ignoreCase = true)
            }
        ) {
            return true
        }

        val viewId = node.viewIdResourceName?.lowercase() ?: return false
        return viewIdSuffixes.any(viewId::endsWith)
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
        val windowId: Int,
        val className: String?,
        val viewIdResourceName: String?,
        val ancestorClassNames: List<String?>
    )

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val CLASS_BUTTON = "android.widget.Button"
        private const val CLASS_VIEW = "android.view.View"
        private const val MAX_COMPOSER_ANCESTOR_LEVELS = 5
        private const val MAX_COMPOSER_ANCHOR_ANCESTORS = 3
        private const val SEND_POLL_INTERVAL_MS = 100L
        private const val SEND_CONFIRM_TIMEOUT_MS = 3_000L
        private const val SEND_CLEAR_CONFIRM_POLLS = 2
        private const val FOCUS_RESTORE_TIMEOUT_MS = 3_000L
        private const val FOCUS_CLICK_FALLBACK_DELAY_MS = 250L

        private val SEND_DESCRIPTIONS = setOf("Send", "Send message")
        private val SEND_VIEW_ID_SUFFIXES = setOf(
            "/send",
            "/send_button",
            "/send_message",
            "/send_message_button"
        )
    }
}
