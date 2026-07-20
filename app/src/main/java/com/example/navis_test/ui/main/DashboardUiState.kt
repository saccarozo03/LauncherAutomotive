package com.example.navis_test.ui.main

import com.example.navis_test.model.ConnectionState
import com.example.navis_test.model.VehicleStatus
import com.example.navis_test.model.VinState

data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val vehicle: VehicleStatus? = null,
    val fuelPercent: Int? = null,
    val fuelNormal: Boolean? = null,
    val vin: VinState = VinState.Idle,
    val threshold: ThresholdUi = ThresholdUi.Idle,
    val thresholdSaving: Boolean = false,
    val rxLog: List<String> = emptyList(),
    val txLog: List<String> = emptyList()
)

sealed class ThresholdUi {

    object Idle : ThresholdUi()
    data class Result(val success: Boolean, val errorCode: String? = null) : ThresholdUi()
    data class Readback(val value: Int) : ThresholdUi()
}

sealed class MainEvent {
    object ShowWriteSuccessToast : MainEvent()
}
