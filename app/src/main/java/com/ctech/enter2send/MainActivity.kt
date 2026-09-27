package com.ctech.enter2send

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var serviceSummary: TextView
    private lateinit var masterSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
        }

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        content.addView(textView(getString(R.string.app_name), 30f, COLOR_INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(textView(getString(R.string.app_tagline), 17f, COLOR_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), 0)
        })
        content.addView(textView(getString(R.string.privacy_summary), 14f, COLOR_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(24))
        })

        content.addView(sectionCard().apply {
            serviceStatus = textView("", 19f, COLOR_INK, true)
            addView(serviceStatus)

            serviceSummary = textView("", 14f, COLOR_MUTED).apply {
                setPadding(0, dp(6), 0, dp(14))
            }
            addView(serviceSummary)

            addView(Button(this@MainActivity).apply {
                text = getString(R.string.open_accessibility_settings)
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(COLOR_PRIMARY)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

            addView(textView(getString(R.string.background_setup_summary), 14f, COLOR_MUTED).apply {
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.open_app_settings)
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        })

        content.addView(sectionCard().apply {
            masterSwitch = Switch(this@MainActivity).apply {
                text = getString(R.string.master_switch_label)
                textSize = 17f
                setTextColor(COLOR_INK)
                isChecked = BridgePreferences.isMasterEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, enabled ->
                    BridgePreferences.setMasterEnabled(this@MainActivity, enabled)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(masterSwitch)
            addView(textView(getString(R.string.master_switch_summary), 14f, COLOR_MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        })

        content.addView(sectionHeading(getString(R.string.supported_apps_label)))
        SupportedAppProfiles.all.forEach { profile ->
            content.addView(appCard(profile))
        }

        content.addView(sectionHeading(getString(R.string.keyboard_controls_label)))
        content.addView(sectionCard().apply {
            addView(textView(getString(R.string.key_help), 15f, COLOR_INK))
        })

        content.addView(sectionHeading(getString(R.string.safety_label)))
        content.addView(sectionCard().apply {
            addView(textView(getString(R.string.safety_summary), 14f, COLOR_MUTED))
            addView(textView(getString(R.string.native_setting_note), 14f, COLOR_PRIMARY, true).apply {
                setPadding(0, dp(12), 0, 0)
            })
        })

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isBridgeServiceEnabled()
        serviceStatus.text = getString(if (enabled) R.string.service_on else R.string.service_off)
        serviceStatus.setTextColor(if (enabled) COLOR_SUCCESS else COLOR_ERROR)
        serviceSummary.text = getString(
            if (enabled) R.string.service_on_summary else R.string.service_off_summary
        )
    }

    private fun appCard(profile: SupportedAppProfile): View =
        sectionCard(compact = true).apply {
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                textView(profile.displayName, 17f, COLOR_INK, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(Switch(this@MainActivity).apply {
                contentDescription = profile.displayName
                isChecked = BridgePreferences.isAppEnabled(this@MainActivity, profile)
                setOnCheckedChangeListener { _, enabled ->
                    BridgePreferences.setAppEnabled(this@MainActivity, profile, enabled)
                }
            })
            addView(header)
            addView(textView(getString(profile.summaryResId), 14f, COLOR_MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun sectionHeading(text: String): TextView =
        textView(text, 16f, COLOR_INK, true).apply {
            setPadding(dp(2), dp(18), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun sectionCard(compact: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(if (compact) 14 else 18), dp(18), dp(if (compact) 14 else 18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), COLOR_BORDER)
            }
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

    private fun textView(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isBridgeServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name ==
                    ChatGptKeyAccessibilityService::class.java.name
            }
    }

    companion object {
        private val COLOR_BACKGROUND = Color.rgb(246, 247, 251)
        private val COLOR_INK = Color.rgb(24, 32, 52)
        private val COLOR_MUTED = Color.rgb(89, 98, 119)
        private val COLOR_PRIMARY = Color.rgb(79, 70, 229)
        private val COLOR_SUCCESS = Color.rgb(0, 128, 88)
        private val COLOR_ERROR = Color.rgb(190, 45, 55)
        private val COLOR_BORDER = Color.rgb(226, 229, 238)
    }
}
