package com.ctech.enter2send

import android.os.Build
import android.os.Handler
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercises event-time cancellation before polling can observe a temporary ownership change. */
@RunWith(Parameterized::class)
class ConfirmationWindowEventsTest(
    private val packageName: String,
    private val phase: Phase
) {
    @Test fun backgroundSupportedAppEventPreservesProtectionAndRefocus() = Fixture().run {
        beginOperation()
        val backgroundPackage = SupportedAppProfiles.all.first { it.packageName != packageName }.packageName
        service.onAccessibilityEvent(AccessibilityEvent(backgroundPackage))

        assertFalse("The uncleared operation must still block another send", down())
        assertFalse(up())
        assertEquals(listOf(Node.ACTION_CLICK), send.performedActionsForTest)

        send.isEnabled = false
        composer.isFocused = false
        Handler.tick(0)
        Handler.tick(100)
        Handler.tick(300)
        assertEquals(listOf(Node.ACTION_FOCUS, Node.ACTION_CLICK), composer.performedActionsForTest)
    }

    @Test fun samePackageWindowSwitchCancelsBeforeReturning() = assertEventCancels {
        setWindows(listOf(window(otherRoot())))
    }

    @Test fun missingWindowCancelsBeforeReturning() = assertEventCancels {
        setWindows(emptyList())
    }

    @Test fun missingInputFocusCancelsBeforeReturning() = assertEventCancels {
        setWindows(listOf(window(root).apply { isFocused = false }))
    }

    @Test fun ambiguousInputFocusCancelsBeforeReturning() = assertEventCancels {
        setWindows(listOf(window(root), window(otherRoot())))
    }

    @Test fun masterDisableCancelsBeforeReenabling() = assertEventCancels {
        BridgePreferences.setMasterEnabled(service, false)
    }

    @Test fun profileDisableCancelsBeforeReenabling() = assertEventCancels {
        BridgePreferences.setAppEnabled(service, profile, false)
    }

    private fun assertEventCancels(disrupt: Fixture.() -> Unit) = Fixture().run {
        beginOperation()
        val actionsBeforeEvent = composer.performedActionsForTest.toList()
        disrupt()
        // Use the operation's own package: event provenance cannot establish input ownership.
        service.onAccessibilityEvent(AccessibilityEvent(packageName))

        // Recover before the first/next poll, so polling alone cannot make this assertion pass.
        BridgePreferences.setMasterEnabled(service, true)
        BridgePreferences.setAppEnabled(service, profile, true)
        setWindows(listOf(window(root)))
        send.isEnabled = false
        composer.isFocused = false
        Handler.tick(0)
        Handler.tick(100)
        Handler.tick(300)

        assertEquals("Cancelled operations must not restore focus or click", actionsBeforeEvent,
            composer.performedActionsForTest)
        assertEquals(listOf(Node.ACTION_CLICK), send.performedActionsForTest)
    }

    private inner class Fixture {
        val profile = checkNotNull(SupportedAppProfiles.forPackage(packageName))
        val root = node()
        val composer = node().apply {
            className = "android.widget.EditText"
            isEditable = true
            isFocused = true
            actionList += action(Node.ACTION_FOCUS)
            actionList += action(Node.ACTION_CLICK)
        }
        val send = node().apply {
            contentDescription = "Send"
            isClickable = true
            actionList += action(Node.ACTION_CLICK)
        }
        val service: ChatGptKeyAccessibilityService

        init {
            Handler.reset()
            Build.VERSION.SDK_INT = 35
            root.addChild(composer).addChild(send)
            addRequiredMarker(root)
            service = ChatGptKeyAccessibilityService()
            BridgePreferences.setMasterEnabled(service, true)
            BridgePreferences.setAppEnabled(service, profile, true)
            setWindows(listOf(window(root)))
        }

        fun beginOperation() {
            assertTrue(down())
            assertTrue(up())
            assertEquals(listOf(Node.ACTION_CLICK), send.performedActionsForTest)
            if (phase == Phase.REFOCUSING) {
                send.isEnabled = false
                composer.isFocused = false
                Handler.tick(0)
                Handler.tick(100)
                // The fake leaves focus unchanged, making the later click fallback observable.
                assertEquals(listOf(Node.ACTION_FOCUS), composer.performedActionsForTest)
            } else {
                assertTrue(composer.performedActionsForTest.isEmpty())
            }
        }

        fun otherRoot(): Node = node().apply {
            windowId = 2
            addRequiredMarker(this)
        }

        private fun addRequiredMarker(target: Node) {
            if (profile == SupportedAppProfiles.claudeRemoteControl) {
                target.addChild(node().apply {
                    windowId = target.windowId
                    contentDescription = "Change mode"
                })
            }
        }

        fun setWindows(windows: List<Window>) {
            service.windows = windows
            service.windowsOnAllDisplays = SparseArray<List<Window>>().apply { put(0, windows) }
        }

        fun down(): Boolean = dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        fun up(): Boolean = dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        private fun dispatch(event: KeyEvent): Boolean = ChatGptKeyAccessibilityService::class.java
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
            .invoke(service, event) as Boolean

        private fun node(): Node = Node().apply { this.packageName = this@ConfirmationWindowEventsTest.packageName }
    }

    enum class Phase { AWAITING_CLEAR, REFOCUSING }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1}")
        fun cases(): Collection<Array<Any>> = SupportedAppProfiles.all.flatMap { profile ->
            Phase.values().map { phase -> arrayOf<Any>(profile.packageName, phase) }
        }

        private fun action(id: Int) = Node.AccessibilityAction(id, null)
        private fun window(root: Node) = Window().apply {
            this.root = root
            id = root.windowId
            isFocused = true
        }
    }
}
