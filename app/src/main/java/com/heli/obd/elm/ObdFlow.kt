/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 以 Kotlin Flow 包裝 [ObdManager] 的事件串流。
 *
 * 與舊的 Listener API 並存（零破壞）：收集 Flow 期間自動註冊 / 移除 listener，
 * 供 ViewModel / Compose 等現代 UI 層以 `collect` 訂閱。
 *
 * 範例：
 * ```kotlin
 * scope.launch { manager.obdStateFlow().collect { state -> ... } }
 * ```
 */

/** 連線狀態串流：初次收集立即發出目前狀態（行為等同 addListener 的 notifyState）。 */
fun ObdManager.obdStateFlow(): Flow<ObdManager.State> = callbackFlow {
    val listener = object : ObdManager.Listener {
        override fun onStateChanged(state: ObdManager.State) {
            trySend(state)
        }

        override fun onLiveData(data: ObdManager.LiveData) {
            // 本串流只訂閱狀態
        }
    }
    addListener(listener)
    awaitClose { removeListener(listener) }
}

/** 即時數據串流：每次輪詢結果（僅在連線/模擬模式進行中發出）。 */
fun ObdManager.obdLiveDataFlow(): Flow<ObdManager.LiveData> = callbackFlow {
    val listener = object : ObdManager.Listener {
        override fun onStateChanged(state: ObdManager.State) {
            // 本串流只訂閱即時數據
        }

        override fun onLiveData(data: ObdManager.LiveData) {
            trySend(data)
        }
    }
    addListener(listener)
    awaitClose { removeListener(listener) }
}
