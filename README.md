# HeliOBD

**汽機車 OBD-II 車機檢測 App**（Android 8.0+，直向介面）

HeliOBD 是一套專為汽機車維修與車主設計的 OBD-II 診斷工具，透過藍牙 ELM327 轉接器連接車輛 ECU，提供從即時數據、故障碼判讀、專業排放測試到性能量測的完整檢測功能。內建 **28,000+ 筆故障碼資料庫**（含維修建議）與**模擬模式**——即使沒有 OBD 硬體也能完整體驗全部功能。

---

## 目錄

- [功能特色](#功能特色)
- [快速開始](#快速開始)
- [使用指南](#使用指南)
- [技術架構](#技術架構)
- [技術規格](#技術規格)
- [授權系統](#授權系統)
- [版本歷史](#版本歷史)
- [常見問題](#常見問題)

---

## 功能特色

主畫面共 **31 個功能入口**，分六大類：

### 即時診斷

| 功能 | 說明 |
|---|---|
| 即時數據 | 轉速、車速、水溫、電壓等 PID 即時顯示 |
| 故障碼 | 讀取與清除故障碼，28,000+ 筆資料庫查詢維修建議 |
| ECU 掃描 | 以 11-bit CAN header 探測掃描車上所有 ECU 模組 |
| 健康檢查 | 即時數據分析，各系統綠 / 黃 / 紅健康評分 |

### 專業檢測

| 功能 | 說明 |
|---|---|
| O2/EVAP 測試 | 氧感測器（Mode 05）與蒸發排放系統（Mode 08）測試 |
| 多階段測試 | 結構化診斷測試流程（RPM 階梯 / 燃油修正 / O2 響應） |
| 驗車準備 | 排放監測器（I/M）狀態判決 + 驅動週期引導 |
| 連線診斷 | Adapter 版本、供電電壓、通訊協定一鍵檢測 |
| 專業診斷 | VIN / 校正 ID / CVN、排放就緒、凍結幀、Mode 06 監控測試、三態故障碼對照 |
| 閾值警示 | 水溫、轉速、電壓超限即時提醒（音效 + 震動） |

### 數據分析

| 功能 | 說明 |
|---|---|
| 數據錄製 / 回放 | 錄製行車數據（JSON/CSV）並回放曲線 |
| 數據曲線 | 即時曲線圖，多參數同步顯示 |
| 數據對比 | 多筆數據對比，掌握車輛狀態趨勢 |
| 行程回顧 | 完整記錄每次旅程（GPS 軌跡、油耗估算、CSV 匯出） |
| 車況報告 | 彙整診斷結果，一鍵分享 + AI 診斷提示詞 |
| AI 診斷 | 自訂規則引擎分析診斷數據，找出問題根源 |

### 性能測量

| 功能 | 說明 |
|---|---|
| 加速測試 | 0-100 / 0-60 / 1/4 英里計時，記錄最佳成績 |
| 馬力 / 扭力 | 即時功率與加速性能推算（Dyno） |
| 即時油耗 | 即時燃油率顯示 + 加油校準 |
| 油耗統計 | 動 / 靜態油耗分離與油錢計算 |
| 甩尾圓環（G 值） | 加速度計量測橫向 / 縱向 G 值，圓環軌跡繪圖 |
| 駕駛評分 | 評分騎乘風格，改善駕駛習慣 |

### 車輛管理

| 功能 | 說明 |
|---|---|
| 多車管理 | 一台裝置管理多台愛車 |
| 保養提醒 | 保養里程與電池健康監控 |
| 自訂 PID | 公式引擎（A/B/C/D 變數 + 四則運算），新增車廠專用感測器 |
| VW TP 2.0 | VAG 感測器公式表瀏覽與模擬計算 |
| 抬頭顯示 | 夜間騎乘大字顯示，安全不分心 |

### 工具與擴充

| 功能 | 說明 |
|---|---|
| 引擎聲浪 | 模擬排氣聲浪，享受騎乘樂趣 |
| 連線設定 | 自訂 ELM327 初始化指令 |
| OBD 終端機 | 手動輸入 AT / UDS 指令，即時查看回應 |
| 模擬模式 | 無硬體也能體驗全部功能 |

---

## 快速開始

### 下載安裝

最新安裝檔：**`HeliOBD.apk`**（repo 根目錄，與最新 Release 同步）；亦可到 [Releases](https://github.com/js0935/HeliOBD/releases) 下載版本化安裝檔。

### 從原始碼編譯

環境需求：

- JDK 17+
- Android SDK（compileSdk 35）
- Android Studio（建議）

```
gradlew.bat assembleDebug
```

APK 輸出：`app/build/outputs/apk/debug/app-debug.apk`

### 需要的硬體

- 藍牙 **ELM327** 相容 OBD-II 轉接器（藍牙 SPP 介面）
- 汽機車 OBD-II 診斷座（一般位於儀表板下方或椅座旁）

---

## 使用指南

### 連線流程

1. 將 ELM327 轉接器插入車輛 OBD-II 診斷座
2. 開啟手機藍牙並與轉接器配對
3. 開啟 App → 主畫面右上角狀態膠囊顯示連線狀態（灰 = 未連線、綠 = 已連線、橘 = 模擬模式）
4. 進入「即時數據」→ 選擇轉接器連線，即可開始讀取

### 模擬模式

無需任何硬體即可體驗全部功能：

- 主畫面 →「模擬模式」入口進入（或於連線失敗時切換）
- 模擬模式提供真實的 PID 響應與故障碼情境，適合學習 OBD-II 原理或展示功能
- 每次啟動 App 時模擬模式自動重置為關閉

### 日夜模式

設定 → 外觀模式：**深色 / 淺色 / 跟隨系統**。深色模式為車機場景設計的高對比色票（深底青橘配色），淺色模式為日間戶外閱讀調整。

### 故障碼判讀

讀取故障碼後，App 會自動查詢內建資料庫（28,000+ 筆，含車廠專用碼）顯示**故障說明與維修建議**；未收錄的碼提供通用說明。資料庫離線使用，不需網路連線。

---

## 技術架構

```
app/src/main/java/com/heli/obd/
├── App.kt                  Application：LicenseManager 全域單例（含公鑰）
├── BaseActivity.kt         共用基底（主題 / 返回 / 螢幕常亮）
├── MainActivity.kt         主畫面：31 功能入口 + ObdManager 全域單例
├── diag/                   診斷引擎
│   ├── DiagnosisEngine.kt  AI 診斷規則引擎
│   └── HealthCheckEngine.kt 健康檢查評分引擎（綠 / 黃 / 紅）
├── elm/                    ELM327 藍牙通訊層
│   ├── ObdManager.kt       掃描 / 連線 / AT 指令 / 輪詢 / 故障碼 / 模擬模式
│   ├── ObdDecoder.kt       回應解碼（RPM / 車速 / 水溫 / 電壓 / DTC / O2 / EVAP）
│   ├── ObdConstants.kt     PID 與 DTC 描述表（含 ECU header / Mode 05 / Mode 08）
│   ├── DtcDatabase.kt      DTC 描述資料庫查詢層（assets/dtc_codes.db）
│   ├── DeviceReflection.kt 轉接器自我偵測
│   ├── BtPermissions.kt    藍牙權限（8–11 與 12+）
│   ├── AlertMonitor.kt     閾值警示（水溫 / 轉速 / 電壓 + 音效 / 震動）
│   └── DemoConfig.kt       模擬模式全域開關（SharedPreferences）
├── license/                授權套件（AndroidLicenseKit 整合）
│   ├── LicenseValidator.kt 金鑰解析 + RSA 簽章驗證（純 JVM 邏輯）
│   ├── LicenseManager.kt   授權狀態機 + 功能閘門
│   ├── LicenseStore.kt     SharedPreferences 持久化 + 時間倒退偵測
│   ├── DeviceId.kt         ANDROID_ID → SHA-256 設備碼（32 hex）
│   └── LicenseActivity.kt  授權管理畫面
├── maintenance/            保養提醒
│   └── MaintenanceStore.kt 保養里程 / 電池健康持久化
├── pid/                    自訂 PID
│   ├── PidEvaluator.kt     公式引擎（A/B/C/D 變數 + 四則運算）
│   └── PidStore.kt         JSON 持久化（files/custom_pids.json）
├── scoring/                駕駛評分
│   └── DrivingScoreEngine.kt 騎乘風格評分引擎
├── trip/                   行程記錄
│   ├── TripRecorder.kt     JSON 儲存 + GPS 軌跡 + 油耗估算 + CSV 匯出
│   └── FuelCalibration.kt  加油校準
├── vehicles/               多車管理
│   └── VehicleStore.kt     車輛資料持久化
├── vwtp/                   VW TP 2.0
│   ├── VwtpProtocol.kt     感測器通訊協定封裝
│   ├── VwtpSession.kt      感測器工作階段
│   ├── VwtpFormulaEngine.kt 感測器公式引擎
│   ├── VwtpFormulaStore.kt 公式表持久化
│   └── VwtpUnitSymbols.kt  單位符號表
└── ui/                     功能畫面
    ├── ObdMonitorActivity.kt  即時數據儀表
    ├── DtcActivity.kt         故障碼
    ├── EcuScanActivity.kt     ECU 模組掃描
    ├── HealthCheckActivity.kt 健康檢查
    ├── O2EvapActivity.kt      O2 / EVAP 測試
    ├── StageTestActivity.kt   多階段測試助理
    ├── SmogCheckActivity.kt   驗車準備（I/M 判決 + 驅動週期）
    ├── ConnectionDiagActivity.kt 連線診斷（Adapter 版本 / 電壓 / 協定）
    ├── ProDiagActivity.kt   專業診斷（VIN / 排放就緒 / 凍結幀 / Mode 06 / 故障碼對照）
    ├── DataLoggerActivity.kt 數據錄製（JSON / CSV）
    ├── DataReplayActivity.kt 數據回放（曲線圖）
    ├── ChartActivity.kt      數據曲線
    ├── RealtimeChartActivity.kt 即時曲線圖
    ├── CompareActivity.kt    數據對比
    ├── TripActivity.kt       行程回顧（軌跡檢視 / CSV 分享）
    ├── VehicleReportActivity.kt 車況報告（分享 / AI 提示詞）
    ├── AiDiagnoseActivity.kt AI 診斷
    ├── AccelerationActivity.kt 加速測試
    ├── DynoActivity.kt       馬力 / 扭力估算
    ├── LiveFuelActivity.kt   即時油耗（含加油校準）
    ├── SkidPadActivity.kt    甩尾圓環 G 值量測
    ├── DrivingScoreActivity.kt 駕駛評分
    ├── VehiclesActivity.kt   多車管理
    ├── MaintenanceActivity.kt 保養提醒
    ├── CustomPidActivity.kt  自訂 PID
    ├── VwtpSensorsActivity.kt VW TP 2.0 感測器表
    ├── AlertsActivity.kt     閾值警示設定
    ├── HudActivity.kt        抬頭顯示
    ├── EngineSoundActivity.kt 引擎聲浪
    ├── TerminalActivity.kt   OBD 終端機（AT / UDS）
    ├── SettingsActivity.kt   設定（日夜模式 / 語音警示 / 連線）
    ├── SplashActivity.kt     啟動畫面
    ├── FeaturePlaceholderActivity.kt  占位
    ├── UnitSystem.kt         單位制換算
    └── 繪圖元件：GaugeView / MonitorTiles / ChartView / DataChartView / TripChartView / TripTrackView / SkidPadView
```

### 分層設計

| 層級 | 模組 | 職責 |
|---|---|---|
| **UI 層** | `ui/`、`MainActivity`、`BaseActivity` | 畫面呈現、使用者互動、測試流程引導 |
| **診斷引擎層** | `diag/`、`scoring/` | AI 診斷規則、健康評分、駕駛評分邏輯 |
| **通訊層** | `elm/ObdManager` | 藍牙掃描 / 連線 / AT 指令發送 / PID 輪詢 / 模擬模式資料流 |
| **解碼層** | `elm/ObdDecoder`、`ObdConstants` | ELM327 回應解析、PID 公式換算、ECU header 管理 |
| **資料層** | `DtcDatabase`、`pid/`、`trip/`、`vehicles/`、`maintenance/`、`vwtp/` | 故障碼庫 / 自訂 PID / 行程 / 車輛 / 保養 / 感測器公式持久化 |
| **授權層** | `license/` | RSA 離線授權驗證與功能閘門 |

---

## 技術規格

| 項目 | 值 |
|---|---|
| 最低 Android | 8.0（API 26） |
| 目標 Android | 15（API 35，compileSdk 35） |
| 語言 | Kotlin（JVM 17） |
| 藍牙 | ELM327 相容轉接器（SPP） |
| 通訊 | 標準 OBD-II Mode 01/02/03/05/06/08/0A + AT 指令（OBD 終端機支援 UDS） |
| 資料庫 | SQLite（assets 打包，28,000+ 筆 DTC 定義） |
| 依賴 | androidx.core-ktx 1.15、appcompat 1.7、constraintlayout 2.2、lifecycle 2.8 |

---

## 授權系統

App 內建 RSA 離線授權（與 PC 工具 LicenseKeyGenUI 配對），可選擇性套用：

- **目前入口已解鎖**：所有功能直接開放，主畫面不再強制驗證授權。
- 授權套件（`license/`）完整保留，可隨時重新啟用功能閘門。
- 已簽發的有效授權金鑰（`OBD-...QIDAQAB`）持續有效，LicenseKeyGenUI 配對流程不變。

---

## 版本歷史

| 版本 | 內容 |
|---|---|
| `0e36fa6` | 主畫面升級：品牌 Hero 區、OBD 狀態膠囊、精緻入口網格 |
| `d69165e` | 設計系統現代化：扁平化按鈕 + 按壓回饋、卡片 soft shadow、晝夜色票 |
| `c835125` | 連線診斷畫面；DTC 資料庫擴充至 28,000+ 碼（含維修建議） |
| `69ad08b` | 驗車準備（I/M 判決 + 驅動週期引導）、車況報告（分享 / AI 提示詞） |
| `9bfd0ec` | ECU 掃描、O2/EVAP 測試、多階段測試、回放進階分析（縮放 / 游標 / 直方圖） |
| `d361864` | 馬力 / 扭力、即時油耗、數據錄製 / 回放、甩尾圓環、日夜模式、Mode 06 |
| `15f7bb1` | 保持螢幕常亮、設定連線控制、VW TP 2.0 感測器表 |
| `d7948d6` | APK 二進位與 README 下載章節 |
| `0984e71` | 初始版本：核心診斷功能（即時數據、故障碼、模擬模式、授權系統） |

---

## 常見問題

**Q：連不上 ELM327 轉接器？**

1. 確認轉接器已供電（部分車款需發動引擎或轉至 ACC）
2. 先到系統藍牙設定完成配對，再回 App 連線
3. 使用「連線診斷」功能檢測 Adapter 版本、電壓與通訊協定
4. 可在「連線設定」自訂 ELM327 初始化指令

**Q：讀不到數據？**

確認車輛年份與 OBD-II 通訊協定相容（1996 年後汽油車 / 2004 年後柴油車多數支援）；部分車系需使用特定 ECU header，可於 ECU 掃描中偵測。

**Q：模擬模式如何開啟？**

主畫面 →「模擬模式」入口，無需硬體即可體驗全部功能；每次啟動自動重置為關閉。

**Q：故障碼沒有說明？**

內建資料庫涵蓋 28,000+ 通用與車廠碼；未收錄的少數碼會顯示通用說明，可上網查詢或使用「OBD 終端機」手動查詢。

**Q：日夜模式如何切換？**

設定 → 外觀模式：深色 / 淺色 / 跟隨系統。深色模式為車機場景設計（高對比、低眩光）。
