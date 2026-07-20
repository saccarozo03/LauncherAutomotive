package com.example.navis_test.model

class CanLogEntry(
    val direction: Direction,
    val ok: Boolean,
    val canId: Int,
    val data: ByteArray,
    val atMillis: Long
) {
    enum class Direction { RX, TX }

    fun formatPacket(): String {
        val idHex = String.format("%X", canId)
        val dataHex = data.joinToString(" ") { String.format("%02X", it) }
        return "ID=0x$idHex  DATA=$dataHex"
    }
}
