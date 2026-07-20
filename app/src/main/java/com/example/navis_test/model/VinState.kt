package com.example.navis_test.model

sealed class VinState {
    object Idle : VinState()
    object Reading : VinState()
    data class Success(val vin: String) : VinState()
    object Failed : VinState()
}
