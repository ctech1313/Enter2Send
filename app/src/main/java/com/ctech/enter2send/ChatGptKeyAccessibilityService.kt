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
            val controls = findActiveDictationControls(root) ?: run {
                resetDictationSession()
                return false
            }
            val focusSurface = session.surface
            val composerAnchor = session.composerAnchor
            val clicked = controls.send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                consumedKeyCode = event.keyCode
                resetDictationSession()
                startComposerFocusRestore(focusSurface, composerAnchor)
            }
            return clicked
        }

        if (session != null) return false

        val composer = findUniqueFocusedEditableNode(root) ?: return false
        val sendButton = findUniqueSendButtonNearComposer(composer) ?: return false
        val focusSurface = surfaceForRoot(root)
        val composerAnchor = createComposerAnchor(composer)
        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            consumedKeyCode = event.keyCode
            startComposerFocusRestore(focusSurface, composerAnchor)
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
            val controls = findActiveDictationControls(root) ?: run {
                resetDictationSession()
                return false
            }
            val clicked = controls.stop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                session.phase = DictationPhase.STOPPING
                session.phaseStartedAt = SystemClock.uptimeMillis()
                session.deadline = session.phaseStartedAt + STOP_TIMEOUT_MS
                session.backAttempted = false
                session.focusRequested = false
                consumedKeyCode = KeyEvent.KEYCODE_F8
                startTransitionPolling()
            }
            return clicked
        }

        val composer = findUniqueFocusedEditableNode(root) ?: return false

        // A visible Send control means the composer already contains user input.
        // F8 must never replace or submit that input.
        if (findUniqueSendButtonNearComposer(composer) != null) return false

        val startTarget = findDictationStartTarget(root, composer) ?: return false
        val clicked = startTarget.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            val now = SystemClock.uptimeMillis()
            dictationSession = DictationSession(
                surface = startTarget.surface,
                composerAnchor = createComposerAnchor(composer),
                phase = DictationPhase.STARTING,
                phaseStartedAt = now,
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
        if (session.phase == DictationPhase.STARTING) {
            if (findActiveDictationControls(root) != null) {
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

                when (session.phase) {
                    DictationPhase.STARTING -> {
                        if (findActiveDictationControls(root) != null) {
                            session.phase = DictationPhase.RECORDING
                            transitionPoll = null
                            return
                        }
                    }

                    DictationPhase.STOPPING -> {
                        if (restoreComposerAfterStop(root, session)) {
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

    private fun restoreComposerAfterStop(
        root: AccessibilityNodeInfo,
        session: DictationSession
    ): Boolean {
        val composer = findUniqueVisibleEditableNode(root)
        if (composer != null && findUniqueSendButtonNearComposer(composer) != null) {
            if (composer.isFocused) return true
            if (!session.focusRequested) {
                session.focusRequested = true
                composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            return findUniqueFocusedEditableNode(rootInActiveWindow ?: return false) != null
        }

        val recordingControlsRemain = findActiveDictationControls(root) != null
        val backDelayElapsed =
            SystemClock.uptimeMillis() - session.phaseStartedAt >= REMOTE_BACK_DELAY_MS
        if (session.surface == DictationSurface.REMOTE &&
            !recordingControlsRemain &&
            !session.backAttempted &&
            backDelayElapsed
        ) {
            session.backAttempted = true
            performGlobalAction(GLOBAL_ACTION_BACK)
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

    private fun startComposerFocusRestore(
        surface: DictationSurface,
        composerAnchor: ComposerAnchor
    ) {
        cancelComposerFocusRestore()
        val now = SystemClock.uptimeMillis()
        val request = ComposerFocusRequest(
            surface = surface,
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

                val target = findComposerFocusTarget(root, request)
                val focusedEditable = findUniqueFocusedEditableNode(root)
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
        if (surfaceForRoot(root) != request.surface) return null
        val composer = findUniqueVisibleEditableNode(root) ?: return null
        return composer.takeIf { matchesComposerAnchor(it, request.composerAnchor) }
    }

    private fun createComposerAnchor(composer: AccessibilityNodeInfo): ComposerAnchor =
        ComposerAnchor(
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

    private fun surfaceForRoot(root: AccessibilityNodeInfo): DictationSurface =
        if (hasRemoteSurfaceIdentity(root)) {
            DictationSurface.REMOTE
        } else {
            DictationSurface.NORMAL_CHAT
        }

    private fun supportsAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        node.actionList.any { it.id == action }

    private fun cancelComposerFocusRestore() {
        composerFocusPoll?.let(mainHandler::removeCallbacks)
        composerFocusPoll = null
        composerFocusRequest = null
    }

    private fun findDictationStartTarget(
        root: AccessibilityNodeInfo,
        composer: AccessibilityNodeInfo
    ): DictationStartTarget? {
        val remoteSurface = hasRemoteSurfaceIdentity(root)
        return if (remoteSurface) {
            findRemoteDictationStartTarget(composer)?.let {
                DictationStartTarget(DictationSurface.REMOTE, it)
            }
        } else {
            findNormalChatDictationStartTarget(composer)?.let {
                DictationStartTarget(DictationSurface.NORMAL_CHAT, it)
            }
        }
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

    private fun findNormalChatDictationStartTarget(
        composer: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var ancestor: AccessibilityNodeInfo? = composer
        repeat(MAX_COMPOSER_ANCESTOR_LEVELS) {
            ancestor = ancestor?.parent
            val scope = ancestor ?: return null
            val semanticTarget = findUniqueIdentityTarget(
                scope,
                DICTATION_START_DESCRIPTIONS,
                DICTATION_START_VIEW_ID_SUFFIXES
            )
            if (semanticTarget != null) return semanticTarget
        }

        val immediateScope = composer.parent ?: return null
        return findUniqueComposerLocalButtonTarget(immediateScope, composer)
    }

    private fun findActiveDictationControls(
        root: AccessibilityNodeInfo
    ): ActiveDictationControls? {
        if (findUniqueVisibleEditableNode(root) != null) return null

        val stop = findUniqueIdentityTarget(
            root,
            DICTATION_STOP_DESCRIPTIONS,
            DICTATION_STOP_VIEW_ID_SUFFIXES
        ) ?: return null
        val send = findUniqueIdentityTarget(
            root,
            SEND_DESCRIPTIONS,
            SEND_VIEW_ID_SUFFIXES
        ) ?: return null
        if (stop == send || !shareNearbyAncestor(stop, send)) return null
        return ActiveDictationControls(stop, send)
    }

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

    private fun hasRemoteSurfaceIdentity(root: AccessibilityNodeInfo): Boolean {
        var found = false

        fun visit(node: AccessibilityNodeInfo) {
            if (found) return
            if (node.packageName?.toString() == CHATGPT_PACKAGE &&
                node.isVisibleToUser &&
                node.isEnabled &&
                !node.isEditable &&
                (node.isClickable || node.className?.toString() == CLASS_BUTTON)
            ) {
                val description = node.contentDescription?.toString()?.trim()
                val viewId = node.viewIdResourceName?.lowercase()
                found = description != null && REMOTE_SURFACE_DESCRIPTIONS.any {
                    it.equals(description, ignoreCase = true)
                } || viewId != null && REMOTE_SURFACE_VIEW_ID_SUFFIXES.any(viewId::endsWith)
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
                if (found) return
            }
        }

        visit(root)
        return found
    }

    private fun findUniqueFocusedEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? = findUniqueEditableNode(root, requireFocus = true)

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
            val isComposerCandidate = node.packageName?.toString() == CHATGPT_PACKAGE &&
                node.isVisibleToUser &&
                node.isEnabled &&
                node.isEditable &&
                (!requireFocus || node.isFocused)
            if (isComposerCandidate) matches += node

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
                if (matches.size > 1) return
            }
        }

        visit(root)
        return matches.singleOrNull()
    }

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

    private fun shareNearbyAncestor(
        first: AccessibilityNodeInfo,
        second: AccessibilityNodeInfo
    ): Boolean {
        val firstAncestors = mutableListOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = first
        var depth = 0
        while (current != null && depth++ <= MAX_CONTROL_ANCESTOR_LEVELS) {
            firstAncestors += current
            current = current.parent
        }

        current = second
        depth = 0
        while (current != null && depth++ <= MAX_CONTROL_ANCESTOR_LEVELS) {
            if (firstAncestors.any { it == current }) return true
            current = current.parent
        }
        return false
    }

    private data class DictationStartTarget(
        val surface: DictationSurface,
        val node: AccessibilityNodeInfo
    )

    private data class ActiveDictationControls(
        val stop: AccessibilityNodeInfo,
        val send: AccessibilityNodeInfo
    )

    private data class DictationSession(
        val surface: DictationSurface,
        val composerAnchor: ComposerAnchor,
        var phase: DictationPhase,
        var phaseStartedAt: Long,
        var deadline: Long,
        var backAttempted: Boolean = false,
        var focusRequested: Boolean = false
    )

    private data class ComposerAnchor(
        val className: String?,
        val viewIdResourceName: String?,
        val ancestorClassNames: List<String?>
    )

    private data class ComposerFocusRequest(
        val surface: DictationSurface,
        val composerAnchor: ComposerAnchor,
        val startedAt: Long,
        val deadline: Long,
        var focusRequested: Boolean = false,
        var clickRequested: Boolean = false
    )

    private enum class DictationSurface {
        NORMAL_CHAT,
        REMOTE
    }

    private enum class DictationPhase {
        STARTING,
        RECORDING,
        STOPPING
    }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val CLASS_BUTTON = "android.widget.Button"
        private const val CLASS_VIEW = "android.view.View"
        private const val MAX_COMPOSER_ANCESTOR_LEVELS = 5
        private const val MAX_COMPOSER_ANCHOR_ANCESTORS = 3
        private const val MAX_CONTROL_ANCESTOR_LEVELS = 5
        private const val TRANSITION_POLL_INTERVAL_MS = 100L
        private const val FOCUS_RESTORE_POLL_INTERVAL_MS = 100L
        private const val FOCUS_RESTORE_SETTLE_MS = 300L
        private const val FOCUS_RESTORE_TIMEOUT_MS = 3_000L
        private const val START_TIMEOUT_MS = 2_500L
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val REMOTE_BACK_DELAY_MS = 500L

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
        private val REMOTE_SURFACE_DESCRIPTIONS = setOf(
            "Remote",
            "Close Remote",
            "Exit Remote"
        )
        private val REMOTE_SURFACE_VIEW_ID_SUFFIXES = setOf(
            "/remote",
            "/remote_header",
            "/remote_mode"
        )
    }
}
