package com.ctech.enter2send

import android.os.Build
import android.os.Handler
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.WeakReference

/** Runs production service bytecode with deterministic Android API fakes, not an OEM UI. */
class ChatGptComposerLifecycleTest {
    @Test fun separateEnterPressesCannotRetryAnUnclearedSend() = Fixture().run {
        assertTrue(down()); assertTrue(up())
        Handler.tick(0); Handler.tick(100)
        assertFalse(down()); assertFalse(up())
        assertEquals(listOf(Node.ACTION_CLICK), send.performedActionsForTest)
    }

    @Test fun recreatedFocusedComposerDoesNotBypassPendingConfirmation() = Fixture().run {
        down(); up()
        replaceComposer(editable())
        assertFalse(down())
        assertEquals(1, send.performedActionsForTest.size)
    }

    @Test fun changedAncestorsAndReusedClassOrViewIdDoNotTransferFocus() {
        for (id in listOf<String?>(null, "com.openai.chatgpt:id/composer")) Fixture().run {
            composer.viewIdResourceName = id
            down(); up()
            val replacement = editable().apply { isFocused = false; viewIdResourceName = id }
            replaceComposer(replacement, newContainer = true)
            send.isEnabled = false
            Handler.tick(0); Handler.tick(100); Handler.tick(300)
            assertTrue(replacement.performedActionsForTest.isEmpty())
            assertTrue(composer.performedActionsForTest.isEmpty())
        }
    }

    @Test fun sameClassAndAncestorsAreInsufficientForRefocus() = Fixture().run {
        down(); up()
        val replacement = editable().apply { isFocused = false }
        replaceComposer(replacement)
        send.isEnabled = false
        Handler.tick(0); Handler.tick(100); Handler.tick(300)
        assertTrue(replacement.performedActionsForTest.isEmpty())
    }

    @Test fun confirmedClearAllowsExplicitSendFromRecreatedComposer() = Fixture().run {
        down(); up()
        val replacement = editable()
        replaceComposer(replacement, newContainer = true)
        send.isEnabled = false
        Handler.tick(0); Handler.tick(100)
        send.isEnabled = true
        assertTrue(down())
        assertEquals(2, send.performedActionsForTest.size)
        assertTrue(replacement.performedActionsForTest.isEmpty())
    }

    @Test fun originalSourceCanBeReacquiredAndRefocusedAfterClear() = Fixture().run {
        down(); up()
        val snapshot = editable().apply {
            setSourceNodeIdForTest(composer.sourceNodeIdForTest)
            isFocused = false
        }
        replaceComposer(snapshot, newContainer = true)
        send.isEnabled = false
        Handler.tick(0); Handler.tick(100)
        assertEquals(listOf(Node.ACTION_FOCUS), snapshot.performedActionsForTest)
        snapshot.isFocused = true
        Handler.tick(300)
        assertEquals(listOf(Node.ACTION_FOCUS), snapshot.performedActionsForTest)
    }

    @Test fun changedEditableDuringRefocusDoesNotReceiveFallbackClick() = Fixture().run {
        down(); up(); composer.isFocused = false; send.isEnabled = false
        Handler.tick(0); Handler.tick(100)
        assertEquals(listOf(Node.ACTION_FOCUS), composer.performedActionsForTest)
        val replacement = editable().apply { isFocused = false }
        replaceComposer(replacement)
        Handler.tick(300)
        assertTrue(replacement.performedActionsForTest.isEmpty())
    }

    @Test fun releasedSourceSnapshotDoesNotTriggerSpeculativeRefocus() = Fixture().run {
        down(); up()
        val operation = service.javaClass.getDeclaredField("sendOperation").apply { isAccessible = true }.get(service)
        val anchor = operation.javaClass.getDeclaredField("composerAnchor").apply { isAccessible = true }.get(operation)
        val reference = anchor.javaClass.getDeclaredField("node").apply { isAccessible = true }.get(anchor) as WeakReference<*>
        reference.clear() // Deterministic collection; the service must not retain/reidentify the snapshot.
        composer.isFocused = false; send.isEnabled = false
        Handler.tick(0); Handler.tick(100); Handler.tick(300)
        assertTrue(composer.performedActionsForTest.isEmpty())
    }

    @Test fun unrelatedEventRetainsProtectionWhileOriginalWindowOwnsInput() = Fixture().run {
        down(); up()
        service.onAccessibilityEvent(AccessibilityEvent("com.example.ime"))
        assertFalse(down())
        assertEquals(1, send.performedActionsForTest.size)
        send.isEnabled = false; composer.isFocused = false
        Handler.tick(0); Handler.tick(100)
        assertEquals(listOf(Node.ACTION_FOCUS), composer.performedActionsForTest)
    }

    @Test fun appSwitchEventCancelsRestorationBeforeReturningToChatGpt() = Fixture().run {
        down(); up(); send.isEnabled = false; composer.isFocused = false
        setWindows(listOf(window(node("com.example.notes"))))
        service.onAccessibilityEvent(AccessibilityEvent("com.example.notes"))
        setWindows(listOf(window(root)))
        Handler.tick(0); Handler.tick(100); Handler.tick(300)
        assertTrue(composer.performedActionsForTest.isEmpty())
    }

    @Test fun ambiguousComposersDoNotConfirmClearOrReceiveFocus() = Fixture().run {
        down(); up(); send.isEnabled = false; composer.isFocused = false
        val other = editable().apply { isFocused = false }
        root.addChild(other)
        Handler.tick(0); Handler.tick(100); Handler.tick(300)
        assertTrue(composer.performedActionsForTest.isEmpty())
        assertTrue(other.performedActionsForTest.isEmpty())
    }

    @Test fun textIsNeverReadForSendingOrNonComposerFields() {
        for (contents in listOf<String?>(null, "", " \n ", "private synthetic input")) Fixture().run {
            composer.text = contents // getText throws in the fake, even for empty text.
            assertTrue(down()); up()
            assertFalse(down())
            assertEquals(0, composer.textReadCountForTest)
        }
        Fixture().run {
            root.childrenForTest.remove(send)
            composer.text = "synthetic search"
            assertFalse(down())
            assertEquals(0, composer.textReadCountForTest)
        }
    }

    @Test fun disabledSendWithoutTextInspectionPassesThrough() = Fixture().run {
        send.isEnabled = false
        assertFalse(down()); assertFalse(up())
        assertTrue(send.performedActionsForTest.isEmpty())
        assertEquals(0, composer.textReadCountForTest)
    }

    @Test fun timeoutRecoveryDoesNotLeavePermanentLockout() = Fixture().run {
        down(); up(); Handler.tick(3000)
        assertTrue(down())
        assertEquals(2, send.performedActionsForTest.size)
    }

    private class Fixture {
        val root = node()
        val composer = editable()
        val send = node().apply {
            contentDescription = "Send"; isClickable = true
            actionList += action(Node.ACTION_CLICK)
        }
        val service: ChatGptKeyAccessibilityService
        init {
            Handler.reset(); Build.VERSION.SDK_INT = 35
            root.addChild(composer).addChild(send)
            service = ChatGptKeyAccessibilityService()
            BridgePreferences.setMasterEnabled(service, true)
            setWindows(listOf(window(root)))
        }
        fun replaceComposer(replacement: Node, newContainer: Boolean = false) {
            root.childrenForTest.clear()
            if (newContainer) root.addChild(node().apply { className = "different.Container"; addChild(replacement) })
            else root.addChild(replacement)
            root.addChild(send)
        }
        fun setWindows(windows: List<Window>) {
            service.windows = windows
            service.windowsOnAllDisplays = SparseArray<List<Window>>().apply { put(0, windows) }
        }
        fun down() = dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        fun up() = dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        private fun dispatch(event: KeyEvent): Boolean = ChatGptKeyAccessibilityService::class.java
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
            .invoke(service, event) as Boolean
    }
    companion object {
        private fun node(pkg: String = "com.openai.chatgpt") = Node().apply { packageName = pkg }
        private fun editable() = node().apply {
            className = "android.widget.EditText"; isEditable = true; isFocused = true
            actionList += action(Node.ACTION_FOCUS); actionList += action(Node.ACTION_CLICK)
        }
        private fun action(id: Int) = Node.AccessibilityAction(id, null)
        private fun window(node: Node) = Window().apply { root = node; id = node.windowId; isFocused = true }
    }
}
