package com.example.navis_test.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.navis_test.data.VehicleRepository
import com.example.navis_test.model.CanLogEntry
import com.example.navis_test.model.ThresholdEvent
import com.example.navis_test.model.VinState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val repo = VehicleRepository

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MainEvent> = _events

    private var fuelPollJob: Job? = null
    private var logJob: Job? = null
    private var fakeVinJob: Job? = null

    init {

        viewModelScope.launch {
            delay(CONNECT_DELAY_MS)
            repo.ensureStarted()
        }

        viewModelScope.launch {
            repo.connectionState.collect { c -> _uiState.update { it.copy(connection = c) } }
        }

        viewModelScope.launch {
            repo.vehicleStatus.collect { v -> _uiState.update { it.copy(vehicle = v) } }
        }
        viewModelScope.launch {
            repo.fuel.collect { f ->
                _uiState.update { it.copy(fuelPercent = f.percent, fuelNormal = f.normal) }
            }
        }
        viewModelScope.launch {
            repo.thresholdEvents.collect { onThresholdEvent(it) }
        }
        if (USE_REAL_VIN) {
            viewModelScope.launch {
                repo.vin.collect { v -> _uiState.update { it.copy(vin = v) } }
            }
        }
    }

    fun onResumed() {

        fuelPollJob?.cancel()
        fuelPollJob = viewModelScope.launch {
            while (true) {
                repo.requestFuelValue()
                repo.requestFuelStatus()
                delay(FUEL_POLL_INTERVAL_MS)
            }
        }

        logJob?.cancel()
        logJob = viewModelScope.launch {
            repo.canLog.collect { appendLog(it) }
        }
    }

    fun onPaused() {
        fuelPollJob?.cancel()
        fuelPollJob = null
        logJob?.cancel()
        logJob = null

        repo.cancelPendingUds()
    }

    fun onLightToggled(on: Boolean) = repo.setLight(on)

    fun onLightModeSelected(blink: Boolean) = repo.setLightMode(blink)

    fun onReadVinClicked() {
        if (USE_REAL_VIN) {
            repo.readVin()
            return
        }

        _uiState.update { it.copy(vin = VinState.Reading) }
        fakeVinJob?.cancel()
        fakeVinJob = viewModelScope.launch {
            delay(FAKE_VIN_DELAY_MS)
            _uiState.update { it.copy(vin = VinState.Success(FAKE_VIN)) }
        }
    }

    fun onSaveThresholdClicked(input: String) {
        val value = input.trim().toIntOrNull()
        if (value == null) {
            _uiState.update { it.copy(threshold = ThresholdUi.Result(false, "03")) }
            return
        }
        if (!repo.isConnected()) {
            _uiState.update { it.copy(threshold = ThresholdUi.Result(false, "01")) }
            return
        }

        _uiState.update { it.copy(thresholdSaving = true) }
        repo.writeThreshold(value)
    }

    private fun onThresholdEvent(event: ThresholdEvent) {
        when (event) {
            is ThresholdEvent.WriteOk -> {
                _uiState.update { it.copy(thresholdSaving = false, threshold = ThresholdUi.Result(true)) }
                _events.tryEmit(MainEvent.ShowWriteSuccessToast)
            }
            is ThresholdEvent.WriteFailed -> {
                _uiState.update { it.copy(thresholdSaving = false, threshold = ThresholdUi.Result(false, event.code)) }
            }
            is ThresholdEvent.Readback -> {
                _uiState.update { it.copy(threshold = ThresholdUi.Readback(event.value)) }
            }
        }
    }

    private fun appendLog(entry: CanLogEntry) {
        val line = when {
            entry.direction == CanLogEntry.Direction.RX -> "RX  " + entry.formatPacket()
            entry.ok -> "TX  " + entry.formatPacket()
            else -> "TX (ERR) " + entry.formatPacket()
        }
        _uiState.update {
            if (entry.direction == CanLogEntry.Direction.RX) {
                it.copy(rxLog = (listOf(line) + it.rxLog).take(MAX_LOG))
            } else {
                it.copy(txLog = (listOf(line) + it.txLog).take(MAX_LOG))
            }
        }
    }

    companion object {
        private const val CONNECT_DELAY_MS = 500L
        private const val FUEL_POLL_INTERVAL_MS = 1000L
        private const val MAX_LOG = 5

        private const val USE_REAL_VIN = false
        private const val FAKE_VIN = "LDC613P23A1300001"
        private const val FAKE_VIN_DELAY_MS = 900L
    }
}
