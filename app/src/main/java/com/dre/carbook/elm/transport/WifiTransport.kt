package com.dre.carbook.elm.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Wi-Fi адаптер: TCP, обычно 192.168.0.10:35000. */
class WifiTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
) : StreamTransport() {

    @Volatile
    private var socket: Socket? = null

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        // Сеть адаптера без интернета, и Android по умолчанию шлёт трафик через мобильную сеть.
        // Привязываем сокет именно к Wi-Fi.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm?.allNetworks?.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val s = wifi?.socketFactory?.createSocket() ?: Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { s.close() }
            throw IOException("Нет связи с $host:$port. Подключите телефон к Wi-Fi сети адаптера", e)
        }
        socket = s
        attach(s.getInputStream(), s.getOutputStream())
    }

    override fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }
}
