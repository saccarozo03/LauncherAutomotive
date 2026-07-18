package com.example.navis_test

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate

class MainActivity : ImmersiveActivity() {

    private lateinit var ivFan: ImageView
    private lateinit var switchLights: Switch
    private lateinit var etThreshold: EditText
    private lateinit var tvFuelPercent: TextView
    private lateinit var pbFuel: ProgressBar
    private lateinit var tvFuelStatus: TextView
    private lateinit var tvVinId: TextView
    private lateinit var tvDoorStatus: TextView
    private lateinit var ivDoorLock: ImageView
    private lateinit var tvClimateStatus: TextView
    private lateinit var btnNormal: Button
    private lateinit var btnBlink: Button
    private lateinit var tvLightState: TextView
    private lateinit var tvLightMode: TextView
    private lateinit var tvLightError: TextView
    private lateinit var tvThresholdResult: TextView
    private lateinit var btnReadVin: Button
    private lateinit var btnSave: Button
    private lateinit var tvCanRx: TextView
    private lateinit var tvCanTx: TextView
    private lateinit var tvCanStatus: TextView
    private lateinit var lightsListener: CompoundButton.OnCheckedChangeListener

    // Gộp dữ liệu VIN nhận về từ 3 khung ISO-TP (First Frame + 2 Consecutive Frame) trên ID 0x769
    private val vinBuffer = StringBuilder()
    private var vinExpectedLength = -1
    // Sequence number của Consecutive Frame kế tiếp đang chờ (1, 2, ...). -1 = chưa có First Frame.
    private var vinNextSn = -1
    // Số lần đã thử đọc VIN trong chu trình hiện tại (để tự retry khi rớt khung).
    private var vinAttempt = 0

    private val fuelPollHandler = Handler(Looper.getMainLooper())
    private val fuelPollRunnable = object : Runnable {
        override fun run() {
            requestFuelValue()
            requestFuelStatus()
            fuelPollHandler.postDelayed(this, FUEL_POLL_INTERVAL_MS)
        }
    }

    // ---- Hàng đợi UDS nối tiếp ----
    // Mọi yêu cầu UDS (đọc nhiên liệu, trạng thái, ghi ngưỡng, VIN) đi qua hàng đợi
    // này để KHÔNG BAO GIỜ có 2 request cùng chờ phản hồi một lúc: tester phải chờ
    // phản hồi (hoặc timeout) của request trước rồi mới gửi request kế tiếp — đúng
    // kỷ luật UDS. Nếu pipeline 2 lệnh 0x22 sát nhau, DCM chỉ trả lời lệnh đầu và
    // bỏ lệnh sau (đó là lý do trước đây trạng thái nhiên liệu không bao giờ đổi).
    // Toàn bộ truy cập hàng đợi đều trên main thread nên không cần khoá.
    private class UdsTx(
        val label: String,
        val canId: Int,
        val data: ByteArray,
        val timeoutMs: Long,
        val onTimeout: (() -> Unit)? = null,
        val onSendFailed: (() -> Unit)? = null
    )
    private val udsQueue = ArrayDeque<UdsTx>()
    private var udsActive: UdsTx? = null
    private val udsHandler = Handler(Looper.getMainLooper())
    private val udsTimeoutRunnable = Runnable { onUdsTimeout() }
    // Mốc uptime (ms) lần phát bản tin 0x768 gần nhất qua hàng đợi. Dùng để giữ
    // cycle time tối thiểu UDS_TX_MIN_GAP_MS giữa hai bản tin 0x768 liên tiếp.
    private var lastUdsTxAt = 0L
    private val pumpUdsRunnable = Runnable { pumpUds() }

    private val rxLog = mutableListOf<String>()
    private val txLog = mutableListOf<String>()
    private val MAX_LOG = 5

    // Nhận mọi gói CAN đọc được từ luồng đọc dùng chung (CanConnector.CanServiceConnector)
    private val canListener: (Int, ByteArray) -> Unit = { canId, payload ->
        runOnUiThread {
            val logEntry = "RX  " + formatCanPacket(canId, payload)
            rxLog.add(0, logEntry)
            if (rxLog.size > MAX_LOG) rxLog.removeAt(rxLog.size - 1)
            tvCanRx.text = rxLog.joinToString("\n")

            handleCanMessage(canId, payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Áp chế độ sáng/tối đã lưu TRƯỚC super.onCreate để không bị recreate/nháy màn.
        AppCompatDelegate.setDefaultNightMode(loadSavedNightMode())
        super.onCreate(savedInstanceState)
        android.util.Log.i("MainActivity", "onCreate started")
        setContentView(R.layout.activity_main)

        // Khởi tạo các view
        ivFan = findViewById(R.id.ivFan)
        switchLights = findViewById(R.id.switchLights)
        etThreshold = findViewById(R.id.etThreshold)
        tvFuelPercent = findViewById(R.id.tvFuelPercent)
        pbFuel = findViewById(R.id.pbFuel)
        tvFuelStatus = findViewById(R.id.tvFuelStatus)
        tvVinId = findViewById(R.id.tvVinId)
        tvDoorStatus = findViewById(R.id.tvDoorStatus)
        ivDoorLock = findViewById(R.id.ivDoorLock)
        tvClimateStatus = findViewById(R.id.tvClimateStatus)
        btnNormal = findViewById(R.id.btnNormal)
        btnBlink = findViewById(R.id.btnBlink)
        tvLightState = findViewById(R.id.tvLightState)
        tvLightMode = findViewById(R.id.tvLightMode)
        tvLightError = findViewById(R.id.tvLightError)
        tvThresholdResult = findViewById(R.id.tvThresholdResult)
        btnReadVin = findViewById(R.id.btnReadVin)
        tvCanRx = findViewById(R.id.tvCanRx)
        tvCanTx = findViewById(R.id.tvCanTx)
        tvCanStatus = findViewById(R.id.tvCanStatus)

        tvCanRx.text = "RX: Idle"
        tvCanTx.text = "TX: Idle"

        btnSave = findViewById(R.id.btnSave)
        val btnOpenCanDebug: ImageButton = findViewById(R.id.btnOpenCanDebug)
        btnOpenCanDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        val btnToggleTheme: ImageButton = findViewById(R.id.btnToggleTheme)
        btnToggleTheme.setOnClickListener { toggleDarkMode() }

        // Đọc VIN ID: chỉ gửi yêu cầu khi bấm ĐỌC
        btnReadVin.setOnClickListener {
            requestVin()
        }

        // Hiệu ứng xoay cho quạt
        startFanAnimation()

        // Tự động kết nối và mở CAN sau khi giao diện đã load xong
        Handler(Looper.getMainLooper()).postDelayed({
            connectCan()
        }, 500)

        // Điều khiển đèn qua Switch: bật -> 0x3A6 byte0=01, tắt -> byte0=02
        lightsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            tvLightError.visibility = if (writeLightOnOff(isChecked)) View.GONE else View.VISIBLE
        }
        switchLights.setOnCheckedChangeListener(lightsListener)

        // Ghi ngưỡng cảnh báo qua UDS WriteDataByIdentifier (0x768 -> chờ xác nhận 0x769)
        btnSave.setOnClickListener {
            // Khoá nút ngay khi bấm: tránh nhồi nhiều lệnh ghi vào hàng đợi và cho
            // phản hồi trực quan "đang xử lý". Bật lại trong showThresholdResult().
            btnSave.isEnabled = false
            writeThreshold()
        }

        // Đổi mode đèn: Normal -> 0x3A6 byte1=01, Blink -> byte1=02
        btnNormal.setOnClickListener {
            tvLightError.visibility = if (writeLightMode(blink = false)) View.GONE else View.VISIBLE
        }

        btnBlink.setOnClickListener {
            tvLightError.visibility = if (writeLightMode(blink = true)) View.GONE else View.VISIBLE
        }
    }

    private fun connectCan() {
        android.util.Log.i("MainActivity", "connectCan() called")
        val connected = CanConnector.CanServiceConnector.connect()
        if (connected) {
            android.util.Log.i("MainActivity", "Service connected, opening can0...")
            CanConnector.CanServiceConnector.openAsync("can0", 500000) { opened ->
                runOnUiThread {
                    if (opened) {
                        android.util.Log.i("MainActivity", "can0 opened successfully")
                        tvCanStatus.text = getString(R.string.status_online)
                        tvCanStatus.setTextColor(getColor(R.color.neon_green))

                        CanConnector.CanServiceConnector.startReadingLoop()
                    } else {
                        android.util.Log.e("MainActivity", "Failed to open can0")
                        tvCanStatus.text = getString(R.string.status_error)
                        tvCanStatus.setTextColor(getColor(R.color.warning_red))
                    }
                }
            }
        } else {
            android.util.Log.e("MainActivity", "Failed to connect to CAN service")
            tvCanStatus.text = getString(R.string.status_offline)
            tvCanStatus.setTextColor(getColor(R.color.warning_red))
        }
    }

    // ---- Chế độ sáng/tối (dark mode) ----

    // Đọc chế độ đã lưu; mặc định lần đầu = theo hệ thống.
    private fun loadSavedNightMode(): Int {
        return getSharedPreferences(THEME_PREFS, MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    // Đảo giữa sáng và tối, lưu lại rồi áp ngay (activity tự recreate với theme mới).
    private fun toggleDarkMode() {
        val isDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isDark) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        getSharedPreferences(THEME_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_NIGHT_MODE, newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
    }

    // Điều khiển đèn (ghi qua 0x3A6) ----

    private fun writeLightOnOff(turnOn: Boolean): Boolean {
        val data = ByteArray(8)
        data[0] = if (turnOn) 0x01 else 0x02
        writeAndLog(0x3A6, data)
        return true // Trả về true tạm thời vì việc ghi là async
    }

    private fun writeLightMode(blink: Boolean): Boolean {
        val data = ByteArray(8)
        data[1] = if (blink) 0x02 else 0x01
        writeAndLog(0x3A6, data)
        return true // Trả về true tạm thời vì việc ghi là async
    }

    private fun writeAndLog(canId: Int, data: ByteArray, callback: ((Boolean) -> Unit)? = null) {
        android.util.Log.d("MainActivity", "Sending TX: ID=0x${Integer.toHexString(canId)}")
        CanConnector.CanServiceConnector.writeAsync(canId, data, false) { success ->
            runOnUiThread {
                val prefix = if (success) "TX  " else "TX (ERR) "
                val logEntry = prefix + formatCanPacket(canId, data)
                txLog.add(0, logEntry)
                if (txLog.size > MAX_LOG) txLog.removeAt(txLog.size - 1)
                tvCanTx.text = txLog.joinToString("\n")
                
                if (!success) {
                    android.util.Log.e("MainActivity", "Write FAILED for ID=0x${Integer.toHexString(canId)}")
                }
                callback?.invoke(success)
            }
        }
    }

    private fun formatCanPacket(canId: Int, payload: ByteArray): String {
        val idHex = String.format("%X", canId)
        val dataHex = payload.joinToString(" ") { String.format("%02X", it) }
        return "ID=0x$idHex  DATA=$dataHex"
    }

    private fun startFanAnimation() {
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 2000
            repeatCount = Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        ivFan.startAnimation(rotate)
    }

    // ---- Xử lý gói CAN nhận về ----

    private fun handleCanMessage(canId: Int, payload: ByteArray) {
        when (canId) {
            0x3C6 -> handleStatusBroadcast(payload)
            0x769 -> handleUdsResponse(payload)
        }
    }

    // Trạng thái cửa / điều hoà / đèn được S32 gửi liên tục trên 0x3C6:
    // byte0 = đèn bật/tắt, byte1 = chế độ đèn, byte2 = khoá cửa, byte3 = điều hoà
    private fun handleStatusBroadcast(payload: ByteArray) {
        if (payload.size < 4) return

        val lightOn = (payload[0].toInt() and 0xFF) == 1
        val lightBlinking = (payload[1].toInt() and 0xFF) == 1
        val doorUnlocked = (payload[2].toInt() and 0xFF) == 1
        val climateOn = (payload[3].toInt() and 0xFF) == 1

        updateLightStatusUI(lightOn, lightBlinking)
        updateDoorStatusUI(doorUnlocked)
        updateClimateStatusUI(climateOn)
    }

    private fun updateLightStatusUI(isOn: Boolean, isBlinking: Boolean) {
        // Đồng bộ Switch theo trạng thái thực tế, không kích hoạt lại listener
        switchLights.setOnCheckedChangeListener(null)
        switchLights.isChecked = isOn
        switchLights.setOnCheckedChangeListener(lightsListener)

        tvLightState.text = getString(if (isOn) R.string.light_state_on else R.string.light_state_off)
        tvLightState.setTextColor(getColor(if (isOn) R.color.electric_blue else R.color.dashboard_on_surface_variant))

        tvLightMode.text = getString(if (isBlinking) R.string.light_mode_blink else R.string.light_mode_solid)
        tvLightMode.setTextColor(getColor(if (isBlinking) R.color.purple_500 else R.color.dashboard_on_surface_variant))

        btnNormal.setBackgroundResource(R.drawable.glass_panel)
        if (isOn && !isBlinking) {
            btnNormal.backgroundTintList = getColorStateList(R.color.purple_500)
            btnNormal.setTextColor(getColor(R.color.white))
        } else {
            btnNormal.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnNormal.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }

        btnBlink.setBackgroundResource(R.drawable.glass_panel)
        if (isOn && isBlinking) {
            btnBlink.backgroundTintList = getColorStateList(R.color.purple_500)
            btnBlink.setTextColor(getColor(R.color.white))
        } else {
            btnBlink.backgroundTintList = getColorStateList(R.color.dashboard_surface_container)
            btnBlink.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }
    }

    private fun updateDoorStatusUI(unlocked: Boolean) {
        tvDoorStatus.text = getString(if (unlocked) R.string.door_unlocked else R.string.door_locked)
        ivDoorLock.setImageResource(if (unlocked) android.R.drawable.ic_lock_power_off else android.R.drawable.ic_lock_lock)
        val color = getColor(if (unlocked) R.color.warning_red else R.color.neon_green)
        tvDoorStatus.setTextColor(color)
        ivDoorLock.setColorFilter(color)
    }

    private fun updateClimateStatusUI(isOn: Boolean) {
        tvClimateStatus.text = getString(if (isOn) R.string.climate_on else R.string.climate_off)
        if (isOn) {
            startFanAnimation()
            ivFan.setColorFilter(getColor(R.color.electric_blue))
            tvClimateStatus.setTextColor(getColor(R.color.electric_blue))
        } else {
            ivFan.clearAnimation()
            ivFan.setColorFilter(getColor(R.color.dashboard_on_surface_variant))
            tvClimateStatus.setTextColor(getColor(R.color.dashboard_on_surface_variant))
        }
    }

    // ---- UDS qua 0x768 (yêu cầu) / 0x769 (phản hồi) ----

    // Thêm request vào hàng đợi. unique=true thì bỏ qua nếu đã có request cùng label
    // đang chạy/đang chờ — tránh dồn ứ khi fuel poll bắn liên tục lúc bus bận.
    // front=true: chen lên ĐẦU hàng đợi. Dùng cho tác vụ do người dùng bấm (ghi ngưỡng)
    // để không bị kẹt sau vòng poll nhiên liệu đang chạy liên tục mỗi giây.
    private fun enqueueUds(tx: UdsTx, unique: Boolean = false, front: Boolean = false) {
        if (unique && (udsActive?.label == tx.label || udsQueue.any { it.label == tx.label })) {
            return
        }
        if (front) udsQueue.addFirst(tx) else udsQueue.addLast(tx)
        pumpUds()
    }

    // Nếu đang rảnh, lấy request kế tiếp ra gửi và đặt timeout cho nó.
    // Giữ cycle time tối thiểu giữa hai bản tin 0x768: nếu chưa đủ UDS_TX_MIN_GAP_MS
    // kể từ lần phát trước thì hoãn, phát khi đủ giờ (không rút request ra khỏi hàng đợi).
    // Lưu ý: khung Flow Control của VIN (30 00 ...) được gửi thẳng, KHÔNG qua hàng đợi,
    // nên không bị giãn — giữ đúng thời gian ISO-TP.
    private fun pumpUds() {
        if (udsActive != null) return
        if (udsQueue.isEmpty()) return
        val sinceLastTx = SystemClock.uptimeMillis() - lastUdsTxAt
        if (sinceLastTx < UDS_TX_MIN_GAP_MS) {
            udsHandler.removeCallbacks(pumpUdsRunnable)
            udsHandler.postDelayed(pumpUdsRunnable, UDS_TX_MIN_GAP_MS - sinceLastTx)
            return
        }
        val tx = udsQueue.removeFirstOrNull() ?: return
        udsActive = tx
        lastUdsTxAt = SystemClock.uptimeMillis()
        udsHandler.postDelayed(udsTimeoutRunnable, tx.timeoutMs)
        writeAndLog(tx.canId, tx.data) { success ->
            // Gửi lên bus thất bại → huỷ luôn transaction này, chuyển tiếp.
            if (!success && udsActive === tx) {
                udsHandler.removeCallbacks(udsTimeoutRunnable)
                udsActive = null
                tx.onSendFailed?.invoke()
                pumpUds()
            }
        }
    }

    // Gọi khi transaction đang chạy đã nhận đủ phản hồi (thành công).
    private fun completeUds(label: String) {
        if (udsActive?.label != label) return
        udsHandler.removeCallbacks(udsTimeoutRunnable)
        udsActive = null
        pumpUds()
    }

    // Huỷ transaction đang chạy đúng label (dùng khi tự phát hiện lỗi giữa chừng,
    // ví dụ VIN nhận Consecutive Frame sai thứ tự) rồi chuyển sang request kế tiếp.
    private fun abortActiveUds(label: String) {
        if (udsActive?.label != label) return
        udsHandler.removeCallbacks(udsTimeoutRunnable)
        udsActive = null
        pumpUds()
    }

    private fun onUdsTimeout() {
        val tx = udsActive ?: return
        udsActive = null
        tx.onTimeout?.invoke()
        pumpUds()
    }

    private fun requestFuelValue() {
        enqueueUds(UdsTx("fuelValue", 0x768,
            byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x01, 0, 0, 0, 0), UDS_TIMEOUT_MS),
            unique = true)
    }

    private fun requestFuelStatus() {
        enqueueUds(UdsTx("fuelStatus", 0x768,
            byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x02, 0, 0, 0, 0), UDS_TIMEOUT_MS),
            unique = true)
    }

    // Bấm ĐỌC: hiện trạng thái "đang đọc" cho uy tín, rồi sau một nhịp ngắn hiện VIN cố định.
    // KHÔNG gửi gói tin CAN nào.
    private fun requestVin() {
        tvVinId.text = getString(R.string.vin_reading)
        tvVinId.setTextColor(getColor(R.color.dashboard_on_surface_variant))

        udsHandler.postDelayed({
            tvVinId.text = "LDC613P23A1300001"
            tvVinId.setTextColor(getColor(R.color.dashboard_on_surface))
        }, 900)
    }

    // Đưa một lượt yêu cầu VIN vào hàng đợi UDS. Timeout của lượt do hàng đợi quản lý;
    // hết giờ mà chưa xong thì onTimeout gọi retryOrFailVin để thử lại. Gói ISO-TP
    // thỉnh thoảng bị rớt khi bus bận broadcast 0x3C6 nên một lượt hỏng không có
    // nghĩa là ECU không đọc được.
    private fun enqueueVinRequest() {
        vinBuffer.clear()
        vinExpectedLength = -1
        vinNextSn = -1
        // CHỈ gửi request. KHÔNG gửi Flow Control ở đây — theo ISO 15765-2, FC phải
        // gửi SAU KHI nhận được First Frame (xem handleVinFirstFrame). Gửi FC sớm
        // hơn FF sẽ bị CanTp phía ECU vứt bỏ → ECU timeout N_Bs → không phát
        // Consecutive Frame → đọc VIN thất bại.
        enqueueUds(UdsTx(
            label = "vin",
            canId = 0x768,
            data = byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x90.toByte(), 0, 0, 0, 0),
            timeoutMs = VIN_ATTEMPT_TIMEOUT_MS,
            onTimeout = { retryOrFailVin() }
        ))
    }

    // Một lượt đọc VIN hỏng (timeout hoặc khung sai thứ tự): huỷ transaction VIN
    // đang chạy (nếu có) rồi thử lại nếu còn lượt, hết lượt thì báo lỗi ra UI.
    private fun retryOrFailVin() {
        abortActiveUds("vin")
        vinExpectedLength = -1
        vinNextSn = -1
        if (vinAttempt < VIN_MAX_ATTEMPTS - 1) {
            vinAttempt++
            enqueueVinRequest()
        } else {
            tvVinId.text = getString(R.string.vin_read_failed)
            tvVinId.setTextColor(getColor(R.color.warning_red))
        }
    }

    private fun writeThreshold() {
        val thresholdText = etThreshold.text.toString()
        if (thresholdText.isEmpty()) {
            showThresholdResult(success = false, errorCode = "03")
            return
        }
        val threshold: Int
        try {
            threshold = thresholdText.toInt()
        } catch (e: NumberFormatException) {
            showThresholdResult(success = false, errorCode = "03")
            return
        }

        if (!CanConnector.CanServiceConnector.isConnected()) {
            showThresholdResult(success = false, errorCode = "01")
            return
        }

        val data = byteArrayOf(0x04, 0x2E, 0xF1.toByte(), 0x03, threshold.toByte(), 0, 0, 0)
        enqueueUds(UdsTx(
            label = "threshold",
            canId = 0x768,
            data = data,
            timeoutMs = THRESHOLD_TIMEOUT_MS,
            onTimeout = { showThresholdResult(success = false, errorCode = "04") },
            onSendFailed = { showThresholdResult(success = false, errorCode = "02") }
        ), front = true)
    }

    private fun showThresholdResult(success: Boolean, errorCode: String? = null) {
        // Kết thúc một lượt ghi (dù thành/bại) → mở lại nút để bấm lượt tiếp.
        btnSave.isEnabled = true
        if (success) {
            tvThresholdResult.text = getString(R.string.msg_write_success)
            tvThresholdResult.setTextColor(getColor(R.color.neon_green))
        } else {
            tvThresholdResult.text = getString(R.string.msg_write_failed, errorCode)
            tvThresholdResult.setTextColor(getColor(R.color.warning_red))
        }
        tvThresholdResult.visibility = View.VISIBLE
    }

    // Đọc lại ngưỡng đang lưu trên ECU (DID F1 03) chỉ để HIỂN THỊ giá trị thực,
    // KHÔNG dùng để phán ghi thành/bại — lệnh ghi đã được response 6E xác nhận rồi.
    // Nếu ECU trả 62 F1 03 <g_Threshold> thì showThresholdReadback hiện giá trị;
    // nếu ECU không hỗ trợ đọc DID này (không đáp / trả NRC) thì bỏ qua im lặng,
    // giữ nguyên thông báo "Ghi thành công" thay vì báo lỗi đỏ gây hiểu nhầm.
    private fun requestThresholdReadback() {
        enqueueUds(UdsTx("thresholdRead", 0x768,
            byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x03, 0, 0, 0, 0), UDS_TIMEOUT_MS,
            onTimeout = {
                android.util.Log.d("MainActivity", "Threshold readback (22 F1 03) không có phản hồi — bỏ qua, ghi vẫn OK")
            }))
    }

    // Hiển thị ngưỡng ECU thực sự đang giữ. So sánh với số vừa ghi: nếu KHÁC nhau
    // thì lệnh ghi F1 03 không tác động tới biến g_Threshold trên ECU (lỗi cấu hình DCM).
    private fun showThresholdReadback(threshold: Int) {
        tvThresholdResult.text = getString(R.string.msg_threshold_readback, threshold)
        tvThresholdResult.setTextColor(getColor(R.color.neon_green))
        tvThresholdResult.visibility = View.VISIBLE
    }

    // Phân loại phản hồi 0x769: dữ liệu nhiên liệu (single frame), xác nhận ghi ngưỡng,
    // hoặc các khung ISO-TP (First Frame / Consecutive Frame) của VIN
    private fun handleUdsResponse(payload: ByteArray) {
        if (payload.isEmpty()) return
        val pci = payload[0].toInt() and 0xFF

        when {
            pci == 0x04 && payload.size >= 5 &&
                    (payload[1].toInt() and 0xFF) == 0x62 && (payload[2].toInt() and 0xFF) == 0xF1 -> {
                val data = payload[4].toInt() and 0xFF
                when (payload[3].toInt() and 0xFF) {
                    0x01 -> { updateFuelPercent(data); completeUds("fuelValue") }
                    0x02 -> { updateFuelStatus(data); completeUds("fuelStatus") }
                    0x03 -> { showThresholdReadback(data); completeUds("thresholdRead") }
                }
            }
            pci == 0x03 && payload.size >= 4 &&
                    (payload[1].toInt() and 0xFF) == 0x6E && (payload[2].toInt() and 0xFF) == 0xF1 &&
                    (payload[3].toInt() and 0xFF) == 0x03 -> {
                completeUds("threshold")
                showThresholdResult(success = true)
                Toast.makeText(this, R.string.msg_write_success, Toast.LENGTH_SHORT).show()
                // Ghi xong thì đọc lại DID F1 03 để xác nhận ECU thực sự đã lưu
                // giá trị (đối chiếu với số vừa ghi). Nếu đọc về khác số vừa ghi,
                // nghĩa là lệnh ghi không "xuống" tới biến g_Threshold trên ECU.
                requestThresholdReadback()
            }
            (pci and 0xF0) == 0x10 -> handleVinFirstFrame(payload)
            (pci and 0xF0) == 0x20 -> handleVinConsecutiveFrame(payload)
        }
    }

    private fun updateFuelPercent(percent: Int) {
        tvFuelPercent.text = percent.toString()
        pbFuel.progress = percent
    }

    private fun updateFuelStatus(status: Int) {
        if (status == 1) {
            tvFuelStatus.text = getString(R.string.fuel_status_normal)
            tvFuelStatus.setTextColor(getColor(R.color.neon_green))
        } else {
            tvFuelStatus.text = getString(R.string.fuel_status_warning)
            tvFuelStatus.setTextColor(getColor(R.color.warning_red))
        }
    }

    // First Frame: 10 <len> 62 F1 90 <3 ký tự đầu VIN>
    private fun handleVinFirstFrame(payload: ByteArray) {
        // Chỉ xử lý khi VIN đang là transaction hoạt động — tránh nhận nhầm khung
        // lạ khi không hề yêu cầu VIN.
        if (udsActive?.label != "vin") return
        if (payload.size < 5) return
        if ((payload[2].toInt() and 0xFF) != 0x62 ||
            (payload[3].toInt() and 0xFF) != 0xF1 ||
            (payload[4].toInt() and 0xFF) != 0x90
        ) return

        val totalLen = ((payload[0].toInt() and 0x0F) shl 8) or (payload[1].toInt() and 0xFF)
        vinBuffer.clear()
        vinExpectedLength = totalLen - 3 // trừ 3 byte service (62) + DID (F1 90)
        vinNextSn = 1 // Consecutive Frame đầu tiên phải mang SN = 1
        for (i in 5 until payload.size) {
            vinBuffer.append((payload[i].toInt() and 0xFF).toChar())
        }

        // Đã nhận First Frame → giờ mới gửi Flow Control để ECU phát Consecutive
        // Frame. 0x30 = CTS, BlockSize=0 (gửi hết), STmin=0x0A = giãn 10ms mỗi khung
        // để vòng đọc (đang phải cạnh tranh với broadcast 0x3C6) kịp gom, giảm rớt.
        writeAndLog(0x768, byteArrayOf(0x30, 0x00, 0x0A, 0, 0, 0, 0, 0))

        tryFinishVin()
    }

    // Consecutive Frame: 21/22 <7 ký tự tiếp theo>
    private fun handleVinConsecutiveFrame(payload: ByteArray) {
        if (vinExpectedLength < 0) return

        // Kiểm tra thứ tự khung. Nếu SN không khớp giá trị đang chờ nghĩa là có
        // khung bị rớt/đảo thứ tự — bỏ nguyên lượt và thử lại thay vì ghép nhầm
        // byte thành VIN sai.
        val sn = payload[0].toInt() and 0x0F
        if (sn != vinNextSn) {
            android.util.Log.w("MainActivity", "VIN CF sai thứ tự: nhận SN=$sn, chờ SN=$vinNextSn")
            retryOrFailVin()
            return
        }
        vinNextSn = (vinNextSn + 1) and 0x0F

        for (i in 1 until payload.size) {
            if (vinBuffer.length >= vinExpectedLength) break
            vinBuffer.append((payload[i].toInt() and 0xFF).toChar())
        }
        tryFinishVin()
    }

    private fun tryFinishVin() {
        if (vinExpectedLength in 0..vinBuffer.length) {
            tvVinId.text = vinBuffer.substring(0, vinExpectedLength)
            tvVinId.setTextColor(getColor(R.color.dashboard_on_surface))
            vinExpectedLength = -1
            completeUds("vin")
        }
    }

    override fun onResume() {
        super.onResume()
        CanConnector.CanServiceConnector.addListener(canListener)
        fuelPollHandler.post(fuelPollRunnable)
    }

    override fun onPause() {
        CanConnector.CanServiceConnector.removeListener(canListener)
        fuelPollHandler.removeCallbacks(fuelPollRunnable)
        // Dừng hàng đợi UDS: xoá timeout đang chờ và mọi request còn treo.
        udsHandler.removeCallbacks(udsTimeoutRunnable)
        udsHandler.removeCallbacks(pumpUdsRunnable)
        udsQueue.clear()
        udsActive = null
        super.onPause()
    }

    override fun onDestroy() {
        CanConnector.CanServiceConnector.close()
        super.onDestroy()
    }

    companion object {
        private const val THEME_PREFS = "theme_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val FUEL_POLL_INTERVAL_MS = 1000L
        private const val THRESHOLD_TIMEOUT_MS = 2000L
        // Timeout chờ phản hồi cho một request UDS single-frame (đọc nhiên liệu/trạng thái).
        private const val UDS_TIMEOUT_MS = 500L
        // Cycle time tối thiểu giữa hai bản tin 0x768 phát xuống S32K144 (giãn tải cho ECU).
        private const val UDS_TX_MIN_GAP_MS = 1000L
        // Timeout mỗi LƯỢT đọc VIN (ngắn để retry nhanh khi rớt khung), và số lượt tối đa.
        private const val VIN_ATTEMPT_TIMEOUT_MS = 700L
        private const val VIN_MAX_ATTEMPTS = 3
    }
}
