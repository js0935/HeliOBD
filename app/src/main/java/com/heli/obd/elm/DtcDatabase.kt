/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * DTC 描述資料庫查詢層：打包於 assets 的 dtc_codes.db
 * （MIT 授權 Wal33D/dtc-database，28,000+ 碼，通用碼約 4,000 筆）。
 */
object DtcDatabase {

    private const val DB_NAME = "dtc_codes.db"

    @Volatile
    private var db: SQLiteDatabase? = null

    /** 首次呼叫時把 assets 的資料庫複製到 app databases 目錄並唯讀開啟。 */
    fun ensureReady(context: Context) {
        if (db != null) return
        synchronized(this) {
            if (db != null) return
            try {
                val appCtx = context.applicationContext
                val dbFile = appCtx.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) {
                    dbFile.parentFile?.mkdirs()
                    appCtx.assets.open(DB_NAME).use { input ->
                        dbFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (_: Exception) {
                db = null
            }
        }
    }

    /**
     * 查通用（generic）DTC 描述。
     * 查詢順序比照 Car Scanner DescriptionLoader：先精確比對，未命中時降級查
     * 前 5 字元（ISO 15031-6 標準碼長度）的家族描述；未收錄或 db 未就緒回傳 null。
     */
    fun description(code: String): String? {
        val database = db ?: return null
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return null
        return try {
            queryDescription(database, normalized)
                ?: if (normalized.length > 5) {
                    queryDescription(database, normalized.substring(0, 5))
                } else {
                    null
                }
        } catch (_: Exception) {
            null
        }
    }

    /** 精確查詢單一 code 的通用描述；無結果回傳 null */
    private fun queryDescription(database: SQLiteDatabase, code: String): String? {
        return database.rawQuery(
            "SELECT description FROM dtc_definitions WHERE code = ? AND is_generic = 1 LIMIT 1",
            arrayOf(code),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
