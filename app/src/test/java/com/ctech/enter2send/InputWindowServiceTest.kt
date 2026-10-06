package com.ctech.enter2send

import android.os.Build
import android.os.Handler
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises window selection through the production service's Enter handler. */
class InputWindowServiceTest {
    @Test
    fun unsupportedFocusedWindowDoesNotClickVisibleSupportedBackground() {
        for (sdk in listOf(29, 30)) {
            val background = AppWindow(CHATGPT, 1, focused = false)
            val foreground = AppWindow("com.example.notes", 2)
            val fixture = Fixture(sdk, listOf(background.window, foreground.window))

            assertFalse("API $sdk", fixture.down())
            assertTrue(background.send.performedActionsForTest.isEmpty())
            assertTrue(foreground.send.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun supportedFocusedWindowWinsOverVisibleSupportedBackground() {
        for (sdk in listOf(29, 30)) {
            val background = AppWindow(CHATGPT, 1, focused = false)
            val foreground = AppWindow(MESSENGER, 2)
            val fixture = Fixture(sdk, listOf(background.window, foreground.window))

            assertTrue("API $sdk", fixture.down())
            assertTrue(background.send.performedActionsForTest.isEmpty())
            assertEquals(listOf(Node.ACTION_CLICK), foreground.send.performedActionsForTest)
        }
    }

    @Test
    fun missingOrAmbiguousInputFocusDoesNotClickAnySend() {
        for (sdk in listOf(29, 30)) {
            assertFalse("API $sdk empty windows", Fixture(sdk, emptyList()).down())
            for (focused in listOf(false, true)) {
                val first = AppWindow(CHATGPT, 1, focused)
                val second = AppWindow(MESSENGER, 2, focused)
                val fixture = Fixture(sdk, listOf(first.window, second.window))

                assertFalse("API $sdk focused=$focused", fixture.down())
                assertTrue(first.send.performedActionsForTest.isEmpty())
                assertTrue(second.send.performedActionsForTest.isEmpty())
            }
        }
    }

    @Test
    fun missingOrMismatchedFocusedWindowRootDoesNotClickSend() {
        for (sdk in listOf(29, 30)) {
            val mismatched = AppWindow(CHATGPT, 1)
            mismatched.window.id = 2
            assertFalse("API $sdk mismatched root", Fixture(sdk, listOf(mismatched.window)).down())
            assertTrue(mismatched.send.performedActionsForTest.isEmpty())

            val missing = AppWindow(CHATGPT, 3)
            missing.window.root = null
            assertFalse("API $sdk missing root", Fixture(sdk, listOf(missing.window)).down())
            assertTrue(missing.send.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun api29UsesLegacyWindowsInsteadOfAllDisplayWindows() {
        val legacy = AppWindow(MESSENGER, 1)
        val otherDisplay = AppWindow(CHATGPT, 2)
        val fixture = Fixture(
            29,
            listOf(legacy.window),
            listOf(listOf(otherDisplay.window))
        )

        assertTrue(fixture.down())
        assertEquals(listOf(Node.ACTION_CLICK), legacy.send.performedActionsForTest)
        assertTrue(otherDisplay.send.performedActionsForTest.isEmpty())
    }

    @Test
    fun api30FindsFocusedTargetOnSecondaryDisplayInsteadOfLegacyWindows() {
        val legacy = AppWindow(CHATGPT, 1)
        val background = AppWindow(CHATGPT, 2, focused = false)
        val secondary = AppWindow(MESSENGER, 3)
        val fixture = Fixture(
            30,
            listOf(legacy.window),
            listOf(listOf(background.window), listOf(secondary.window))
        )

        assertTrue(fixture.down())
        assertTrue(legacy.send.performedActionsForTest.isEmpty())
        assertTrue(background.send.performedActionsForTest.isEmpty())
        assertEquals(listOf(Node.ACTION_CLICK), secondary.send.performedActionsForTest)
    }

    @Test
    fun api30RejectsFocusConflictAcrossDisplays() {
        val primary = AppWindow(CHATGPT, 1)
        val secondary = AppWindow(MESSENGER, 2)
        val fixture = Fixture(
            30,
            listOf(primary.window),
            listOf(listOf(primary.window), listOf(secondary.window))
        )

        assertFalse(fixture.down())
        assertTrue(primary.send.performedActionsForTest.isEmpty())
        assertTrue(secondary.send.performedActionsForTest.isEmpty())
    }

    private class Fixture(
        sdk: Int,
        legacyWindows: List<Window>,
        displayWindows: List<List<Window>> = listOf(legacyWindows)
    ) {
        private val service = ChatGptKeyAccessibilityService()

        init {
            Handler.reset()
            Build.VERSION.SDK_INT = sdk
            BridgePreferences.setMasterEnabled(service, true)
            BridgePreferences.setAppEnabled(service, SupportedAppProfiles.chatGpt, true)
            BridgePreferences.setAppEnabled(service, SupportedAppProfiles.messenger, true)
            service.windows = legacyWindows
            service.windowsOnAllDisplays = SparseArray<List<Window>>().apply {
                displayWindows.forEachIndexed { index, windows -> put(index, windows) }
            }
        }

        fun down(): Boolean = ChatGptKeyAccessibilityService::class.java
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
            .apply { isAccessible = true }
            .invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) as Boolean
    }

    private class AppWindow(packageName: String, windowId: Int, focused: Boolean = true) {
        private val root = node(packageName, windowId)
        val send = node(packageName, windowId).apply {
            contentDescription = "Send"
            isClickable = true
            actionList += Node.AccessibilityAction(Node.ACTION_CLICK, null)
        }
        val window = Window().apply {
            root = this@AppWindow.root
            id = windowId
            isFocused = focused
        }

        init {
            root.addChild(node(packageName, windowId).apply {
                className = "android.widget.EditText"
                isEditable = true
                isFocused = true
            }).addChild(send)
        }
    }

    companion object {
        private const val CHATGPT = "com.openai.chatgpt"
        private const val MESSENGER = "com.facebook.orca"

        private fun node(packageName: String, windowId: Int) = Node().apply {
            this.packageName = packageName
            this.windowId = windowId
        }
    }
}
