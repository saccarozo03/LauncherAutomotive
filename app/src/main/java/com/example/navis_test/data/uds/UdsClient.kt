package com.example.navis_test.data.uds

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class UdsClient(
    private val minTxGapMs: Long,
    private val send: (canId: Int, data: ByteArray, onResult: (Boolean) -> Unit) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<UdsRequest>()
    private var active: UdsRequest? = null
    private val timeoutRunnable = Runnable { onTimeout() }
    private val pumpRunnable = Runnable { pump() }

    private var lastTxAt = 0L

    val activeLabel: String? get() = active?.label

    fun enqueue(request: UdsRequest, unique: Boolean = false, front: Boolean = false) {
        if (unique && (active?.label == request.label || queue.any { it.label == request.label })) {
            return
        }
        if (front) queue.addFirst(request) else queue.addLast(request)
        pump()
    }

    private fun pump() {
        if (active != null) return
        if (queue.isEmpty()) return
        val sinceLastTx = SystemClock.uptimeMillis() - lastTxAt
        if (sinceLastTx < minTxGapMs) {
            handler.removeCallbacks(pumpRunnable)
            handler.postDelayed(pumpRunnable, minTxGapMs - sinceLastTx)
            return
        }
        val tx = queue.removeFirstOrNull() ?: return
        active = tx
        lastTxAt = SystemClock.uptimeMillis()
        handler.postDelayed(timeoutRunnable, tx.timeoutMs)
        send(tx.canId, tx.data) { success ->

            if (!success && active === tx) {
                handler.removeCallbacks(timeoutRunnable)
                active = null
                tx.onSendFailed?.invoke()
                pump()
            }
        }
    }

    fun complete(label: String) {
        if (active?.label != label) return
        handler.removeCallbacks(timeoutRunnable)
        active = null
        pump()
    }

    fun abort(label: String) {
        if (active?.label != label) return
        handler.removeCallbacks(timeoutRunnable)
        active = null
        pump()
    }

    fun clear() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(pumpRunnable)
        queue.clear()
        active = null
    }

    private fun onTimeout() {
        val tx = active ?: return
        active = null
        tx.onTimeout?.invoke()
        pump()
    }
}
