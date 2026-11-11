package com.dre.carbook.elm.transport

import kotlinx.coroutines.channels.ReceiveChannel

/** Канал байтов до адаптера ELM327 (Bluetooth SPP, BLE или Wi-Fi). */
interface ElmTransport {
    /** Входящие данные. Закрывается с IOException при потере связи. */
    val incoming: ReceiveChannel<ByteArray>

    suspend fun connect()

    suspend fun write(data: ByteArray)

    fun close()
}

/** Сколько входящих кусков держим в очереди (до ~1 КБ каждый). */
const val INCOMING_CAPACITY = 512
