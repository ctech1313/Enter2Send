package com.ctech.enter2send

import android.content.Context

object BridgePreferences {
    private const val FILE_NAME = "bridge_preferences"
    private const val KEY_ENABLED = "master_enabled"
    private const val KEY_DICTATION_ENABLED = "dictation_enabled"

    fun isMasterEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isDictationEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DICTATION_ENABLED, false)

    fun setDictationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DICTATION_ENABLED, enabled)
            .apply()
    }
}
