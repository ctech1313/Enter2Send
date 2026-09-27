package com.ctech.enter2send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedAppProfilesTest {
    @Test
    fun resolvesSupportedPackages() {
        assertSame(
            SupportedAppProfiles.chatGpt,
            SupportedAppProfiles.forPackage("com.openai.chatgpt")
        )
        assertSame(
            SupportedAppProfiles.messenger,
            SupportedAppProfiles.forPackage("com.facebook.orca")
        )
        assertSame(
            SupportedAppProfiles.claudeRemoteControl,
            SupportedAppProfiles.forPackage("com.anthropic.claude")
        )
        assertNull(SupportedAppProfiles.forPackage("com.example.other"))
        assertNull(SupportedAppProfiles.forPackage(null))
    }

    @Test
    fun keepsAdditionalAppsOptInWithoutChangingChatGptDefault() {
        assertTrue(SupportedAppProfiles.chatGpt.enabledByDefault)
        assertFalse(SupportedAppProfiles.messenger.enabledByDefault)
        assertFalse(SupportedAppProfiles.claudeRemoteControl.enabledByDefault)
    }

    @Test
    fun matchesOnlyExactSemanticSendDescriptions() {
        val profile = SupportedAppProfiles.messenger

        assertTrue(profile.hasSendIdentity("Send", null))
        assertTrue(profile.hasSendIdentity(" send message ", null))
        assertFalse(profile.hasSendIdentity("Send Like", null))
        assertFalse(profile.hasSendIdentity("Resend", null))
    }

    @Test
    fun chatGptMatchesLocalizedSendButNotOtherComposerActions() {
        val profile = SupportedAppProfiles.chatGpt
        assertTrue(profile.hasSendIdentity("Enviar", null))
        assertTrue(profile.hasSendIdentity("Enviar mensagem", null))
        assertTrue(profile.hasSendIdentity("Senden", null))
        assertFalse(profile.hasSendIdentity("Stop generating", null))
        assertFalse(profile.hasSendIdentity("Voice", null))
        assertFalse(profile.hasSendIdentity("Attach", null))
        assertFalse(profile.hasSendIdentity("Regenerate", null))
    }

    @Test
    fun matchesOnlyKnownSendViewIdSuffixes() {
        val profile = SupportedAppProfiles.messenger

        assertTrue(profile.hasSendIdentity(null, "com.facebook.orca:id/send_button"))
        assertTrue(profile.hasSendIdentity(null, "COM.FACEBOOK.ORCA:ID/SEND_MESSAGE"))
        assertFalse(profile.hasSendIdentity(null, "com.facebook.orca:id/send_like_button"))
        assertFalse(profile.hasSendIdentity(null, "com.facebook.orca:id/search"))
    }

    @Test
    fun limitsClaudeToItsExactRemoteControlSemantics() {
        val profile = SupportedAppProfiles.claudeRemoteControl

        assertTrue(profile.hasSendIdentity("Send", null))
        assertFalse(profile.hasSendIdentity("Send message", null))
        assertFalse(profile.hasSendIdentity("Send now", null))
        assertFalse(profile.hasSendIdentity("Stop", null))
        assertFalse(profile.hasSendIdentity(null, "com.anthropic.claude:id/send_button"))

        assertTrue(profile.requiresWindowIdentity)
        assertTrue(profile.hasRequiredWindowIdentity("Change mode"))
        assertTrue(profile.hasRequiredWindowIdentity(" change MODE "))
        assertFalse(profile.hasRequiredWindowIdentity("Remote control"))
        assertFalse(profile.hasRequiredWindowIdentity("Voice Mode"))
        assertFalse(profile.hasRequiredWindowIdentity(null))
    }

    @Test
    fun doesNotRequireWindowMarkersForExistingProfiles() {
        assertFalse(SupportedAppProfiles.chatGpt.requiresWindowIdentity)
        assertFalse(SupportedAppProfiles.messenger.requiresWindowIdentity)
        assertFalse(SupportedAppProfiles.chatGpt.hasRequiredWindowIdentity("Change mode"))
    }

    @Test
    fun containsExactlyTheDeclaredSupportedApps() {
        assertEquals(
            setOf("com.openai.chatgpt", "com.facebook.orca", "com.anthropic.claude"),
            SupportedAppProfiles.all.map { it.packageName }.toSet()
        )
    }
}
