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
    private var dictationSession: DictationSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transitionPoll: Runnable? = null
    private var composerFocusPoll: Runnable? = null
    private var composerFocusRequest: ComposerFocusRequest? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != CHATGPT_PACKAGE) {
            resetDictationSession()
            cancelComposerFocusRestore()
        }
    }

    override fun onInterrupt() {
        consumedKeyCode = null
        resetDictationSession()
        cancelComposerFocusRestore()
    }

    override fun onDestroy() {
        consumedKeyCode = null
        resetDictationSession()
        cancelComposerFocusRestore()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!BridgePreferences.isMasterEnabled(this)) {
            consumedKeyCode = null
            resetDictationSession()
            cancelComposerFocusRestore()
            return false
        }

        if (event.keyCode == KeyEvent.KEYCODE_F8) {
            if (!BridgePreferences.isDictationEnabled(this)) {
                resetDictationSession()
                return false
            }
            return handleF8(event)
        }

        if (event.keyCode != KeyEvent.KEYCODE_ENTER &&
            event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return false
        }

        if (event.isShiftPressed) {
            consumedKeyCode = null
            return false
        }

        if (event.action == KeyEvent.ACTION_UP) {
            val consume = consumedKeyCode == event.keyCode
            consumedKeyCode = null
            return consume
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0 && consumedKeyCode == event.keyCode) return true

        val root = chatGptRoot() ?: run {
            resetDictationSession()
            return false
        }

        val session = refreshRecordingSession(root)
        if (session?.phase == DictationPhase.RECORDING) {
            val stop = findUniqueDictationStop(root) ?: run {
                resetDictationSession()
                return false
            }
            val clicked = stop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                consumedKeyCode = event.keyCode
                beginStopping(session, DictationPhase.STOPPING_TO_SEND)
            }
            return clicked
        }

        if (session != null) return false

        val composer = findFocusedComposer(root) ?: return false
        val sendButton = findUniqueSendButtonNearComposer(composer) ?: return false
        val composerAnchor = createComposerAnchor(composer)
        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            consumedKeyCode = event.keyCode
            startComposerFocusRestore(composerAnchor)
        }
        return clicked
    }

    private fun handleF8(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            val consume = consumedKeyCode == KeyEvent.KEYCODE_F8
            consumedKeyCode = null
            return consume
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0 && consumedKeyCode == KeyEvent.KEYCODE_F8) return true

        val root = chatGptRoot() ?: run {
            resetDictationSession()
            return false
        }

        val session = refreshRecordingSession(root)
        if (session != null) {
            if (session.phase != DictationPhase.RECORDING) return false
            val stop = findUniqueDictationStop(root) ?: run {
                resetDictationSession()
                return false
            }
            val clicked = stop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                consumedKeyCode = KeyEvent.KEYCODE_F8
                beginStopping(session, DictationPhase.STOPPING_TO_EDIT)
            }
            return clicked
        }

        val composer = findFocusedComposer(root) ?: return false

        // A visible Send control means the composer already contains user input.
        // F8 must never replace or submit that input.
        if (findUniqueSendButtonNearComposer(composer) != null) return false

        val startTarget = findRemoteDictationStartTarget(composer) ?: return false
        val clicked = startTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            val now = SystemClock.uptimeMillis()
            dictationSession = DictationSession(
                composerAnchor = createComposerAnchor(composer),
                phase = DictationPhase.STARTING,
                deadline = now + START_TIMEOUT_MS
            )
            consumedKeyCode = KeyEvent.KEYCODE_F8
            startTransitionPolling()
        }
        return clicked
    }

    private fun chatGptRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == CHATGPT_PACKAGE }

    private fun refreshRecordingSession(root: AccessibilityNodeInfo): DictationSession? {
        val session = dictationSession ?: return null
        if (root.windowId != session.composerAnchor.windowId) {
            resetDictationSession()
            return null
        }
        if (session.phase == DictationPhase.STARTING) {
            if (findUniqueDictationStop(root) != null) {
                session.phase = DictationPhase.RECORDING
                cancelTransitionPolling()
            } else if (SystemClock.uptimeMillis() >= session.deadline) {
                resetDictationSession()
                return null
            }
        }
        return dictationSession
    }

    private fun startTransitionPolling() {
        cancelTransitionPolling()
        val poll = object : Runnable {
            override fun run() {
                val session = dictationSession ?: return
                val root = chatGptRoot()
                if (root == null) {
                    resetDictationSession()
                    return
                }
                if (root.windowId != session.composerAnchor.windowId) {
                    resetDictationSession()
                    return
                }

                when (session.phase) {
                    DictationPhase.STARTING -> {
                        if (findUniqueDictationStop(root) != null) {
                            session.phase = DictationPhase.RECORDING
                            transitionPoll = null
                            return
                        }
                    }

                    DictationPhase.STOPPING_TO_EDIT,
                    DictationPhase.STOPPING_TO_SEND -> {
                        if (completeStopTransition(root, session)) {
                            resetDictationSession()
                            return
                        }
                    }

                    DictationPhase.RECORDING -> {
                        transitionPoll = null
                        return
                    }
                }

                if (SystemClock.uptimeMillis() >= session.deadline) {
                    resetDictationSession()
                    return
                }

                mainHandler.postDelayed(this, TRANSITION_POLL_INTERVAL_MS)
            }
        }
        transitionPoll = poll
        mainHandler.post(poll)
    }

    private fun beginStopping(session: DictationSession, phase: DictationPhase) {
        val now = SystemClock.uptimeMillis()
        session.phase = phase
        session.deadline = now + STOP_TIMEOUT_MS
        session.focusRequested = false
        startTransitionPolling()
    }

    private fun completeStopTransition(
        root: AccessibilityNodeInfo,
        session: DictationSession
    ): Boolean {
        val composer = findUniqueVisibleEditableNode(root)
        if (composer != null) {
            val send = findUniqueSendButtonNearComposer(composer)
            if (send != null) {
                if (session.phase == DictationPhase.STOPPING_TO_SEND) {
                    val clicked = send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) startComposerFocusRestore(session.composerAnchor)
                    return clicked
                }

                if (composer.isFocused) return true
                if (!session.focusRequested) {
                    session.focusRequested = true
                    composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                return findFocusedComposer(rootInActiveWindow ?: return false)?.let {
                    it == composer
                } == true
            }
        }
        return false
    }

    private fun cancelTransitionPolling() {
        transitionPoll?.let(mainHandler::removeCallbacks)
        transitionPoll = null
    }

    private fun resetDictationSession() {
        cancelTransitionPolling()
        dictationSession = null
    }

    private fun startComposerFocusRestore(composerAnchor: ComposerAnchor) {
        cancelComposerFocusRestore()
        val now = SystemClock.uptimeMillis()
        val request = ComposerFocusRequest(
            composerAnchor = composerAnchor,
            startedAt = now,
            deadline = now + FOCUS_RESTORE_TIMEOUT_MS
        )
        composerFocusRequest = request

        val poll = object : Runnable {
            override fun run() {
                if (composerFocusRequest !== request ||
                    !BridgePreferences.isMasterEnabled(this@ChatGptKeyAccessibilityService)
                ) {
                    cancelComposerFocusRestore()
                    return
                }

                val root = chatGptRoot()
                if (root == null) {
                    cancelComposerFocusRestore()
                    return
                }
                if (root.windowId != request.composerAnchor.windowId) {
                    cancelComposerFocusRestore()
                    return
                }

                val target = findComposerFocusTarget(root, request)
                val focusedEditable = findFocusedComposer(root)
                if (focusedEditable != null) {
                    val stillSettling = SystemClock.uptimeMillis() - request.startedAt <
                        FOCUS_RESTORE_SETTLE_MS
                    val matchesOriginalComposer =
                        matchesComposerAnchor(focusedEditable, request.composerAnchor)
                    if (!stillSettling || !matchesOriginalComposer) {
                        // Respect focus the user placed and accept a composer that
                        // remained focused after the send transition settled.
                        cancelComposerFocusRestore()
                        return
                    }
                }

                if (focusedEditable == null && target != null) {
                    if (!request.focusRequested) {
                        request.focusRequested = true
                        if (supportsAction(target, AccessibilityNodeInfo.ACTION_FOCUS)) {
                            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        }
                    } else if (!request.clickRequested &&
                        supportsAction(target, AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        request.clickRequested = true
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }

                if (SystemClock.uptimeMillis() >= request.deadline) {
                    cancelComposerFocusRestore()
                    return
                }

                mainHandler.postDelayed(this, FOCUS_RESTORE_POLL_INTERVAL_MS)
            }
        }
        composerFocusPoll = poll
        mainHandler.post(poll)
    }

    private fun findComposerFocusTarget(
        root: AccessibilityNodeInfo,
        request: ComposerFocusRequest
    ): AccessibilityNodeInfo? {
        if (root.windowId != request.composerAnchor.windowId) return null
        val composer = findUniqueVisibleEditableNode(root) ?: return null
        return composer.takeIf { matchesComposerAnchor(it, request.composerAnchor) }
    }

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

    private fun supportsAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        node.actionList.any { it.id == action }

    private fun cancelComposerFocusRestore() {
        composerFocusPoll?.let(mainHandler::removeCallbacks)
        composerFocusPoll = null
        composerFocusRequest = null
    }

    private fun findRemoteDictationStartTarget(
        composer: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val scope = composer.parent ?: return null
        val semanticTarget = findUniqueIdentityTarget(
            scope,
            DICTATION_START_DESCRIPTIONS,
            DICTATION_START_VIEW_ID_SUFFIXES
        )
        return semanticTarget ?: findUniqueComposerLocalButtonTarget(scope, composer)
    }

    private fun findUniqueDictationStop(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? = findUniqueIdentityTarget(
            root,
            DICTATION_STOP_DESCRIPTIONS,
            DICTATION_STOP_VIEW_ID_SUFFIXES
        )

    private fun findUniqueComposerLocalButtonTarget(
        scope: AccessibilityNodeInfo,
        composer: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo) {
            if (candidates.size > 1) return
            if (node != composer &&
                isClickableActionNode(node) &&
                node.className?.toString() == CLASS_VIEW &&
                node.contentDescription == null &&
                node.viewIdResourceName == null &&
                hasDirectButtonChild(node) &&
                !subtreeHasSendIdentity(node)
            ) {
                candidates += node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
                if (candidates.size > 1) return
            }
        }

        visit(scope)
        return candidates.singleOrNull()
    }

    private fun findUniqueFocusedEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? = findUniqueEditableNode(root, requireFocus = true)

    private fun findFocusedComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && isEditableComposerCandidate(inputFocus, requireFocus = true)) {
            return inputFocus
        }
        return findUniqueFocusedEditableNode(root)
    }

    private fun findUniqueVisibleEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? = findUniqueEditableNode(root, requireFocus = false)

    private fun findUniqueEditableNode(
        root: AccessibilityNodeInfo,
        requireFocus: Boolean
    ): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo) {
            if (matches.size > 1) return
            val isComposerCandidate = isEditableComposerCandidate(node, requireFocus)
            if (isComposerCandidate) matches += node

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

    private fun findUniqueSendButtonNearComposer(
        composer: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var ancestor: AccessibilityNodeInfo? = composer
        repeat(MAX_COMPOSER_ANCESTOR_LEVELS) {
            ancestor = ancestor?.parent
            val scope = ancestor ?: return null
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectIdentityTargets(
                scope,
                null,
                SEND_DESCRIPTIONS,
                SEND_VIEW_ID_SUFFIXES,
                candidates
            )
            when (candidates.size) {
                1 -> return candidates.single()
                in 2..Int.MAX_VALUE -> return null
            }
        }
        return null
    }

    private fun findUniqueIdentityTarget(
        root: AccessibilityNodeInfo,
        descriptions: Set<String>,
        viewIdSuffixes: Set<String>
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectIdentityTargets(root, null, descriptions, viewIdSuffixes, candidates)
        return candidates.singleOrNull()
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
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

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

    private fun subtreeHasSendIdentity(root: AccessibilityNodeInfo): Boolean {
        var found = false

        fun visit(node: AccessibilityNodeInfo) {
            if (found) return
            if (isIdentityBearingActionNode(node) &&
                hasActionIdentity(node, SEND_DESCRIPTIONS, SEND_VIEW_ID_SUFFIXES)
            ) {
                found = true
                return
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
                if (found) return
            }
        }

        visit(root)
        return found
    }

    private fun hasDirectButtonChild(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (child.className?.toString() == CLASS_BUTTON) return true
        }
        return false
    }

    private data class DictationSession(
        val composerAnchor: ComposerAnchor,
        var phase: DictationPhase,
        var deadline: Long,
        var focusRequested: Boolean = false
    )

    private data class ComposerAnchor(
        val windowId: Int,
        val className: String?,
        val viewIdResourceName: String?,
        val ancestorClassNames: List<String?>
    )

    private data class ComposerFocusRequest(
        val composerAnchor: ComposerAnchor,
        val startedAt: Long,
        val deadline: Long,
        var focusRequested: Boolean = false,
        var clickRequested: Boolean = false
    )

    private enum class DictationPhase {
        STARTING,
        RECORDING,
        STOPPING_TO_EDIT,
        STOPPING_TO_SEND
    }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val CLASS_BUTTON = "android.widget.Button"
        private const val CLASS_VIEW = "android.view.View"
        private const val MAX_COMPOSER_ANCESTOR_LEVELS = 5
        private const val MAX_COMPOSER_ANCHOR_ANCESTORS = 3
        private const val TRANSITION_POLL_INTERVAL_MS = 100L
        private const val FOCUS_RESTORE_POLL_INTERVAL_MS = 100L
        private const val FOCUS_RESTORE_SETTLE_MS = 300L
        private const val FOCUS_RESTORE_TIMEOUT_MS = 3_000L
        private const val START_TIMEOUT_MS = 2_500L
        private const val STOP_TIMEOUT_MS = 10_000L

        private val DICTATION_START_DESCRIPTIONS = setOf(
            "Dictate",
            "Start dictation",
            "Voice input",
            "Microphone"
        )
        private val DICTATION_START_VIEW_ID_SUFFIXES = setOf(
            "/dictate",
            "/dictation",
            "/microphone",
            "/voice_input"
        )
        private val DICTATION_STOP_DESCRIPTIONS = setOf(
            "Stop",
            "Stop dictation",
            "Stop recording",
            "Done dictating"
        )
        private val DICTATION_STOP_VIEW_ID_SUFFIXES = setOf(
            "/stop",
            "/stop_dictation",
            "/stop_recording"
        )
        private val SEND_DESCRIPTIONS = setOf("Send", "Send message")
        private val SEND_VIEW_ID_SUFFIXES = setOf(
            "/send",
            "/send_button",
            "/send_message",
            "/send_message_button"
        )
    }
}
