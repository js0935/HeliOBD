/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vwtp

import android.content.Context
import org.json.JSONObject

/**
 * VW TP 2.0（VAG）感測器公式儲存：從 assets 讀取 vwtp_formulas.json。
 *
 * 資料來源：MotoDiag VWTPFormulaManager.cs 公式表（181 個 case，163 個有定義，
 * 其餘缺號原始 C# 即無 case）。公式表為唯讀，與 APK 一起打包。
 */
class VwtpFormulaStore(private val context: Context) {

    /** 讀取公式表；assets 缺失或 JSON 損壞時回傳空表（呼叫端自行處理）。 */
    fun load(): Map<Int, VwtpFormulaEngine.Formula> {
        return runCatching {
            val text = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            VwtpFormulaEngine.fromJsonObject(JSONObject(text))
        }.getOrElse { emptyMap() }
    }

    private companion object {
        const val FILE_NAME = "vwtp_formulas.json"
    }
}
