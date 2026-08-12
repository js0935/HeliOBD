# HeliOBD ProGuard / R8 規則
#
# 啟用 R8（isMinifyEnabled = true）後生效。
# 原則：只保留「透過字串/反射/XML 間接引用」的類別，其餘交由 R8 自動裁減。

# ---- 自訂 View（XML 佈局實例化，需保留建構子） ----
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---- 藍牙隱藏 API 反射入口（DeviceReflection 以方法名字串呼叫系統類別） ----
-keep class com.heli.obd.elm.DeviceReflection { *; }

# ---- 授權套件（LicenseActivity 為獨立 Activity；保留類別名便於 Crash 追蹤） ----
-keep class com.heli.obd.license.** { *; }

# ---- 自訂 PID 公式引擎（PidEvaluator 以 JSON 字串建構，保留為安全性） ----
-keep class com.heli.obd.pid.** { *; }

# ---- VW TP 2.0 通訊協定（VwtpSession 內部以協定編號反射派發） ----
-keep class com.heli.obd.vwtp.** { *; }
