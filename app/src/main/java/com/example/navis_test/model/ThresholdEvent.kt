package com.example.navis_test.model

sealed class ThresholdEvent {
    object WriteOk : ThresholdEvent()
    data class WriteFailed(val code: String) : ThresholdEvent()

    data class Readback(val value: Int) : ThresholdEvent()
}
