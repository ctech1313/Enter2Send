package com.ctech.enter2send

import java.util.Locale

data class SupportedAppProfile(
    val displayName: String,
    val summaryResId: Int,
    val packageName: String,
    internal val preferenceKey: String,
    internal val enabledByDefault: Boolean,
    private val sendDescriptions: Set<String>,
    private val sendViewIdSuffixes: Set<String>,
    private val requiredWindowIdentities: Set<String> = emptySet()
) {
    internal val requiresWindowIdentity: Boolean
        get() = requiredWindowIdentities.isNotEmpty()

    fun hasSendIdentity(
        contentDescription: CharSequence?,
        viewIdResourceName: String?
    ): Boolean {
        val description = contentDescription?.toString()?.trim()
        if (description != null && sendDescriptions.any {
                it.equals(description, ignoreCase = true)
            }
        ) {
            return true
        }

        val viewId = viewIdResourceName?.lowercase(Locale.ROOT) ?: return false
        return sendViewIdSuffixes.any(viewId::endsWith)
    }

    fun hasRequiredWindowIdentity(contentDescription: CharSequence?): Boolean {
        val description = contentDescription?.toString()?.trim() ?: return false
        return requiredWindowIdentities.any {
            it.equals(description, ignoreCase = true)
        }
    }
}

object SupportedAppProfiles {
    val chatGpt = SupportedAppProfile(
        displayName = "ChatGPT",
        summaryResId = R.string.chatgpt_summary,
        packageName = "com.openai.chatgpt",
        preferenceKey = "app_chatgpt_enabled",
        enabledByDefault = true,
        sendDescriptions = setOf("Send", "Send message", "Enviar", "Enviar mensagem", "Senden"),
        sendViewIdSuffixes = setOf(
            "/send",
            "/send_button",
            "/send_message",
            "/send_message_button"
        )
    )

    val messenger = SupportedAppProfile(
        displayName = "Messenger",
        summaryResId = R.string.messenger_summary,
        packageName = "com.facebook.orca",
        preferenceKey = "app_messenger_enabled",
        enabledByDefault = false,
        sendDescriptions = setOf("Send", "Send message"),
        sendViewIdSuffixes = setOf(
            "/send",
            "/send_button",
            "/send_message",
            "/send_message_button"
        )
    )

    val claudeRemoteControl = SupportedAppProfile(
        displayName = "Claude Remote Control",
        summaryResId = R.string.claude_remote_summary,
        packageName = "com.anthropic.claude",
        preferenceKey = "app_claude_remote_control_enabled",
        enabledByDefault = false,
        sendDescriptions = setOf("Send"),
        sendViewIdSuffixes = emptySet(),
        requiredWindowIdentities = setOf("Change mode")
    )

    val all: List<SupportedAppProfile> = listOf(chatGpt, messenger, claudeRemoteControl)

    fun forPackage(packageName: String?): SupportedAppProfile? =
        all.singleOrNull { it.packageName == packageName }
}
