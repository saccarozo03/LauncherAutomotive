package com.example.navis_test.ui.main

import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.navis_test.R
import com.example.navis_test.model.ConnectionState
import com.example.navis_test.model.VehicleStatus
import com.example.navis_test.model.VinState
import com.example.navis_test.ui.ImmersiveActivity
import com.example.navis_test.ui.ThemeManager
import kotlinx.coroutines.launch

class MainActivity : ImmersiveActivity() {

    private val viewModel: MainViewModel by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {

        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        findViewById<ImageButton>(R.id.btnToggleTheme).setOnClickListener {
            ThemeManager.toggle(this)
        }

        btnReadVin.setOnClickListener { viewModel.onReadVinClicked() }

        lightsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            tvLightError.visibility = View.GONE
            viewModel.onLightToggled(isChecked)
        }
        switchLights.setOnCheckedChangeListener(lightsListener)

        btnSave.setOnClickListener { viewModel.onSaveThresholdClicked(etThreshold.text.toString()) }
        btnNormal.setOnClickListener { viewModel.onLightModeSelected(blink = false) }
        btnBlink.setOnClickListener { viewModel.onLightModeSelected(blink = true) }

        startFanAnimation()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch {

                    viewModel.events.collect { event ->
                        when (event) {
                            is MainEvent.ShowWriteSuccessToast ->
                                Toast.makeText(this@MainActivity, R.string.msg_write_success, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    override fun onPause() {
        viewModel.onPaused()
        super.onPause()
    }

    private fun bindViews() {
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
        btnSave = findViewById(R.id.btnSave)
        tvCanRx = findViewById(R.id.tvCanRx)
        tvCanTx = findViewById(R.id.tvCanTx)
        tvCanStatus = findViewById(R.id.tvCanStatus)
    }

    private fun render(state: DashboardUiState) {
        renderConnection(state.connection)

        state.vehicle?.let { renderVehicle(it) }
        renderFuel(state.fuelPercent, state.fuelNormal)
        renderVin(state.vin)
        renderThreshold(state)
        if (state.rxLog.isNotEmpty()) tvCanRx.text = state.rxLog.joinToString("\n")
        if (state.txLog.isNotEmpty()) tvCanTx.text = state.txLog.joinToString("\n")
    }

    private fun renderConnection(connection: ConnectionState) {
        tvCanStatus.isActivated = connection == ConnectionState.ONLINE
        when (connection) {
            ConnectionState.CONNECTING -> Unit
            ConnectionState.ONLINE -> tvCanStatus.text = getString(R.string.status_online)
            ConnectionState.SERVICE_OFFLINE -> tvCanStatus.text = getString(R.string.status_offline)
            ConnectionState.OPEN_FAILED -> tvCanStatus.text = getString(R.string.status_error)
        }
    }

    private fun renderVehicle(vehicle: VehicleStatus) {
        renderLightStatus(vehicle.lightOn, vehicle.lightBlinking)
        renderDoorStatus(vehicle.doorUnlocked)
        renderClimateStatus(vehicle.climateOn)
    }

    private fun renderLightStatus(isOn: Boolean, isBlinking: Boolean) {

        switchLights.setOnCheckedChangeListener(null)
        switchLights.isChecked = isOn
        switchLights.setOnCheckedChangeListener(lightsListener)

        tvLightState.text = getString(if (isOn) R.string.light_state_on else R.string.light_state_off)
        tvLightState.isActivated = isOn

        tvLightMode.text = getString(if (isBlinking) R.string.light_mode_blink else R.string.light_mode_solid)
        tvLightMode.isActivated = isBlinking

        btnNormal.isSelected = isOn && !isBlinking
        btnBlink.isSelected = isOn && isBlinking
    }

    private fun renderDoorStatus(unlocked: Boolean) {
        tvDoorStatus.text = getString(if (unlocked) R.string.door_unlocked else R.string.door_locked)
        tvDoorStatus.isActivated = unlocked
        ivDoorLock.isActivated = unlocked
    }

    private fun renderClimateStatus(isOn: Boolean) {
        tvClimateStatus.text = getString(if (isOn) R.string.climate_on else R.string.climate_off)
        tvClimateStatus.isActivated = isOn
        ivFan.isActivated = isOn
        if (isOn) startFanAnimation() else ivFan.clearAnimation()
    }

    private fun renderFuel(percent: Int?, normal: Boolean?) {
        if (percent != null) {
            tvFuelPercent.text = percent.toString()
            pbFuel.progress = percent
        }
        if (normal != null) {
            tvFuelStatus.text = getString(if (normal) R.string.fuel_status_normal else R.string.fuel_status_warning)
            tvFuelStatus.isActivated = !normal
        }
    }

    private fun renderVin(vin: VinState) {
        when (vin) {
            is VinState.Idle -> Unit
            is VinState.Reading -> {
                tvVinId.text = getString(R.string.vin_reading)
                tvVinId.setTextColor(getColor(R.color.dashboard_on_surface_variant))
            }
            is VinState.Success -> {
                tvVinId.text = vin.vin
                tvVinId.setTextColor(getColor(R.color.dashboard_on_surface))
            }
            is VinState.Failed -> {
                tvVinId.text = getString(R.string.vin_read_failed)
                tvVinId.setTextColor(getColor(R.color.warning_red))
            }
        }
    }

    private fun renderThreshold(state: DashboardUiState) {
        btnSave.isEnabled = !state.thresholdSaving
        when (val threshold = state.threshold) {
            is ThresholdUi.Idle -> Unit
            is ThresholdUi.Result -> {
                tvThresholdResult.text =
                    if (threshold.success) getString(R.string.msg_write_success)
                    else getString(R.string.msg_write_failed, threshold.errorCode)
                tvThresholdResult.isActivated = threshold.success
                tvThresholdResult.visibility = View.VISIBLE
            }
            is ThresholdUi.Readback -> {
                tvThresholdResult.text = getString(R.string.msg_threshold_readback, threshold.value)
                tvThresholdResult.isActivated = true
                tvThresholdResult.visibility = View.VISIBLE
            }
        }
    }

    private fun startFanAnimation() {
        ivFan.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fan_rotate))
    }
}
