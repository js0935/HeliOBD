# HeliOBD

汽機車 OBD-II 車機檢測 App（Android 8.0+，直向介面）。

## 下載 APK

最新安裝檔：**`HeliOBD.apk`**（repo 根目錄，與最新 Release 同步）；亦可到 [Releases](https://github.com/js0935/HeliOBD/releases) 下載版本化安裝檔。

## 授權系統

App 內建 RSA 離線授權（與 PC 工具 LicenseKeyGenUI 配對），可選擇性套用：
- **目前入口已解鎖**：所有功能直接開放，主畫面不再強制驗證授權。
- 授權套件（`license/`）完整保留，可隨時重新啟用功能閘門。
- 已簽發的有效授權金鑰（`OBD-...QIDAQAB`）持續有效，LicenseKeyGenUI 配對流程不變。

## 編譯

```
gradlew.bat assembleDebug
```

APK 輸出：`app/build/outputs/apk/debug/app-debug.apk`

## 功能總覽

| 功能 | 狀態 |
|---|---|
| 即時數據（轉速/車速/水溫/電壓） | 完成 |
| 故障碼讀取 / 清除 | 完成 |
| 引擎聲浪模擬 | 完成 |
| AI 診斷（自訂規則引擎） | 完成 |
| 行程回顧（含 GPS 軌跡、油耗估算、CSV 匯出） | 完成 |
| 數據對比 | 完成 |
| 多車管理 | 完成 |
| 抬頭顯示（HUD 大字模式） | 完成 |
| 閾值警示（水溫/轉速/電壓） | 完成 |
| 自訂 PID（公式引擎 + 車廠專用感測器） | 完成 |
| 加速測試（0-100 / 0-60 / 1/4 英里） | 完成 |
| 模擬模式（無 OBD 硬體體驗全部功能） | 完成 |

## 結構

```
app/src/main/java/com/heli/obd/
├── App.kt                  Application：LicenseManager 全域單例（含公鑰）
├── MainActivity.kt         主畫面：功能入口 + ObdManager 全域單例
├── license/                授權套件（AndroidLicenseKit 整合）
│   ├── LicenseValidator.kt 金鑰解析 + RSA 簽章驗證（純 JVM 邏輯）
│   ├── LicenseManager.kt   授權狀態機 + 功能閘門
│   ├── LicenseStore.kt     SharedPreferences 持久化 + 時間倒退偵測
│   ├── DeviceId.kt         ANDROID_ID → SHA-256 設備碼（32 hex）
│   └── LicenseActivity.kt  授權管理畫面
├── elm/                    ELM327 藍牙層
│   ├── ObdManager.kt       掃描/連線/AT 指令/輪詢/故障碼/模擬模式
│   ├── ObdDecoder.kt       回應解碼（RPM/車速/水溫/電壓/DTC）
│   ├── ObdConstants.kt     PID 與 DTC 描述表
│   ├── BtPermissions.kt    藍牙權限（8–11 與 12+）
│   ├── AlertMonitor.kt     閾值警示（水溫/轉速/電壓 + 音效/震動）
│   └── DemoConfig.kt       模擬模式全域開關（SharedPreferences）
├── pid/                    自訂 PID
│   ├── PidEvaluator.kt     公式引擎（A/B/C/D 變數 + 四則運算）
│   └── PidStore.kt         JSON 持久化（files/custom_pids.json）
├── trip/                   行程記錄
│   └── TripRecorder.kt     JSON 儲存 + GPS 軌跡 + 油耗估算 + CSV 匯出
└── ui/
    ├── ObdMonitorActivity.kt  即時數據儀表
    ├── DtcActivity.kt         故障碼
    ├── EngineSoundActivity.kt 引擎聲浪
    ├── AiDiagnoseActivity.kt  AI 診斷
    ├── TripActivity.kt        行程回顧（軌跡檢視 / CSV 分享）
    ├── CompareActivity.kt     數據對比
    ├── VehiclesActivity.kt    多車管理
    ├── HudActivity.kt         抬頭顯示
    ├── AlertsActivity.kt      閾值警示設定
    ├── CustomPidActivity.kt   自訂 PID
    ├── AccelerationActivity.kt 加速測試
    ├── TripTrackView.kt       軌跡繪圖 View
    └── FeaturePlaceholderActivity.kt  占位
```

## 現況

全功能已交付並通過 `assembleDebug` 建置。主畫面包含 17 個功能入口；
模擬模式提供完整體驗（無需 ELM327 硬體）。
