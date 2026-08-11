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
| ECU 模組掃描（11-bit CAN header 探測） | 完成 |
| O2/EVAP 測試（Mode 05 / Mode 08） | 完成 |
| 多階段測試助理（RPM 階梯/燃油修正/O2 響應） | 完成 |
| 驗車準備（I/M 監測器判決 + 驅動週期引導） | 完成 |
| 車況報告（彙整分享 + AI 診斷提示詞） | 完成 |
| 引擎聲浪模擬 | 完成 |
| AI 診斷（自訂規則引擎） | 完成 |
| 行程回顧（含 GPS 軌跡、油耗估算、CSV 匯出） | 完成 |
| 數據對比 | 完成 |
| 多車管理 | 完成 |
| 抬頭顯示（HUD 大字模式） | 完成 |
| 閾值警示（水溫/轉速/電壓） | 完成 |
| 自訂 PID（公式引擎 + 車廠專用感測器） | 完成 |
| 加速測試（0-100 / 0-60 / 1/4 英里） | 完成 |
| 馬力/扭力估算（Dyno） | 完成 |
| 即時油耗（含加油校準） | 完成 |
| 數據錄製 / 回放（JSON/CSV） | 完成 |
| 回放進階分析（參數多選/縮放/游標讀值/直方圖） | 完成 |
| 語音警示開關 | 完成 |
| 日夜模式（深色/淺色/跟隨系統） | 完成 |
| 甩尾圓環（G 值量測） | 完成 |
| 故障碼資料庫（160+ 碼含維修建議） | 完成 |
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
│   ├── ObdDecoder.kt       回應解碼（RPM/車速/水溫/電壓/DTC/O2/EVAP）
│   ├── ObdConstants.kt     PID 與 DTC 描述表（含 ECU header / Mode 05 / Mode 08）
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
    ├── DynoActivity.kt       馬力/扭力估算
    ├── LiveFuelActivity.kt   即時油耗（含加油校準）
    ├── DataLoggerActivity.kt 數據錄製（JSON/CSV）
    ├── DataReplayActivity.kt 數據回放（曲線圖）
    ├── SkidPadActivity.kt    甩尾圓環 G 值量測
    ├── SkidPadView.kt        圓環軌跡繪圖 View
    ├── EcuScanActivity.kt    ECU 模組掃描
    ├── O2EvapActivity.kt     O2/EVAP 測試
    ├── StageTestActivity.kt 多階段測試助理
    ├── SmogCheckActivity.kt 驗車準備（I/M 判決 + 驅動週期）
    ├── VehicleReportActivity.kt 車況報告（分享 / AI 提示詞）
    ├── TripTrackView.kt      軌跡繪圖 View
    └── FeaturePlaceholderActivity.kt  占位
```

## 現況

全功能已交付並通過 `assembleDebug` 建置。主畫面包含 28 個功能入口；
支援日夜模式（設定 → 外觀模式：深色/淺色/跟隨系統）；
模擬模式提供完整體驗（無需 ELM327 硬體）。
