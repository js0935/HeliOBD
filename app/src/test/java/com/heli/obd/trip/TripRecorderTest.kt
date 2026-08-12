package com.heli.obd.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private fun sample(
        time: Long = 1000,
        rpm: Int = 2000,
        speed: Int = 60,
        coolant: Int = 88,
        map: Int? = 45,
        timingAdvance: Float? = 10f,
        throttle: Int? = 30,
        fuelLevel: Int? = 50,
        moduleVoltage: Float? = 13.8f,
    ) = TripRecorder.Sample(
        time = time,
        rpm = rpm,
        speed = speed,
        coolant = coolant,
        lat = 25.0,
        lng = 121.5,
        voltage = 14.2f,
        maf = 12.5f,
        fuelRate = 4.2f,
        torqueNm = 110f,
        fuelTrim = 1.5f,
        afr = 14.7f,
        map = map,
        timingAdvance = timingAdvance,
        throttle = throttle,
        fuelLevel = fuelLevel,
        moduleVoltage = moduleVoltage,
    )

    // ===== CSV =====

    @Test
    fun `toCsv 標頭包含全部欄位且順序固定`() {
        val header = TripRecorder.toCsv(listOf(sample())).lineSequence().first()
        val expected = "time,rpm,speed,coolant,voltage,maf,fuelRate,torqueNm,fuelTrim,afr," +
            "map,timingAdvance,throttle,fuelLevel,moduleVoltage,lat,lng"
        assertEquals(expected, header)
    }

    @Test
    fun `toCsv 資料列依欄位順序輸出值`() {
        val row = TripRecorder.toCsv(listOf(sample())).lineSequence().drop(1).first()
        assertEquals(
            "1000,2000,60,88,14.2,12.5,4.2,110.0,1.5,14.7,45,10.0,30,50,13.8,25.0,121.5",
            row
        )
    }

    @Test
    fun `toCsv null 欄位輸出空字串`() {
        val s = TripRecorder.Sample(
            time = 1, rpm = 0, speed = 0, coolant = 0, lat = 0.0, lng = 0.0,
        )
        val row = TripRecorder.toCsv(listOf(s)).lineSequence().drop(1).first()
        assertEquals("1,0,0,0,,,,,,,,,,,,0.0,0.0", row)
    }

    @Test
    fun `toCsv 空清單僅輸出標頭`() {
        val csv = TripRecorder.toCsv(emptyList())
        assertEquals(1, csv.lineSequence().filter { it.isNotBlank() }.count())
    }

    // ===== 降採樣 =====

    @Test
    fun `downsample 小於上限時原樣回傳`() {
        val list = (0 until 100).map { sample(time = it.toLong()) }
        assertEquals(list, TripRecorder.downsample(list))
    }

    @Test
    fun `downsample 超過上限時依比例保留 max 筆且涵蓋首筆`() {
        val list = (0 until 1000).map { sample(time = it.toLong()) }
        val result = TripRecorder.downsample(list, max = 100)
        assertEquals(100, result.size)
        assertEquals(list.first(), result.first())
        assertEquals(990L, result.last().time)
    }

    // ===== 油耗查表 =====

    @Test
    fun `fuelRateLookup 依平均速度查表`() {
        assertEquals(3.5, TripRecorder.fuelRateLookup(0.0), 1e-9)
        assertEquals(3.5, TripRecorder.fuelRateLookup(29.9), 1e-9)
        assertEquals(3.0, TripRecorder.fuelRateLookup(30.0), 1e-9)
        assertEquals(3.0, TripRecorder.fuelRateLookup(59.9), 1e-9)
        assertEquals(3.3, TripRecorder.fuelRateLookup(60.0), 1e-9)
        assertEquals(4.0, TripRecorder.fuelRateLookup(90.0), 1e-9)
        assertEquals(4.8, TripRecorder.fuelRateLookup(120.0), 1e-9)
    }

    // ===== JSON 序列化 =====

    @Test
    fun `encodeSummary 與 decodeSummary round-trip`() {
        val summary = TripRecorder.TripSummary(
            id = 42,
            startTime = 1000,
            endTime = 2000,
            durationSec = 10,
            distanceKm = 0.5,
            maxSpeed = 80,
            avgSpeedKmh = 40.0,
            maxRpm = 4500,
            avgRpm = 2200.0,
            avgCoolant = 87.0,
            samples = 20,
            estFuelL = 0.03,
            hasTrack = true,
            litersDynamic = 0.02,
            litersStatic = 0.01,
            avgFuelRateLh = 4.5,
            idleTimeSec = 5,
        )
        val round = TripRecorder.decodeSummary(TripRecorder.encodeSummary(summary, emptyList()).toString())
        assertEquals(summary, round)
    }

    @Test
    fun `encodeSample 與 decodeSamples round-trip 保留新 PID 欄位`() {
        val s = sample()
        val json = TripRecorder.encodeSample(s).toString()
        val decoded = TripRecorder.decodeSamples("{\"samplesArr\":[$json]}")
        assertEquals(1, decoded.size)
        assertEquals(s, decoded.first())
    }

    @Test
    fun `decodeSamples 舊格式（無新 PID 欄位）相容且新欄位為 null`() {
        val old = """{"samplesArr":[{"time":5,"rpm":1000,"speed":30,"coolant":80,"lat":0.0,"lng":0.0,"voltage":14.0,"maf":8.0,"fuelRate":3.0,"torqueNm":90.0,"fuelTrim":0.5,"afr":14.7}]}"""
        val list = TripRecorder.decodeSamples(old)
        assertEquals(1, list.size)
        assertEquals(1000, list[0].rpm)
        assertEquals(null, list[0].map)
        assertEquals(null, list[0].timingAdvance)
        assertEquals(null, list[0].throttle)
        assertEquals(null, list[0].fuelLevel)
        assertEquals(null, list[0].moduleVoltage)
    }

    @Test
    fun `decodeSamples 對無效內容回空清單`() {
        assertTrue(TripRecorder.decodeSamples("not json").isEmpty())
        assertTrue(TripRecorder.decodeSamples("").isEmpty())
    }
}
