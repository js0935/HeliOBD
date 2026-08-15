/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.view.View
import android.widget.TextView

/**
 * 按鈕「執行中」回饋 helper：
 * 暫時禁用指定的 View（按鈕／chips）並（可選）把文字改為「讀取中…」等忙碌提示，
 * 工作完成後呼叫 [Handle.done] 自動恢復原狀態——同時防止工作進行中重複點擊。
 */
object BusyUi {

    /** 進入忙碌狀態；回傳的 handle 需在工作完成時呼叫 done() 恢復。 */
    fun mark(views: List<View>, busyText: String? = null): Handle {
        val saved = views.map { view ->
            Triple(view, view.isEnabled, (view as? TextView)?.text?.toString() ?: "")
        }
        views.forEach { view ->
            view.isEnabled = false
            if (busyText != null) (view as? TextView)?.text = busyText
        }
        return Handle {
            saved.forEach { (view, enabled, text) ->
                view.isEnabled = enabled
                if (text.isNotEmpty()) (view as? TextView)?.text = text
            }
        }
    }

    /** 單一 View 版本。 */
    fun mark(view: View, busyText: String? = null): Handle = mark(listOf(view), busyText)

    /** 忙碌狀態的解除把手（冪等，重複呼叫 done() 只恢復一次）。 */
    class Handle(private val restore: () -> Unit) {
        private var done = false
        fun done() {
            if (done) return
            done = true
            restore()
        }
    }
}
