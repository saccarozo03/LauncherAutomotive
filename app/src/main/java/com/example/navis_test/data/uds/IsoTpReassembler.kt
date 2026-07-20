package com.example.navis_test.data.uds

class IsoTpReassembler(
    private val expectedHeader: ByteArray,
    private val onSendFlowControl: () -> Unit,
    private val onComplete: (ByteArray) -> Unit,
    private val onSequenceError: (received: Int, expected: Int) -> Unit
) {
    private var buffer = ByteArray(0)
    private var expectedLength = -1

    private var nextSn = -1

    val isActive: Boolean get() = expectedLength >= 0

    fun reset() {
        buffer = ByteArray(0)
        expectedLength = -1
        nextSn = -1
    }

    fun onFirstFrame(payload: ByteArray): Boolean {
        if (payload.size < 2 + expectedHeader.size) return false
        for (i in expectedHeader.indices) {
            if (payload[2 + i] != expectedHeader[i]) return false
        }

        val totalLen = ((payload[0].toInt() and 0x0F) shl 8) or (payload[1].toInt() and 0xFF)
        buffer = payload.copyOfRange(2 + expectedHeader.size, payload.size)
        expectedLength = totalLen - expectedHeader.size
        nextSn = 1

        onSendFlowControl()
        tryFinish()
        return true
    }

    fun onConsecutiveFrame(payload: ByteArray) {
        if (!isActive || payload.isEmpty()) return

        val sn = payload[0].toInt() and 0x0F
        if (sn != nextSn) {
            val expected = nextSn
            reset()
            onSequenceError(sn, expected)
            return
        }
        nextSn = (nextSn + 1) and 0x0F

        val remaining = expectedLength - buffer.size
        val take = minOf(remaining, payload.size - 1)
        if (take > 0) {
            buffer += payload.copyOfRange(1, 1 + take)
        }
        tryFinish()
    }

    private fun tryFinish() {
        if (expectedLength in 0..buffer.size) {
            val result = buffer.copyOfRange(0, expectedLength)
            reset()
            onComplete(result)
        }
    }
}
