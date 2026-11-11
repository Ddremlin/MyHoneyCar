package com.dre.carbook.elm.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/**
 * BLE адаптеры (Vgate iCar Pro BLE, Konnwei, клоны «для iPhone»).
 * Ищем «UART»-сервис: характеристику с notify и характеристику с write.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ElmTransport {

    // ограничено: поток мусора от адаптера между командами не съест память (старое выбрасывается)
    private val channel = Channel<ByteArray>(INCOMING_CAPACITY, BufferOverflow.DROP_OLDEST)
    override val incoming: ReceiveChannel<ByteArray> get() = channel

    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var chunkSize = 20
    @Volatile
    private var discoveryStarted = false
    private val ready = CompletableDeferred<Unit>()
    private val writeAck = Channel<Unit>(Channel.CONFLATED)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(247)) discover(g)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val e = IOException("BLE-соединение разорвано (код $status)")
                    ready.completeExceptionally(e)
                    channel.close(e)
                    g.close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) chunkSize = (mtu - 3).coerceIn(20, 244)
            discover(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IOException("Ошибка поиска сервисов BLE: $status"))
                return
            }
            val (notify, write) = findCharacteristics(g) ?: run {
                ready.completeExceptionally(IOException("На устройстве не найден сервис ELM327"))
                return
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = if (notify.properties and PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (!writeDescriptorCompat(g, cccd, value)) ready.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(Unit)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            channel.trySend(value.copyOf())
        }

        @Deprecated("Android 12 и ниже")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { channel.trySend(it.copyOf()) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck.trySend(Unit)
        }
    }

    private fun discover(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        g.discoverServices()
    }

    override suspend fun connect() {
        val g = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: throw IOException("Не удалось начать BLE-подключение")
        gatt = g
        try {
            withTimeoutOrNull(15_000) { ready.await() }
                ?: throw IOException("Таймаут BLE-подключения")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Нет соединения")
        val c = writeChar ?: throw IOException("Нет соединения")
        val type = if (c.properties and PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var off = 0
        while (off < data.size) {
            val chunk = data.copyOfRange(off, min(off + chunkSize, data.size))
            writeAck.tryReceive()
            if (!writeCharacteristicCompat(g, c, chunk, type)) {
                delay(30) // стек BLE занят — одна повторная попытка
                if (!writeCharacteristicCompat(g, c, chunk, type)) throw IOException("Ошибка записи BLE")
            }
            withTimeoutOrNull(1000) { writeAck.receive() }
            off += chunk.size
        }
    }

    override fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        channel.close()
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    private fun findCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = g.services
            .filter { shortUuid(it.uuid) !in SKIP_SERVICES }
            .sortedBy { if (shortUuid(it.uuid) in PREFERRED_SERVICES || it.uuid == VGATE_SERVICE) 0 else 1 }
        for (s in services) {
            val n = s.characteristics.firstOrNull { it.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0 }
                ?: continue
            val w = s.characteristics.firstOrNull { it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0 }
                ?: continue
            return n to w
        }
        return null
    }

    private fun shortUuid(u: UUID): Int? =
        if (u.leastSignificantBits == BASE_UUID.leastSignificantBits &&
            (u.mostSignificantBits and 0xFFFFFFFFL) == BASE_UUID.mostSignificantBits
        ) {
            (u.mostSignificantBits ushr 32).toInt()
        } else {
            null
        }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val VGATE_SERVICE: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        private val BASE_UUID: UUID = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")
        private val SKIP_SERVICES = setOf(0x1800, 0x1801, 0x180A, 0x180F)
        private val PREFERRED_SERVICES = setOf(0xFFF0, 0xFFE0, 0x18F0)
    }
}
