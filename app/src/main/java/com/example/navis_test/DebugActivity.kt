package com.example.navis_test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale

class DebugActivity : ImmersiveActivity() {

    private lateinit var etCanId: EditText
    private lateinit var etCanData: EditText
    private lateinit var cbExtended: CheckBox
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnToggleReading: Button

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Watchdog "im lặng": theo dõi lúc nhận frame gần nhất để cảnh báo khi vòng đọc
    // đang chạy nhưng không có frame nào về (nghi đứt dây/termination/mạch hoặc bên gửi im).
    private val debugHandler = Handler(Looper.getMainLooper())
    private var lastRxAt = 0L
    private var noRxWarned = false
    private val rxWatchdog = object : Runnable {
        override fun run() {
            if (CanConnector.CanServiceConnector.isReadingActive() && lastRxAt != 0L) {
                val silentMs = SystemClock.uptimeMillis() - lastRxAt
                if (silentMs > RX_SILENCE_WARN_MS && !noRxWarned) {
                    appendStatus("CẢNH BÁO: ${silentMs / 1000}s không có frame CAN nào — kiểm tra dây/termination/mạch hoặc bên gửi")
                    noRxWarned = true
                }
            }
            debugHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val canListener: (Int, ByteArray) -> Unit = { canId, payload ->
        runOnUiThread {
            lastRxAt = SystemClock.uptimeMillis()
            if (noRxWarned) {
                appendStatus("Đã có frame CAN trở lại")
                noRxWarned = false
            }
            appendLog("RX", canId, payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        etCanId = findViewById(R.id.etCanId)
        etCanData = findViewById(R.id.etCanData)
        cbExtended = findViewById(R.id.cbExtended)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnToggleReading = findViewById(R.id.btnToggleReading)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { tvLog.text = "" }

        updateToggleReadingLabel()
        btnToggleReading.setOnClickListener {
            if (CanConnector.CanServiceConnector.isReadingActive()) {
                CanConnector.CanServiceConnector.stopReadingLoop()
                appendStatus("Đã DỪNG vòng đọc (thủ công)")
            } else {
                // Bấm để chạy lại: dựng lại cả chuỗi cho chắc (service + can0 + vòng đọc).
                autoStartDebug()
            }
            updateToggleReadingLabel()
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener { sendPacket() }
    }

    // Tự dựng toàn bộ chuỗi debug khi mở màn: kết nối CanService -> mở can0 -> chạy vòng đọc.
    // Ghi log từng bước để thấy chuỗi đứt ở tầng nào (service / socket / mạch).
    private fun autoStartDebug() {
        if (CanConnector.CanServiceConnector.isReadingActive()) {
            appendStatus("Vòng đọc đang chạy — RX hiển thị tự động")
            lastRxAt = SystemClock.uptimeMillis()
            return
        }

        if (CanConnector.CanServiceConnector.isConnected()) {
            appendStatus("CanService đã kết nối")
        } else {
            appendStatus("Đang kết nối CanService (binder hust.can.ICan/default)…")
            val connected = CanConnector.CanServiceConnector.connect()
            if (!connected) {
                appendStatus("LỖI: không kết nối được CanService — service trên Pi chưa chạy?")
                return
            }
            appendStatus("Đã kết nối CanService")
        }

        appendStatus("Đang mở can0 @ 500 kbps…")
        CanConnector.CanServiceConnector.openAsync("can0", 500000) { opened ->
            runOnUiThread {
                if (opened) {
                    appendStatus("Đã mở can0 — bắt đầu vòng đọc, chờ frame…")
                    lastRxAt = SystemClock.uptimeMillis()
                    noRxWarned = false
                    CanConnector.CanServiceConnector.startReadingLoop()
                } else {
                    appendStatus("LỖI: mở can0 thất bại — kiểm tra 'ip link' / mạch CAN / bitrate")
                }
                updateToggleReadingLabel()
            }
        }
    }

    private fun updateToggleReadingLabel() {
        btnToggleReading.text = if (CanConnector.CanServiceConnector.isReadingActive()) {
            getString(R.string.btn_stop_reading)
        } else {
            getString(R.string.btn_start_reading)
        }
    }

    private fun sendPacket() {
        val canId = parseCanId(etCanId.text.toString())
        if (canId == null) {
            Toast.makeText(this, R.string.error_can_id_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val data = parseCanData(etCanData.text.toString())
        if (data == null) {
            Toast.makeText(this, R.string.error_can_data_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val extended = cbExtended.isChecked
        CanConnector.CanServiceConnector.writeAsync(canId, data, extended) { success ->
            runOnUiThread {
                if (success) {
                    appendLog("TX", canId, data)
                } else {
                    Toast.makeText(this, R.string.msg_can_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Nhận "3A6" hoặc "0x3A6" dạng hex
    private fun parseCanId(input: String): Int? {
        val trimmed = input.trim().removePrefix("0x").removePrefix("0X")
        if (trimmed.isEmpty()) return null
        return trimmed.toIntOrNull(16)
    }

    // Nhận danh sách byte hex cách nhau bởi khoảng trắng, VD: "01 02 0A"
    private fun parseCanData(input: String): ByteArray? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        val tokens = trimmed.split(Regex("\\s+"))
        val bytes = ByteArray(tokens.size)
        for (i in tokens.indices) {
            val value = tokens[i].toIntOrNull(16) ?: return null
            if (value !in 0..0xFF) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun appendLog(direction: String, canId: Int, payload: ByteArray) {
        val time = timeFormat.format(System.currentTimeMillis())
        val idHex = String.format("%X", canId)
        val dataHex = payload.joinToString(" ") { String.format("%02X", it) }
        tvLog.append("[$time] $direction  ID=0x$idHex  DATA=$dataHex\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    // Dòng trạng thái của chuỗi debug (khác với RX/TX), giúp lần theo tầng nào lỗi.
    private fun appendStatus(msg: String) {
        val time = timeFormat.format(System.currentTimeMillis())
        tvLog.append("[$time] ·· $msg\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        CanConnector.CanServiceConnector.addListener(canListener)
        // Debug chạy tự động: vừa mở màn là dựng chuỗi và bắt đầu đọc, không cần bấm tay.
        autoStartDebug()
        debugHandler.post(rxWatchdog)
        updateToggleReadingLabel()
    }

    override fun onPause() {
        CanConnector.CanServiceConnector.removeListener(canListener)
        // Chỉ gỡ watchdog; KHÔNG dừng vòng đọc để MainActivity vẫn nhận dữ liệu.
        debugHandler.removeCallbacks(rxWatchdog)
        super.onPause()
    }

    companion object {
        // Vòng đọc chạy mà im quá ngưỡng này thì watchdog cảnh báo nghi lỗi mạch/dây.
        private const val RX_SILENCE_WARN_MS = 3000L
        private const val WATCHDOG_INTERVAL_MS = 1000L
    }
}
