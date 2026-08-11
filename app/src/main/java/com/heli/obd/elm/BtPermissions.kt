/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 藍牙權限輔助（Android 8–11 與 12+ 權限模型不同）。
 */
object BtPermissions {

    /** 連線所需權限清單（依系統版本） */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /** 是否已取得全部藍牙權限 */
    fun hasAll(context: Context): Boolean =
        required().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}
