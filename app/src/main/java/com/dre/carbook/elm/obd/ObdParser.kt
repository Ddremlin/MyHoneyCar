package com.dre.carbook.elm.obd

/** Разбор ответов ELM327 при ATS0/ATH0 (без пробелов и заголовков). */
object ObdParser {
    private val HEX = Regex("^[0-9A-F]+$")
    private val FRAME = Regex("^[0-9A-F]:[0-9A-F]*$")
    private val LENGTH = Regex("^[0-9A-F]{3}$")
    private val PADDING = setOf(0x00, 0x55, 0xAA, 0xCC)

    /**
     * Ответ → сообщения в виде hex-строк. Многокадровый ответ CAN
     * ("014", "0:490201...", "1:...") склеивается в одно сообщение и обрезается по длине.
     * Если отвечают несколько ЭБУ, каждое сообщение (строка длины или кадр 0:) начинает новое.
     */
    fun messages(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var current: StringBuilder? = null
        var length: Int? = null
        fun flush() {
            current?.let { sb ->
                val len = length
                out += if (len != null && sb.length > len * 2) sb.substring(0, len * 2) else sb.toString()
            }
            current = null
            length = null
        }
        for (line in lines.map { it.replace(" ", "").uppercase() }) {
            when {
                LENGTH.matches(line) -> {
                    flush()
                    current = StringBuilder()
                    length = line.toInt(16)
                }
                FRAME.matches(line) -> {
                    val sb = current
                    if (sb == null || (line[0] == '0' && sb.isNotEmpty())) {
                        flush()
                        current = StringBuilder()
                    }
                    current!!.append(line.substringAfter(':'))
                }
                HEX.matches(line) && line.length % 2 == 0 -> {
                    flush()
                    out += line
                }
            }
        }
        flush()
        return out
    }

    /**
     * Ответ на запрос нескольких PID ("010C0D05") → данные по каждому PID.
     * null — ответ не удалось однозначно разобрать (адаптер/ЭБУ не умеет такие запросы).
     */
    fun parseMulti(lines: List<String>, requested: List<Pid>): Map<Int, IntArray>? {
        val byCode = requested.associateBy { it.code }
        val msgs = messages(lines)
        if (msgs.isEmpty()) return null
        val out = mutableMapOf<Int, IntArray>()
        for (m in msgs) {
            if (!m.startsWith("41")) return null
            val bytes = hexToBytes(m.substring(2))
            var i = 0
            while (i < bytes.size) {
                val pid = byCode[bytes[i]] ?: break
                if (i + 1 + pid.size > bytes.size) return null
                out.putIfAbsent(pid.code, bytes.copyOfRange(i + 1, i + 1 + pid.size))
                i += 1 + pid.size
            }
            // хвост допустим только как заполнитель кадра CAN
            val tail = bytes.drop(i)
            if (tail.isNotEmpty() && (tail.any { it != tail[0] } || tail[0] !in PADDING)) return null
        }
        return out
    }

    /** Байты данных режима 01 от каждого ответившего ЭБУ. */
    fun mode01All(lines: List<String>, pid: Int): List<IntArray> {
        val prefix = "41%02X".format(pid)
        return messages(lines).filter { it.startsWith(prefix) }.map { hexToBytes(it.substring(prefix.length)) }
    }

    fun mode01(lines: List<String>, pid: Int): IntArray? = mode01All(lines, pid).firstOrNull()

    /** Битовая маска поддерживаемых PID из ответа на 0100/0120/…; объединяется по всем ЭБУ. */
    fun supportedPids(lines: List<String>, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (data in mode01All(lines, base)) {
            if (data.size < 4) continue
            val mask = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
            for (i in 1..32) if (mask and (1L shl (32 - i)) != 0L) out += base + i
        }
        return out
    }

    /** Коды неисправностей из ответа режима 03/07/0A (responseMode = 0x43/0x47/0x4A). */
    fun parseDtcs(lines: List<String>, responseMode: Int, isCan: Boolean): List<String> {
        val prefix = "%02X".format(responseMode)
        val out = LinkedHashSet<String>()
        for (m in messages(lines)) {
            if (!m.startsWith(prefix)) continue
            var data = m.substring(2)
            var limit = Int.MAX_VALUE
            if (isCan && data.length >= 2) {
                // в CAN первый байт — число кодов
                limit = data.substring(0, 2).toInt(16)
                data = data.substring(2)
            }
            data.chunked(4).filter { it.length == 4 }.take(limit).forEach {
                if (it != "0000") out += decodeDtc(it)
            }
        }
        return out.toList()
    }

    /** "0133" → "P0133". */
    fun decodeDtc(hex: String): String {
        val b0 = hex.substring(0, 2).toInt(16)
        return "PCBU"[b0 shr 6].toString() + ((b0 shr 4) and 3) + hex.substring(1)
    }

    fun parseVin(lines: List<String>): String? {
        val sb = StringBuilder()
        for (m in messages(lines)) {
            if (!m.startsWith("4902") || m.length < 6) continue
            // после 49 02 идёт номер сообщения/счётчик, потом ASCII
            hexToBytes(m.substring(6)).forEach { sb.append(it.toChar()) }
        }
        val vin = sb.filter { it in '0'..'9' || it in 'A'..'Z' }.takeLast(17).toString()
        return vin.ifEmpty { null }
    }

    fun hexToBytes(hex: String): IntArray =
        IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
}
