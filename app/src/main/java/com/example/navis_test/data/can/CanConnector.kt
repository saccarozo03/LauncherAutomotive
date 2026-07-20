package com.example.navis_test.data.can

import android.os.IBinder
import hust.can.ICan
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CanConnector {
    private var canService: ICan? = null
    private val lock = Any()

    @Volatile private var isReading = false
    private var readThread: Thread? = null
    private val listeners = CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CanService-IO").apply { isDaemon = true }
    }

    fun connect(): Boolean {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)

            val serviceName = "hust.can.ICan/default"
            android.util.Log.d("CanConnector", "Attempting to connect to service: $serviceName")

            val binder = getService.invoke(null, serviceName) as? IBinder
            if (binder == null) {
                android.util.Log.e("CanConnector", "Service $serviceName returned null binder")
                return false
            }

            synchronized(lock) {
                canService = ICan.Stub.asInterface(binder)
            }
            android.util.Log.d("CanConnector", "Connected to CanService successfully")
        } catch (e: Exception) {
            android.util.Log.e("CanConnector", "Connect exception: ${e.message}")
            e.printStackTrace()
        }
        return canService != null
    }

    fun openAsync(ifName: String, bitrate: Int, callback: (Boolean) -> Unit) {
        ioExecutor.execute {
            val service = synchronized(lock) { canService }
            val result = if (service != null) {
                safeCall("open") { service.canOpen(ifName, bitrate) }
            } else false
            callback(result)
        }
    }

    fun writeAsync(canId: Int, data: ByteArray, extended: Boolean, callback: ((Boolean) -> Unit)? = null) {
        ioExecutor.execute {
            val service = synchronized(lock) { canService }
            val result = if (service != null) {
                safeCall("write") { service.canWrite(canId, data, extended) }
            } else false
            callback?.invoke(result)
        }
    }

    fun isConnected(): Boolean = synchronized(lock) { canService != null }

    private fun read(): Pair<Int, ByteArray>? {
        val rawBuffer = ByteArray(12)
        val service = synchronized(lock) { canService } ?: return null

        val dlc = safeCall("read") {
            service.canRead(rawBuffer)
        }

        if (dlc < 0) return null

        val canId = (rawBuffer[0].toInt() and 0xFF) or
                ((rawBuffer[1].toInt() and 0xFF) shl 8) or
                ((rawBuffer[2].toInt() and 0xFF) shl 16) or
                ((rawBuffer[3].toInt() and 0xFF) shl 24)

        val payload = rawBuffer.copyOfRange(4, 4 + dlc)
        return Pair(canId, payload)
    }

    fun addListener(listener: (Int, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Int, ByteArray) -> Unit) {
        listeners.remove(listener)
    }

    fun startReadingLoop() {
        if (isReading) return
        isReading = true
        readThread = Thread({
            while (isReading) {
                val result = read()
                if (result != null) {
                    val (canId, payload) = result
                    android.util.Log.d("CanConnector", "Received CAN: ID=0x${Integer.toHexString(canId)}, Data=${payload.joinToString(" ") { "%02X".format(it) }}")
                    for (listener in listeners) {
                        try {
                            listener(canId, payload)
                        } catch (e: Exception) {
                            android.util.Log.e("CanConnector", "Listener threw: ${e.message}")
                        }
                    }

                } else {

                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }, "CanService-Read").apply { isDaemon = true }
        readThread?.start()
    }

    fun stopReadingLoop() {
        isReading = false
        readThread?.interrupt()
        readThread = null
    }

    fun isReadingActive(): Boolean = isReading

    fun close() {
        stopReadingLoop()
        ioExecutor.execute {
            val service = synchronized(lock) {
                val s = canService
                canService = null
                s
            }
            safeCall("close") {
                service?.canClose()
                true
            }
        }
    }

    fun shutdown() {
        stopReadingLoop()
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            ioExecutor.shutdownNow()
        }
    }

    private fun <T> safeCall(tag: String, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.e("CanConnector", "$tag() failed: ${e.message}")
            @Suppress("UNCHECKED_CAST")
            when (tag) {
                "read" -> -1 as T
                else -> false as T
            }
        }
    }
}
