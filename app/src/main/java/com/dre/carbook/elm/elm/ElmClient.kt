package com.dre.carbook.elm.elm

import com.dre.carbook.elm.transport.ElmTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Адаптер не ответил вовремя (связь при этом может быть жива). */
class ElmTimeoutException(cmd: String, message: String = "Нет ответа на $cmd") : Exception(message)

data class ElmInfo(
    val version: String,
    val protocol: String,
    val voltage: String?,
    val ecuResponding: Boolean,
)

/**
 * Команда → ответ до приглашения '>'. Все обращения сериализуются мьютексом,
 * поэтому опрос, терминал и чтение ошибок можно вызывать параллельно.
 */
class ElmClient(
    private val transport: ElmTransport,
    private val onLog: (String) -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Предыдущая команда не дождалась '>' — её ответ может прийти позже и достаться следующей
     * команде, после чего все ответы съезжают на одну. Перед следующей командой дочитываем хвост.
     */
    private var desynced = false

    /** Номер протокола из ATDPN: 1–5 — K-Line/PWM/VPW, 6–9 и A–C — CAN. */
    var protocolNumber: Char = '0'
        private set

    val isCan: Boolean get() = protocolNumber in '6'..'9' || protocolNumber in 'A'..'C'

    suspend fun send(cmd: String, timeoutMs: Long = 4000, log: Boolean = false): List<String> = mutex.withLock {
        if (desynced) resync()
        while (transport.incoming.tryReceive().isSuccess) {
            // выбрасываем хвосты от предыдущих ответов
        }
        if (log) onLog("> $cmd")
        transport.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        var lines: List<String> = emptyList()
        val done = withTimeoutOrNull(timeoutMs) {
            while (true) {
                while (sb.indexOf(">") < 0) {
                    sb.append(String(transport.incoming.receive(), Charsets.US_ASCII))
                    // неисправный или поддельный адаптер не должен забить память бесконечным ответом
                    if (sb.length > MAX_RESPONSE) return@withTimeoutOrNull false
                }
                val end = sb.indexOf(">")
                lines = parseLines(sb.substring(0, end), cmd)
                // ответ на другую (опоздавшую) команду — выбрасываем и ждём свой
                if (!isStale(cmd, lines)) break
                if (log) onLog("(отброшен запоздавший ответ: ${lines.joinToString(" ")})")
                sb.delete(0, end + 1)
            }
            true
        }
        if (done == false) {
            if (log) onLog("(ответ длиннее ${MAX_RESPONSE / 1024} КБ — прерван)")
            throw ElmTimeoutException(cmd, "Слишком длинный ответ на $cmd")
        }
        done ?: run {
            desynced = true
            if (log) onLog("(нет ответа)")
            throw ElmTimeoutException(cmd)
        }
        if (log) lines.forEach(onLog)
        lines
    }

    /** Ждём запоздавшее приглашение '>' от прошлой команды (не дольше [RESYNC_MS]) и выбрасываем всё до него. */
    private suspend fun resync() {
        desynced = false
        withTimeoutOrNull(RESYNC_MS) {
            var tail = ""
            while (true) {
                tail = (tail + String(transport.incoming.receive(), Charsets.US_ASCII)).takeLast(64)
                if ('>' in tail) break
            }
        }
    }

    suspend fun initialize(): ElmInfo {
        try {
            send("ATZ", 5000, log = true)
        } catch (_: ElmTimeoutException) {
            // некоторые клоны молчат после сброса — продолжаем
        }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
            send(c, log = true)
        }
        val version = send("ATI", log = true).joinToString(" ")
        val voltage = readVoltage(log = true)
        // Первый запрос к ЭБУ запускает автоопределение протокола — это может занять 10+ секунд.
        val ecu = try {
            send("0100", 20_000, log = true).any { it.replace(" ", "").startsWith("4100") }
        } catch (_: ElmTimeoutException) {
            false
        }
        val protocol = refreshProtocol()
        return ElmInfo(version, protocol, voltage, ecu)
    }

    /** Перечитывает протокол (после автоопределения). Возвращает его описание из ATDP. */
    suspend fun refreshProtocol(): String {
        protocolNumber = send("ATDPN", log = true).firstOrNull()?.removePrefix("A")?.firstOrNull() ?: '0'
        return send("ATDP", log = true).firstOrNull().orEmpty()
    }

    suspend fun readVoltage(log: Boolean = false): String? = try {
        send("ATRV", log = log).firstOrNull()?.takeIf { it.any(Char::isDigit) }
    } catch (_: ElmTimeoutException) {
        null
    }

    private fun parseLines(raw: String, cmd: String): List<String> =
        raw.replace("\u0000", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .map { it.replace("SEARCHING...", "").replace(BUS_INIT, "").trim() }
            .filter { it.isNotEmpty() }

    internal companion object {
        private val OBD_CMD = Regex("^(0[1-9A])([0-9A-F]{2})?[0-9A-F]*$")
        private val CAN_FRAME = Regex("^[0-9A-F]:[0-9A-F]+$")
        private val OBD_REPLY = Regex("^4[0-9A-F]{3,}$")
        private val VOLTAGE = Regex("^\\d{1,2}(\\.\\d)?V$")

        /**
         * Ответ явно не на [cmd]: без эха (ATE0) ELM не говорит, на что отвечает, поэтому смотрим на содержимое.
         * На OBD-запрос «01 0C» ответ начинается с «41 0C» (или это текст ошибки — NO DATA и т.п.);
         * ответ OBD (41…, 43…) на AT-команду — точно чужой.
         */
        fun isStale(cmd: String, lines: List<String>): Boolean {
            val c = cmd.replace(" ", "").uppercase()
            val compact = lines.map { it.replace(" ", "").uppercase() }
            val m = OBD_CMD.find(c)
            if (m == null) {
                if (!c.startsWith("AT") || compact.isEmpty()) return false
                if (compact.all { OBD_REPLY.matches(it) || CAN_FRAME.matches(it) }) return true
                // напряжение пришло не на ATRV
                return c != "ATRV" && compact.any { VOLTAGE.matches(it) }
            }
            val mode = "%02X".format(m.groupValues[1].toInt(16) + 0x40)
            // в ответе на запрос нескольких PID ЭБУ может поставить первым любой из них
            val prefixes = when (mode) {
                "41", "42" -> c.substring(2).chunked(2).filter { it.length == 2 }.map { mode + it }.ifEmpty { listOf(mode) }
                "49" -> listOf(mode + m.groupValues[2])
                else -> listOf(mode)
            }
            val msgs = com.dre.carbook.elm.obd.ObdParser.messages(lines)
            if (msgs.any { msg -> prefixes.any { msg.startsWith(it) } }) return false
            // свой ответ «нет данных»/ошибка или что-то нераспознанное — не наше дело его отбрасывать
            if (msgs.isEmpty()) return compact.isNotEmpty() && compact.all { it == "OK" || VOLTAGE.matches(it) }
            // ответ режима 4x на другой запрос (или на другой PID)
            return msgs.all { OBD_REPLY.matches(it) }
        }

        const val MAX_RESPONSE = 64 * 1024
        const val RESYNC_MS = 1500L
        val BUS_INIT = Regex("BUS INIT:\\s*\\.*")
    }
}
