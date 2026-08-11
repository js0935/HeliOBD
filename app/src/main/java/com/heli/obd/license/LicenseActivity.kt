/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.license

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.App
import com.heli.obd.R

/**
 * 授權管理畫面：顯示設備碼、輸入 OBD- 授權碼啟用、檢視已解鎖功能、移除授權。
 */
class LicenseActivity : BaseActivity() {

    private val app get() = application as App
    private val license get() = app.license

    private lateinit var deviceIdText: TextView
    private lateinit var statusText: TextView
    private lateinit var licenseInput: EditText
    private lateinit var featuresContainer: LinearLayout
    private lateinit var deactivateBtn: Button

    /** 功能位元 → 字串資源 */
    private val featureEntries = listOf(
        LicenseValidator.FEAT_ENGINE_SOUND to R.string.feat_engine_sound,
        LicenseValidator.FEAT_AI_DIAGNOSIS to R.string.feat_ai_diag,
        LicenseValidator.FEAT_TRIP_REVIEW to R.string.feat_trip_review,
        LicenseValidator.FEAT_DATA_COMPARE to R.string.feat_data_compare,
        LicenseValidator.FEAT_MULTI_CAR to R.string.feat_multi_car,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)

        deviceIdText = findViewById(R.id.device_id_text)
        statusText = findViewById(R.id.status_text)
        licenseInput = findViewById(R.id.license_input)
        featuresContainer = findViewById(R.id.features_container)
        deactivateBtn = findViewById(R.id.btn_deactivate)

        deviceIdText.text = license.deviceId()

        findViewById<Button>(R.id.btn_copy_id).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("device_id", license.deviceId())
            )
            Toast.makeText(this, R.string.license_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_activate).setOnClickListener {
            val key = licenseInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.license_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = license.activate(key)
            when (result) {
                LicenseManager.Status.VALID -> {
                    Toast.makeText(this, R.string.license_status_valid, Toast.LENGTH_SHORT).show()
                    licenseInput.text?.clear()
                }
                LicenseManager.Status.EXPIRED -> showError(R.string.license_status_expired)
                LicenseManager.Status.DEVICE_MISMATCH -> showError(R.string.license_status_mismatch)
                LicenseManager.Status.INVALID_SIGNATURE -> showError(R.string.license_status_invalid)
                LicenseManager.Status.TIME_ROLLBACK -> showError(R.string.license_status_rollback)
                LicenseManager.Status.NOT_ACTIVATED -> Unit
            }
            refresh()
        }

        deactivateBtn.setOnClickListener {
            license.deactivate()
            refresh()
        }

        refresh()
    }

    private fun showError(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val status = license.status()
        val (textRes, colorRes) = when (status) {
            LicenseManager.Status.VALID -> R.string.license_status_valid to R.color.success
            LicenseManager.Status.EXPIRED -> R.string.license_status_expired to R.color.danger
            LicenseManager.Status.DEVICE_MISMATCH -> R.string.license_status_mismatch to R.color.lock
            LicenseManager.Status.INVALID_SIGNATURE -> R.string.license_status_invalid to R.color.danger
            LicenseManager.Status.TIME_ROLLBACK -> R.string.license_status_rollback to R.color.danger
            LicenseManager.Status.NOT_ACTIVATED -> R.string.license_status_none to R.color.lock
        }
        statusText.setText(textRes)
        statusText.setTextColor(getColor(colorRes))
        deactivateBtn.visibility = if (status == LicenseManager.Status.NOT_ACTIVATED) View.GONE else View.VISIBLE

        // 功能清單
        featuresContainer.removeAllViews()
        featureEntries.forEach { (bit, labelRes) ->
            val enabled = status == LicenseManager.Status.VALID && license.isFeatureEnabled(bit)
            val row = TextView(this).apply {
                text = (if (enabled) "✓ " else "✗ ") + getString(labelRes)
                setTextColor(getColor(if (enabled) R.color.success else R.color.text_secondary))
                textSize = 15f
                setPadding(0, dp(4), 0, dp(4))
            }
            featuresContainer.addView(row)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
