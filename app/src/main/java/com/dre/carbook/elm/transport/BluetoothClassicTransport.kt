package com.dre.carbook.elm.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Классический Bluetooth (профиль SPP) — большинство адаптеров ELM327. */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : StreamTransport() {

    @Volatile
    private var socket: BluetoothSocket? = null

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery()
        // Дешёвые клоны часто не отвечают на SDP-запрос, поэтому пробуем по очереди
        // обычный, insecure и «прямой» сокет на канал 1.
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in attempts) {
            val s = try {
                make()
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                s.connect()
                socket = s
                attach(s.inputStream, s.outputStream)
                return@withContext
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            }
        }
        throw IOException("Не удалось подключиться к ${device.name ?: device.address}: ${last?.message}", last)
    }

    override fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
