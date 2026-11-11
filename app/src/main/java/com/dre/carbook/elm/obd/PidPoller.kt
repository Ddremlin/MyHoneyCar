package com.dre.carbook.elm.obd

import com.dre.carbook.elm.elm.ElmClient
import com.dre.carbook.elm.elm.ElmTimeoutException

/**
 * Опрос PID режима 01. На CAN спрашивает до [BATCH] параметров одной командой;
 * если ответ не разбирается — дочитывает эти PID по одному, а после
 * [MAX_FAILURES] сбоев подряд переходит на обычный опрос до переподключения.
 */
class PidPoller(
    private val client: ElmClient,
    fastAllowed: Boolean,
    private val onLog: (String) -> Unit,
) {
    var fast: Boolean = fastAllowed && client.isCan
        private set

    private var failures = 0

    fun setFastAllowed(allowed: Boolean) {
        fast = allowed && client.isCan
        failures = 0
    }

    suspend fun poll(pids: List<Pid>, onValue: (Pid, Double?) -> Unit) {
        if (!fast || pids.size < 2) {
            pids.forEach { onValue(it, single(it)) }
            return
        }
        for (batch in pids.chunked(BATCH)) {
            if (!fast) {
                batch.forEach { onValue(it, single(it)) }
                continue
            }
            val cmd = "01" + batch.joinToString("") { "%02X".format(it.code) }
            var raw: List<String>? = null
            val parsed = try {
                client.send(cmd).also { raw = it }.let { ObdParser.parseMulti(it, batch) }
            } catch (_: ElmTimeoutException) {
                null
            }
            if (parsed == null) {
                failure("нет разборчивого ответа на $cmd: ${raw?.joinToString(" ") ?: "таймаут"}")
                batch.forEach { onValue(it, single(it)) }
                continue
            }
            var recovered = false
            for (p in batch) {
                val data = parsed[p.code]
                if (data != null) {
                    onValue(p, p.decode(data))
                } else {
                    // PID пропал из группового ответа — проверяем по одному
                    val v = single(p)
                    if (v != null) recovered = true
                    onValue(p, v)
                }
            }
            if (recovered) failure("ЭБУ ответил не на все PID в $cmd") else failures = 0
        }
    }

    private suspend fun single(p: Pid): Double? = try {
        ObdParser.mode01(client.send("01%02X".format(p.code)), p.code)?.let(p::decode)
    } catch (_: ElmTimeoutException) {
        null
    }

    private fun failure(reason: String) {
        failures++
        onLog("! ускоренный опрос: $reason ($failures/$MAX_FAILURES)")
        if (failures >= MAX_FAILURES) {
            fast = false
            onLog("! ускоренный опрос отключён, перехожу на опрос по одному PID")
        }
    }

    private companion object {
        const val BATCH = 6
        const val MAX_FAILURES = 3
    }
}
