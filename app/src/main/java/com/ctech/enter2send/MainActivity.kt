package com.ctech.enter2send

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var dictationSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.BLACK)
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.privacy_summary)
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, padding / 2, 0, padding)
        })

        serviceStatus = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
        }
        content.addView(serviceStatus)

        content.addView(Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        masterSwitch = Switch(this).apply {
            text = getString(R.string.master_switch_label)
            isChecked = BridgePreferences.isMasterEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                BridgePreferences.setMasterEnabled(this@MainActivity, enabled)
            }
        }
        content.addView(masterSwitch)

        dictationSwitch = Switch(this).apply {
            text = getString(R.string.dictation_switch_label)
            isChecked = BridgePreferences.isDictationEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                BridgePreferences.setDictationEnabled(this@MainActivity, enabled)
            }
        }
        content.addView(dictationSwitch)

        content.addView(TextView(this).apply {
            text = getString(R.string.key_help)
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, padding, 0, 0)
        })

        setContentView(content)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isBridgeServiceEnabled()
        serviceStatus.text = if (enabled) {
            getString(R.string.service_on)
        } else {
            getString(R.string.service_off)
        }
        serviceStatus.setTextColor(if (enabled) Color.rgb(0, 120, 70) else Color.rgb(180, 45, 35))
    }

    private fun isBridgeServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name == ChatGptKeyAccessibilityService::class.java.name
            }
    }
}
