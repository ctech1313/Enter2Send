package com.ctech.enter2send

import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the compiled production service on deterministic JVM Android API fakes.
 * These are behavioral regression tests; an instrumented OEM accessibility stack
 * remains responsible for validating device-specific node trees.
 */
class ChatGptKeyAccessibilityServiceTest {
    @Test
    fun diagnosticsAreDebugOnlyAndNeverIncludeComposerText() {
        Fixture().apply {
            val privateDraft = "private draft for diagnostics regression"
            composer.text = privateDraft
            Log.messagesForTest.clear()

            assertTrue(down())
            if (BuildConfig.DEBUG) {
                assertTrue(Log.messagesForTest.any { "Enter down" in it })
                assertTrue(Log.messagesForTest.any { "Send click result=true" in it })
            } else {
                assertTrue("Release must not log keyboard or send activity", Log.messagesForTest.isEmpty())
            }
            assertFalse(Log.messagesForTest.any { privateDraft in it })
        }
    }

    @Test
    fun clickActionLabelIsTheOnlyAcceptedActionLabel() {
        for (packageName in supportedPackages) {
            Fixture(packageName).apply {
                button.contentDescription = null
                button.actionList.clear()
                button.actionList += action(AccessibilityNodeInfo.ACTION_CLICK, "Send")

                assertTrue("$packageName CLICK label", down())
                assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
            }
        }
    }

    @Test
    fun nonSendClickSemanticCannotBorrowSendFromLongClick() {
        for (packageName in supportedPackages) {
            for (description in listOf("Stop", "Voice", "Resend")) {
                Fixture(packageName).apply {
                    button.contentDescription = description
                    button.actionList += action(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Send")

                    assertFalse("$packageName $description plus LONG_CLICK Send", down())
                    assertTrue(button.performedActionsForTest.isEmpty())
                }
            }
        }
    }

    @Test
    fun conflictingSemanticSignalsFailOpen() {
        for (packageName in supportedPackages) {
            Fixture(packageName).apply {
                button.contentDescription = "Stop"
                button.actionList.clear()
                button.actionList += action(AccessibilityNodeInfo.ACTION_CLICK, "Send")
                assertFalse("$packageName Stop description plus CLICK Send", down())
            }

            Fixture(packageName).apply {
                button.contentDescription = "Send"
                button.actionList.clear()
                button.actionList += action(AccessibilityNodeInfo.ACTION_CLICK, "Stop")
                assertFalse("$packageName Send description plus CLICK Stop", down())
            }

            Fixture(packageName).apply {
                button.contentDescription = "Stop"
                button.viewIdResourceName = "$packageName:id/send"
                assertFalse("$packageName Stop description plus send view id", down())
            }
        }
    }

    @Test
    fun nestedSendIconTargetsItsClickableAncestor() {
        Fixture().apply {
            button.contentDescription = null
            button.actionList.single().let { check(it.id == AccessibilityNodeInfo.ACTION_CLICK) }
            button.addChild(node().apply {
                className = "android.widget.ImageView"
                contentDescription = "Send"
            })

            assertTrue(down())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
        }
    }

    @Test
    fun invalidSemanticNodesFormTraversalBarriers() {
        val invalidNodes = listOf<(AccessibilityNodeInfo) -> Unit>(
            { it.isEnabled = false },
            { it.isVisibleToUser = false },
            { it.isEditable = true },
            { it.packageName = "com.example.foreign" }
        )

        invalidNodes.forEachIndexed { index, invalidate ->
            Fixture().apply {
                button.contentDescription = null
                val barrier = node().also(invalidate)
                barrier.addChild(node().apply {
                    className = "android.widget.ImageView"
                    contentDescription = "Send"
                })
                button.addChild(barrier)

                assertFalse("invalid semantic barrier $index", down())
                assertTrue(button.performedActionsForTest.isEmpty())
            }
        }
    }

    @Test
    fun interveningControlsAndUnknownSemanticWrappersBlockAncestorBinding() {
        Fixture().apply {
            button.contentDescription = null
            val interveningControl = node().apply {
                isClickable = true
                actionList += action(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            interveningControl.addChild(node().apply { contentDescription = "Send" })
            button.addChild(interveningControl)

            assertFalse("clickable control without ACTION_CLICK", down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }

        Fixture().apply {
            button.contentDescription = null
            val actionBearingNonControl = node().apply {
                isClickable = false
                actionList += action(AccessibilityNodeInfo.ACTION_CLICK, "Send")
            }
            button.addChild(actionBearingNonControl)

            assertFalse("ACTION_CLICK support without clickable state", down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }

        Fixture().apply {
            button.contentDescription = null
            val unknownWrapper = node().apply { contentDescription = "More options" }
            unknownWrapper.addChild(node().apply { contentDescription = "Send" })
            button.addChild(unknownWrapper)

            assertFalse("explicit unknown semantic wrapper", down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }

        Fixture().apply {
            button.contentDescription = "More options"
            button.viewIdResourceName = "com.openai.chatgpt:id/send"

            assertFalse("unknown description plus send view id", down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun duplicateSemanticChildrenResolveToOnePhysicalTarget() {
        Fixture().apply {
            button.contentDescription = null
            button.addChild(node().apply { contentDescription = "Send" })
            button.addChild(node().apply { contentDescription = "Send" })

            assertTrue(down())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
        }
    }

    @Test
    fun conflictingSiblingSemanticsRejectSharedTargetInEitherOrder() {
        for (packageName in supportedPackages) {
            for (conflict in listOf("Stop", "Voice", "Resend")) {
                for (sendFirst in listOf(true, false)) {
                    Fixture(packageName).apply {
                        button.contentDescription = null
                        val sendNode = node(packageName).apply {
                            className = "android.widget.ImageView"
                            contentDescription = "Send"
                        }
                        val conflictNode = node(packageName).apply {
                            className = "android.widget.ImageView"
                            contentDescription = conflict
                        }
                        if (sendFirst) {
                            button.addChild(sendNode).addChild(conflictNode)
                        } else {
                            button.addChild(conflictNode).addChild(sendNode)
                        }

                        assertFalse(
                            "$packageName $conflict sendFirst=$sendFirst",
                            down()
                        )
                        assertTrue(button.performedActionsForTest.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun conflictingChildRejectsParentSendViewId() {
        Fixture().apply {
            button.contentDescription = null
            button.viewIdResourceName = "com.openai.chatgpt:id/send_button"
            button.addChild(node().apply {
                className = "android.widget.ImageView"
                contentDescription = "Stop"
            })

            assertFalse(down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun independentlyClickableConflictDoesNotRejectOuterSendTarget() {
        Fixture().apply {
            val nestedStop = clickableNode("Stop")
            button.addChild(nestedStop)

            assertTrue(down())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
            assertTrue(nestedStop.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun laterConflictCanReduceTwoProvisionalTargetsToOne() {
        Fixture().apply {
            button.contentDescription = null
            button.addChild(node().apply { contentDescription = "Send" })
            val survivingTarget = clickableNode("Send")
            button.addChild(survivingTarget)
            button.addChild(node().apply { contentDescription = "Stop" })

            assertTrue(down())
            assertTrue(button.performedActionsForTest.isEmpty())
            assertEquals(
                listOf(AccessibilityNodeInfo.ACTION_CLICK),
                survivingTarget.performedActionsForTest
            )
        }
    }

    @Test
    fun laterConflictsCanReduceTwoProvisionalTargetsToNone() {
        Fixture().apply {
            button.contentDescription = null
            button.addChild(node().apply { contentDescription = "Send" })
            val nestedTarget = clickableNode("Send").apply {
                addChild(node().apply { contentDescription = "Stop" })
            }
            button.addChild(nestedTarget)
            button.addChild(node().apply { contentDescription = "Stop" })

            assertFalse(down())
            assertTrue(button.performedActionsForTest.isEmpty())
            assertTrue(nestedTarget.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun twoPhysicalSendTargetsAreAmbiguous() {
        Fixture().apply {
            root.addChild(clickableNode("Send"))

            assertFalse(down())
            assertTrue(button.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun chatGptAllowsEightComposerAncestors() {
        Fixture().apply {
            putButtonAtComposerAncestorLevel(8)

            assertTrue(down())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
        }
    }

    @Test
    fun otherProfilesStopAfterFiveComposerAncestors() {
        for (packageName in listOf("com.facebook.orca", "com.anthropic.claude")) {
            Fixture(packageName).apply {
                putButtonAtComposerAncestorLevel(6)

                assertFalse("$packageName must not search a sixth ancestor", down())
                assertTrue(button.performedActionsForTest.isEmpty())
            }
        }
    }

    @Test
    fun masterAndPerAppPreferencesFailOpenToNativeEnter() {
        Fixture().apply {
            BridgePreferences.setMasterEnabled(service, false)
            assertFalse(down())
        }

        Fixture().apply {
            BridgePreferences.setAppEnabled(service, profile, false)
            assertFalse(down())
        }
    }

    @Test
    fun claudeRequiresRemoteControlWindowMarker() {
        Fixture("com.anthropic.claude").apply {
            root.childrenForTest.remove(marker)
            assertFalse(down())
        }

        Fixture("com.anthropic.claude").apply {
            assertTrue(down())
        }
    }

    @Test
    fun enterRepeatsAndKeyUpRemainPairedToOneSuccessfulClick() {
        Fixture().apply {
            assertTrue(down())
            assertTrue(down(repeatCount = 1))
            assertTrue(up())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
        }
    }

    @Test
    fun ctrlEnterAndNumpadEnterAreHandledWhileShiftEnterIsNative() {
        Fixture().apply {
            assertTrue(down(ctrl = true))
            assertTrue(up())
        }

        Fixture().apply {
            assertTrue(down(keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER))
            assertTrue(up(KeyEvent.KEYCODE_NUMPAD_ENTER))
        }

        Fixture().apply {
            assertFalse(down(shift = true))
            assertFalse(up())
            assertTrue(button.performedActionsForTest.isEmpty())
        }
    }

    @Test
    fun failedClickDoesNotConsumeDownRepeatOrUp() {
        Fixture().apply {
            button.setActionResultForTest(false)

            assertFalse(down())
            assertFalse(down(repeatCount = 1))
            assertFalse(up())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.performedActionsForTest)
        }
    }

    private class Fixture(
        packageName: String = "com.openai.chatgpt"
    ) {
        val profile = checkNotNull(SupportedAppProfiles.forPackage(packageName))
        val root = node(packageName)
        val composer = node(packageName).apply {
            className = "android.widget.EditText"
            isEditable = true
            isFocused = true
            actionList += action(AccessibilityNodeInfo.ACTION_FOCUS)
            actionList += action(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val button = clickableNode("Send", packageName)
        val marker = node(packageName).apply { contentDescription = "Change mode" }
        val service: ChatGptKeyAccessibilityService

        init {
            Handler.reset()
            Build.VERSION.SDK_INT = 35
            root.addChild(composer).addChild(button)
            if (profile === SupportedAppProfiles.claudeRemoteControl) root.addChild(marker)

            service = ChatGptKeyAccessibilityService()
            BridgePreferences.setMasterEnabled(service, true)
            BridgePreferences.setAppEnabled(service, profile, true)
            setWindows(listOf(window(root)))
        }

        fun putButtonAtComposerAncestorLevel(level: Int) {
            root.childrenForTest.clear()
            root.addChild(button)
            if (profile === SupportedAppProfiles.claudeRemoteControl) root.addChild(marker)
            var holder = root
            repeat(level - 1) {
                val container = node(profile.packageName)
                holder.addChild(container)
                holder = container
            }
            holder.addChild(composer)
        }

        fun down(
            keyCode: Int = KeyEvent.KEYCODE_ENTER,
            shift: Boolean = false,
            ctrl: Boolean = false,
            repeatCount: Int = 0
        ): Boolean = dispatch(KeyEvent(KeyEvent.ACTION_DOWN, keyCode, repeatCount, shift, ctrl))

        fun up(keyCode: Int = KeyEvent.KEYCODE_ENTER): Boolean =
            dispatch(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        private fun dispatch(event: KeyEvent): Boolean =
            ChatGptKeyAccessibilityService::class.java
                .getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
                .apply { isAccessible = true }
                .invoke(service, event) as Boolean

        private fun setWindows(windows: List<AccessibilityWindowInfo>) {
            service.windows = windows
            service.windowsOnAllDisplays = SparseArray<List<AccessibilityWindowInfo>>().apply {
                put(0, windows)
            }
        }
    }

    companion object {
        private val supportedPackages = listOf(
            "com.openai.chatgpt",
            "com.facebook.orca",
            "com.anthropic.claude"
        )

        private fun node(packageName: String = "com.openai.chatgpt") =
            AccessibilityNodeInfo().apply { this.packageName = packageName }

        private fun clickableNode(
            description: String,
            packageName: String = "com.openai.chatgpt"
        ) = node(packageName).apply {
            contentDescription = description
            isClickable = true
            actionList += action(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun action(id: Int, label: String? = null) =
            AccessibilityNodeInfo.AccessibilityAction(id, label)

        private fun window(root: AccessibilityNodeInfo) =
            AccessibilityWindowInfo().apply {
                this.root = root
                id = root.windowId
                isFocused = true
            }
    }
}
