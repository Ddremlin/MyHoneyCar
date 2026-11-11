package com.dre.carbook.elm.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** Общая часть для транспортов на потоках (RFCOMM-сокет, TCP-сокет). */
abstract class StreamTransport : ElmTransport {
    // ограничено: поток мусора от адаптера между командами не съест память (старое выбрасывается)
    private val channel = Channel<ByteArray>(INCOMING_CAPACITY, BufferOverflow.DROP_OLDEST)
    override val incoming: ReceiveChannel<ByteArray> get() = channel

    @Volatile
    private var output: OutputStream? = null

    protected fun attach(input: InputStream, output: OutputStream) {
        this.output = output
        thread(name = "elm-reader", isDaemon = true) {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) channel.trySend(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            channel.close(IOException("Соединение с адаптером потеряно"))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Нет соединения")
        out.write(data)
        out.flush()
    }

    override fun close() {
        output = null
        closeSocket()
        channel.close()
    }

    protected abstract fun closeSocket()
}
