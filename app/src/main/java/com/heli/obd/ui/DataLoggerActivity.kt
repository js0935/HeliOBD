/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 單筆錄製樣本（時間戳 + 主要訊號；未取得的值為 null） */
data class DatalogSample(
    val t: Long,
    val rpm: Int?,
    val speed: Int?,
    val coolant: Int?,
    val voltage: Float?,
    val load: Int?,
    val map: Int? = null,
    val timingAdvance: Float? = null,
    val throttle: Int? = null,
    val fuelLevel: Int? = null,
    val moduleVoltage: Float? = null,
)

/**
 * 數據錄製/回放：監聽即時數據寫入 JSON；列出歷史檔供回放、匯出 CSV 或刪除。
 */
class DataLoggerActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var btnRecord: Button
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout

    private var recording = false
    private var startedAt = 0L
    private val samples = mutableListOf<DatalogSample>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_logger)

        btnRecord = findViewById(R.id.btn_record)
        statusText = findViewById(R.id.logger_status)
        listContainer = findViewById(R.id.logger_list_container)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnRecord.setOnClickListener { toggleRecording() }

        obd.addListener(this)
        renderState(obd.state)
        refreshList()
    }

    override fun onDestroy() {
        obd.removeListener(this)
        ioScope.cancel()
        super.onDestroy()
    }

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.logger_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        samples.clear()
        startedAt = System.currentTimeMillis()
        recording = true
        btnRecord.text = getString(R.string.logger_stop)
        statusText.text = getString(R.string.logger_recording, 0)
    }

    private fun stopRecording() {
        recording = false
        btnRecord.text = getString(R.string.logger_record)
        statusText.text = getString(R.string.common_dash)
        if (samples.isEmpty()) {
            Toast.makeText(this, R.string.logger_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir, "datalog").apply { mkdirs() }
        val name = "datalog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
        val file = File(dir, name)
        file.writeText(encodeSamples())
        Toast.makeText(this, getString(R.string.logger_saved, name), Toast.LENGTH_LONG).show()
        refreshList()
    }

    private fun encodeSamples(): String {
        val sb = StringBuilder()
        sb.append("{\"startedAt\":").append(startedAt).append(",\"samples\":[")
        samples.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(s.t)
                .append(",\"rpm\":").append(s.rpm)
                .append(",\"speed\":").append(s.speed)
                .append(",\"coolant\":").append(s.coolant)
                .append(",\"voltage\":").append(s.voltage)
                .append(",\"load\":").append(s.load)
                .append(",\"map\":").append(s.map)
                .append(",\"timingAdvance\":").append(s.timingAdvance)
                .append(",\"throttle\":").append(s.throttle)
                .append(",\"fuelLevel\":").append(s.fuelLevel)
                .append(",\"moduleVoltage\":").append(s.moduleVoltage)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        if (!recording) return
        samples += DatalogSample(
            System.currentTimeMillis(),
            data.rpm,
            data.speed,
            data.coolant,
            data.voltage,
            data.load,
            data.map,
            data.timingAdvance,
            data.throttle,
            data.fuelLevel,
            data.moduleVoltage,
        )
        statusText.text = getString(R.string.logger_recording, samples.size)
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val dir = File(filesDir, "datalog")
        val files = dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            listContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.logger_no_files)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, dp(8), 0, 0)
                }
            )
            return
        }
        files.forEach { file ->
            val count = parseSamples(file).size
            val row = LayoutInflater.from(this).inflate(R.layout.item_dtc, listContainer, false)
            row.findViewById<TextView>(R.id.dtc_code).text = file.name
            row.findViewById<TextView>(R.id.dtc_desc).text = getString(R.string.logger_file_format, count)
            row.isClickable = true
            row.setOnClickListener { showFileMenu(file) }
            listContainer.addView(row)
        }
    }

    private fun showFileMenu(file: File) {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(file.name)
            .setItems(
                arrayOf(
                    getString(R.string.logger_menu_replay),
                    getString(R.string.logger_menu_export),
                    getString(R.string.logger_menu_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, DataReplayActivity::class.java).putExtra("file", file.absolutePath)
                    )
                    1 -> exportCsv(file)
                    2 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun exportCsv(file: File) {
        val list = parseSamples(file)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.logger_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("t,rpm,speed,coolant,voltage,load,map,timingAdvance,throttle,fuelLevel,moduleVoltage\n")
        list.forEach { s ->
            sb.append(s.t).append(',')
                .append(s.rpm ?: "").append(',')
                .append(s.speed ?: "").append(',')
                .append(s.coolant ?: "").append(',')
                .append(s.voltage ?: "").append(',')
                .append(s.load ?: "").append(',')
                .append(s.map ?: "").append(',')
                .append(s.timingAdvance ?: "").append(',')
                .append(s.throttle ?: "").append(',')
                .append(s.fuelLevel ?: "").append(',')
                .append(s.moduleVoltage ?: "").append('\n')
        }
        val dir = File(filesDir, "export").apply { mkdirs() }
        val out = File(dir, file.name.replace(".json", ".csv"))
        out.writeText(sb.toString())
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.logger_menu_export)))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setMessage(R.string.logger_confirm_delete)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                file.delete()
                refreshList()
                Toast.makeText(this, R.string.logger_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderState(state: ObdManager.State) {
        if (state != ObdManager.State.Ready && !recording) {
            statusText.text = getString(R.string.obd_disconnected)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun parseSamples(file: File): List<DatalogSample> {
            return try {
                val root = JSONObject(file.readText())
                val arr = root.getJSONArray("samples")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DatalogSample(
                        o.getLong("t"),
                        if (o.isNull("rpm")) null else o.getInt("rpm"),
                        if (o.isNull("speed")) null else o.getInt("speed"),
                        if (o.isNull("coolant")) null else o.getInt("coolant"),
                        if (o.isNull("voltage")) null else o.getDouble("voltage").toFloat(),
                        if (o.isNull("load")) null else o.getInt("load"),
                        if (o.isNull("map")) null else o.getInt("map"),
                        if (o.isNull("timingAdvance")) null else o.getDouble("timingAdvance").toFloat(),
                        if (o.isNull("throttle")) null else o.getInt("throttle"),
                        if (o.isNull("fuelLevel")) null else o.getInt("fuelLevel"),
                        if (o.isNull("moduleVoltage")) null else o.getDouble("moduleVoltage").toFloat(),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
