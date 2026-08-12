/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.heli.obd.elm.ObdManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 行程記錄器：訂閱 ObdManager 即時數據，累計統計並以 JSON 儲存歷史行程。
 *
 * 距離積分：里程 += 車速(km/h) × 採樣間隔(h)；採樣由 OBD 輪詢（約 500ms）驅動。
 * GPS：記錄期間同時訂閱 GPS_PROVIDER，軌跡點寫入每筆樣本（無權限/無 GPS 時 lat/lng=0）。
 * 油耗估算：依平均速度查表（L/100km），estFuelL = 查表值 × 里程。
 * 儲存格式：files/trips/trip_<startTimeMillis>.json（摘要 + 降採樣後的樣本陣列）。
 */
class TripRecorder(private val context: Context, private val obd: ObdManager) {

    data class TripSummary(
        val id: Long,
        val startTime: Long,
        val endTime: Long,
        val durationSec: Int,
        val distanceKm: Double,
        val maxSpeed: Int,
        val avgSpeedKmh: Double,
        val maxRpm: Int,
        val avgRpm: Double,
        val avgCoolant: Double,
        val samples: Int,
        val estFuelL: Double = 0.0,
        val hasTrack: Boolean = false,
        val litersDynamic: Double = 0.0,
        val litersStatic: Double = 0.0,
        val avgFuelRateLh: Double = 0.0,
        val idleTimeSec: Int = 0,
    ) {
        /** 總耗油量：優先使用 fuelRate 實測累計，否則退回速度查表估算 */
        val totalFuelL: Double
            get() = if (litersDynamic + litersStatic > 0.0) {
                litersDynamic + litersStatic
            } else {
                estFuelL
            }
    }

    data class Sample(
        val time: Long,
        val rpm: Int,
        val speed: Int,
        val coolant: Int,
        val lat: Double,
        val lng: Double,
        val voltage: Float? = null,
        val maf: Float? = null,
        val fuelRate: Float? = null,
        val torqueNm: Float? = null,
        val fuelTrim: Float? = null,
        val afr: Float? = null,
        val map: Int? = null,
        val timingAdvance: Float? = null,
        val throttle: Int? = null,
        val fuelLevel: Int? = null,
        val moduleVoltage: Float? = null,
    )

    @Volatile
    private var recording = false
    private var startTime = 0L
    private var lastSampleTime = 0L
    private var distanceM = 0.0
    private var maxSpeed = 0
    private var speedSum = 0L
    private var maxRpm = 0
    private var rpmSum = 0L
    private var coolantSum = 0L
    private var sampleCount = 0
    private var litersDynamic = 0.0
    private var litersStatic = 0.0
    private var fuelRateSum = 0.0
    private var fuelRateCount = 0
    private var idleTimeSec = 0

    private val samples = mutableListOf<Sample>()
    private var listener: ObdManager.Listener? = null

    @Volatile
    private var lastLocation: Location? = null
    private var locationManager: LocationManager? = null
    private val gpsListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isRecording(): Boolean = recording

    /** 目前行程即時統計（未記錄時回傳 null） */
    fun liveState(): TripSummary? {
        if (!recording) return null
        val durationSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        return TripSummary(
            id = startTime,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            durationSec = durationSec,
            distanceKm = distanceM / 1000.0,
            maxSpeed = maxSpeed,
            avgSpeedKmh = if (sampleCount > 0) speedSum.toDouble() / sampleCount else 0.0,
            maxRpm = maxRpm,
            avgRpm = if (sampleCount > 0) rpmSum.toDouble() / sampleCount else 0.0,
            avgCoolant = if (sampleCount > 0) coolantSum.toDouble() / sampleCount else 0.0,
            samples = sampleCount,
            estFuelL = estimateFuel(liveAvgSpeed(), distanceM / 1000.0),
            hasTrack = samples.any { it.lat != 0.0 || it.lng != 0.0 },
            litersDynamic = litersDynamic,
            litersStatic = litersStatic,
            avgFuelRateLh = if (fuelRateCount > 0) fuelRateSum / fuelRateCount else 0.0,
            idleTimeSec = idleTimeSec,
        )
    }

    /** 目前平均速度（無樣本時 0） */
    private fun liveAvgSpeed(): Double =
        if (sampleCount > 0) speedSum.toDouble() / sampleCount else 0.0

    fun start() {
        if (recording) return
        recording = true
        startTime = System.currentTimeMillis()
        lastSampleTime = startTime
        distanceM = 0.0
        maxSpeed = 0
        speedSum = 0L
        maxRpm = 0
        rpmSum = 0L
        coolantSum = 0L
        sampleCount = 0
        litersDynamic = 0.0
        litersStatic = 0.0
        fuelRateSum = 0.0
        fuelRateCount = 0
        idleTimeSec = 0
        samples.clear()
        lastLocation = null
        startGps()

        val l = object : ObdManager.Listener {
            override fun onStateChanged(state: ObdManager.State) {}

            override fun onLiveData(data: ObdManager.LiveData) {
                if (!recording) return
                val now = System.currentTimeMillis()
                val dtSec = (now - lastSampleTime) / 1000.0
                lastSampleTime = now

                var speed = 0
                data.speed?.let {
                    speed = it
                    if (it > maxSpeed) maxSpeed = it
                    speedSum += it
                    if (dtSec > 0 && dtSec < 5) {
                        distanceM += it / 3.6 * dtSec
                    }
                }
                var rpm = 0
                data.rpm?.let {
                    rpm = it
                    if (it > maxRpm) maxRpm = it
                    rpmSum += it
                }
                var coolant = 0
                data.coolant?.let {
                    coolant = it
                    coolantSum += it
                }
                data.fuelRate?.let { fr ->
                    if (dtSec > 0 && dtSec < 5) {
                        val liters = fr * dtSec / 3600.0
                        if (speed > 0) litersDynamic += liters else litersStatic += liters
                    }
                    fuelRateSum += fr
                    fuelRateCount++
                }
                // 怠速：車速為 0 且引擎運轉（轉速 > 0）
                if (speed == 0 && rpm > 0 && dtSec > 0 && dtSec < 5) {
                    idleTimeSec += dtSec.toInt()
                }
                sampleCount++

                val loc = lastLocation
                samples.add(
                    Sample(
                        time = now,
                        rpm = rpm,
                        speed = speed,
                        coolant = coolant,
                        lat = loc?.latitude ?: 0.0,
                        lng = loc?.longitude ?: 0.0,
                        voltage = data.voltage,
                        maf = data.maf,
                        fuelRate = data.fuelRate,
                        torqueNm = data.torqueNm,
                        fuelTrim = data.fuelTrim,
                        afr = data.afr,
                        map = data.map,
                        timingAdvance = data.timingAdvance,
                        throttle = data.throttle,
                        fuelLevel = data.fuelLevel,
                        moduleVoltage = data.moduleVoltage,
                    )
                )
                if (samples.size > MAX_SAMPLES) {
                    samples.removeAt(0)
                }
            }
        }
        listener = l
        obd.addListener(l)
    }

    /** 停止記錄並儲存行程，回傳摘要；無任何樣本時不儲存並回傳 null */
    fun stop(): TripSummary? {
        if (!recording) return null
        recording = false
        listener?.let { obd.removeListener(it) }
        listener = null
        stopGps()

        if (sampleCount == 0) return null
        val avgSpeed = speedSum.toDouble() / sampleCount
        val distanceKm = distanceM / 1000.0
        val hasTrack = samples.any { it.lat != 0.0 || it.lng != 0.0 }
        val summary = TripSummary(
            id = startTime,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            durationSec = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
            distanceKm = distanceKm,
            maxSpeed = maxSpeed,
            avgSpeedKmh = avgSpeed,
            maxRpm = maxRpm,
            avgRpm = rpmSum.toDouble() / sampleCount,
            avgCoolant = coolantSum.toDouble() / sampleCount,
            samples = sampleCount,
            estFuelL = estimateFuel(avgSpeed, distanceKm),
            hasTrack = hasTrack,
            litersDynamic = litersDynamic,
            litersStatic = litersStatic,
            avgFuelRateLh = if (fuelRateCount > 0) fuelRateSum / fuelRateCount else 0.0,
            idleTimeSec = idleTimeSec,
        )
        save(summary)
        return summary
    }

    fun loadTrips(): List<TripSummary> =
        tripDir().listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { decodeSummary(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.startTime }
            ?: emptyList()

    fun deleteTrip(id: Long) {
        File(tripDir(), "trip_$id.json").delete()
    }

    /** 讀取行程的軌跡樣本（無則空清單） */
    fun loadSamples(id: Long): List<Sample> {
        val file = File(tripDir(), "trip_$id.json")
        if (!file.exists()) return emptyList()
        return decodeSamples(file.readText())
    }

    /** 匯出行程樣本為 CSV，回傳檔案；無樣本或檔案不存在時回傳 null */
    fun exportCsv(id: Long): File? {
        val samples = loadSamples(id)
        if (samples.isEmpty()) return null
        val dir = File(context.filesDir, "export").apply { mkdirs() }
        val file = File(dir, "trip_$id.csv")
        file.writeText(toCsv(samples))
        return file
    }

    /** 依平均速度查表估算油耗（L/100km） */
    fun fuelRate(avgSpeedKmh: Double): Double = fuelRateLookup(avgSpeedKmh)

    /** 依平均速度查表估算油耗（L/100km），再乘里程得總耗油量（L） */
    private fun estimateFuel(avgSpeedKmh: Double, distanceKm: Double): Double =
        fuelRate(avgSpeedKmh) * distanceKm / 100.0

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLocation = it }
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                5f,
                gpsListener,
                android.os.Looper.getMainLooper(),
            )
        }
    }

    private fun stopGps() {
        runCatching { locationManager?.removeUpdates(gpsListener) }
        locationManager = null
    }

    private fun tripDir(): File =
        File(context.filesDir, "trips").apply { mkdirs() }

    private fun save(summary: TripSummary) {
        File(tripDir(), "trip_${summary.id}.json").writeText(encodeSummary(summary, samples).toString())
    }

    companion object {
        private const val MAX_SAMPLES = 30000
        private const val MAX_SAVED_SAMPLES = 5000

        /** 依平均速度查表估算油耗（L/100km） */
        fun fuelRateLookup(avgSpeedKmh: Double): Double = when {
            avgSpeedKmh < 30 -> 3.5
            avgSpeedKmh < 60 -> 3.0
            avgSpeedKmh < 90 -> 3.3
            avgSpeedKmh < 120 -> 4.0
            else -> 4.8
        }

        /** 樣本清單 → CSV 字串（含標頭）。null 欄位輸出空字串 */
        fun toCsv(samples: List<Sample>): String {
            val sb = StringBuilder()
            sb.append(
                "time,rpm,speed,coolant,voltage,maf,fuelRate,torqueNm,fuelTrim,afr," +
                    "map,timingAdvance,throttle,fuelLevel,moduleVoltage,lat,lng\n"
            )
            samples.forEach { s ->
                sb.append(s.time).append(',')
                    .append(s.rpm).append(',')
                    .append(s.speed).append(',')
                    .append(s.coolant).append(',')
                    .append(s.voltage?.toString() ?: "").append(',')
                    .append(s.maf?.toString() ?: "").append(',')
                    .append(s.fuelRate?.toString() ?: "").append(',')
                    .append(s.torqueNm?.toString() ?: "").append(',')
                    .append(s.fuelTrim?.toString() ?: "").append(',')
                    .append(s.afr?.toString() ?: "").append(',')
                    .append(s.map?.toString() ?: "").append(',')
                    .append(s.timingAdvance?.toString() ?: "").append(',')
                    .append(s.throttle?.toString() ?: "").append(',')
                    .append(s.fuelLevel?.toString() ?: "").append(',')
                    .append(s.moduleVoltage?.toString() ?: "").append(',')
                    .append(s.lat).append(',')
                    .append(s.lng).append('\n')
            }
            return sb.toString()
        }

        /** 降採樣：超過上限時每隔 n 筆保留 1 筆，供軌跡繪圖與 CSV */
        fun downsample(list: List<Sample>, max: Int = MAX_SAVED_SAMPLES): List<Sample> {
            if (list.size <= max) return list
            val step = list.size.toDouble() / max
            return (0 until max).map { i -> list[(i * step).toInt()] }
        }

        /** 行程摘要 + 樣本 → JSONObject（樣本先降採樣） */
        fun encodeSummary(s: TripSummary, samples: List<Sample>): JSONObject = JSONObject().apply {
            put("id", s.id)
            put("start", s.startTime)
            put("end", s.endTime)
            put("durationSec", s.durationSec)
            put("distanceKm", s.distanceKm)
            put("maxSpeed", s.maxSpeed)
            put("avgSpeedKmh", s.avgSpeedKmh)
            put("maxRpm", s.maxRpm)
            put("avgRpm", s.avgRpm)
            put("avgCoolant", s.avgCoolant)
            put("samples", s.samples)
            put("estFuelL", s.estFuelL)
            put("hasTrack", s.hasTrack)
            put("litersDynamic", s.litersDynamic)
            put("litersStatic", s.litersStatic)
            put("avgFuelRateLh", s.avgFuelRateLh)
            put("idleTimeSec", s.idleTimeSec)
            put("samplesArr", JSONArray(downsample(samples).map { encodeSample(it) }))
        }

        /** 單筆樣本 → JSONObject（null 欄位省略） */
        fun encodeSample(s: Sample): JSONObject = JSONObject().apply {
            put("time", s.time)
            put("rpm", s.rpm)
            put("speed", s.speed)
            put("coolant", s.coolant)
            put("lat", s.lat)
            put("lng", s.lng)
            s.voltage?.let { put("voltage", it.toDouble()) }
            s.maf?.let { put("maf", it.toDouble()) }
            s.fuelRate?.let { put("fuelRate", it.toDouble()) }
            s.torqueNm?.let { put("torqueNm", it.toDouble()) }
            s.fuelTrim?.let { put("fuelTrim", it.toDouble()) }
            s.afr?.let { put("afr", it.toDouble()) }
            s.map?.let { put("map", it) }
            s.timingAdvance?.let { put("timingAdvance", it.toDouble()) }
            s.throttle?.let { put("throttle", it) }
            s.fuelLevel?.let { put("fuelLevel", it) }
            s.moduleVoltage?.let { put("moduleVoltage", it.toDouble()) }
        }

        /** 行程 JSON 文字 → TripSummary */
        fun decodeSummary(text: String): TripSummary {
            val j = JSONObject(text)
            return TripSummary(
                id = j.getLong("id"),
                startTime = j.getLong("start"),
                endTime = j.getLong("end"),
                durationSec = j.getInt("durationSec"),
                distanceKm = j.getDouble("distanceKm"),
                maxSpeed = j.getInt("maxSpeed"),
                avgSpeedKmh = j.getDouble("avgSpeedKmh"),
                maxRpm = j.getInt("maxRpm"),
                avgRpm = j.getDouble("avgRpm"),
                avgCoolant = j.getDouble("avgCoolant"),
                samples = j.getInt("samples"),
                estFuelL = if (j.has("estFuelL")) j.getDouble("estFuelL") else 0.0,
                hasTrack = j.optBoolean("hasTrack", false),
                litersDynamic = j.optDouble("litersDynamic", 0.0),
                litersStatic = j.optDouble("litersStatic", 0.0),
                avgFuelRateLh = j.optDouble("avgFuelRateLh", 0.0),
                idleTimeSec = j.optInt("idleTimeSec", 0),
            )
        }

        /** 行程 JSON 文字 → 樣本清單（空或格式錯誤回空清單） */
        fun decodeSamples(text: String): List<Sample> {
            return runCatching {
                val j = JSONObject(text)
                val arr = j.optJSONArray("samplesArr") ?: j.optJSONArray("samples") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { s ->
                        Sample(
                            time = s.optLong("time"),
                            rpm = s.optInt("rpm"),
                            speed = s.optInt("speed"),
                            coolant = s.optInt("coolant"),
                            lat = s.optDouble("lat"),
                            lng = s.optDouble("lng"),
                            voltage = if (s.has("voltage")) s.getDouble("voltage").toFloat() else null,
                            maf = if (s.has("maf")) s.getDouble("maf").toFloat() else null,
                            fuelRate = if (s.has("fuelRate")) s.getDouble("fuelRate").toFloat() else null,
                            torqueNm = if (s.has("torqueNm")) s.getDouble("torqueNm").toFloat() else null,
                            fuelTrim = if (s.has("fuelTrim")) s.getDouble("fuelTrim").toFloat() else null,
                            afr = if (s.has("afr")) s.getDouble("afr").toFloat() else null,
                            map = if (s.has("map")) s.getInt("map") else null,
                            timingAdvance = if (s.has("timingAdvance")) s.getDouble("timingAdvance").toFloat() else null,
                            throttle = if (s.has("throttle")) s.getInt("throttle") else null,
                            fuelLevel = if (s.has("fuelLevel")) s.getInt("fuelLevel") else null,
                            moduleVoltage = if (s.has("moduleVoltage")) s.getDouble("moduleVoltage").toFloat() else null,
                        )
                    }
                }
            }.getOrElse { emptyList() }
        }
    }
}
