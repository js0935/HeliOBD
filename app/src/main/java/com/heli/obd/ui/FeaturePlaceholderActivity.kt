/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import com.heli.obd.BaseActivity
import com.heli.obd.R

/**
 * 功能開發中占位畫面：顯示功能名稱與說明（功能本體逐版實作中）。
 */
class FeaturePlaceholderActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feature_placeholder)

        findViewById<ImageView>(R.id.feature_icon)
            .setImageResource(intent.getIntExtra(EXTRA_ICON, R.drawable.ic_sound))
        findViewById<TextView>(R.id.feature_title)
            .setText(intent.getIntExtra(EXTRA_TITLE, R.string.app_name))
        findViewById<TextView>(R.id.feature_desc)
            .setText(intent.getIntExtra(EXTRA_DESC, 0))

        findViewById<TextView>(R.id.feature_status).apply {
            setText(R.string.feature_wip)
            setTextColor(getColor(R.color.text_secondary))
        }
    }

    companion object {
        const val EXTRA_ICON = "feature_icon"
        const val EXTRA_TITLE = "feature_title"
        const val EXTRA_DESC = "feature_desc"
    }
}
