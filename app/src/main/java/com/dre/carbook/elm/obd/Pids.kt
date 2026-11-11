package com.dre.carbook.elm.obd

/**
 * Параметр режима 01. Формула получает байты данных ответа (A, B, C, D…).
 * [bytes] — сколько байтов нужно формуле, [size] — полная длина ответа по SAE J1979
 * (нужна, чтобы разрезать ответ на запрос нескольких PID сразу).
 */
class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val decimals: Int = 0,
    val bytes: Int = 1,
    val size: Int = bytes,
    private val formula: (IntArray) -> Double,
) {
    fun decode(data: IntArray): Double? = if (data.size < bytes) null else formula(data)

    fun format(value: Double?): String =
        if (value == null) "—" else "%.${decimals}f".format(value)
}

private val IntArray.a get() = this[0]
private val IntArray.b get() = this[1]
private val IntArray.ab get() = this[0] * 256 + this[1]
private val IntArray.cd get() = this[2] * 256 + this[3]
private val IntArray.abcd get() = (this[0].toLong() shl 24) + (this[1] shl 16) + (this[2] shl 8) + this[3]

private fun pct(a: Int) = a * 100 / 255.0
private fun trim(a: Int) = (a - 128) * 100 / 128.0

object Pids {
    val all: List<Pid> = buildList {
        // основные
        add(Pid(0x0C, "Обороты двигателя", "об/мин", bytes = 2) { it.ab / 4.0 })
        add(Pid(0x0D, "Скорость", "км/ч") { it.a.toDouble() })
        add(Pid(0x05, "Температура ОЖ", "°C") { it.a - 40.0 })
        add(Pid(0x04, "Расчётная нагрузка двигателя", "%") { pct(it.a) })
        add(Pid(0x11, "Положение дросселя", "%") { pct(it.a) })
        add(Pid(0x0F, "Температура воздуха на впуске", "°C") { it.a - 40.0 })
        add(Pid(0x0B, "Давление во впуске (MAP)", "кПа") { it.a.toDouble() })
        add(Pid(0x10, "Расход воздуха (MAF)", "г/с", 2, bytes = 2) { it.ab / 100.0 })
        add(Pid(0x0E, "Угол опережения зажигания", "°", 1) { it.a / 2.0 - 64 })
        add(Pid(0x42, "Напряжение ЭБУ", "В", 2, bytes = 2) { it.ab / 1000.0 })
        add(Pid(0x5C, "Температура масла", "°C") { it.a - 40.0 })
        add(Pid(0x46, "Температура наружного воздуха", "°C") { it.a - 40.0 })
        add(Pid(0x33, "Атмосферное давление", "кПа") { it.a.toDouble() })
        add(Pid(0x01, "Количество кодов ошибок", "", size = 4) { (it.a and 0x7F).toDouble() })
        // топливо
        add(Pid(0x2F, "Уровень топлива", "%") { pct(it.a) })
        add(Pid(0x5E, "Расход топлива", "л/ч", 2, bytes = 2) { it.ab / 20.0 })
        add(Pid(0x06, "Краткосрочная коррекция топлива, банк 1", "%", 1) { trim(it.a) })
        add(Pid(0x07, "Долгосрочная коррекция топлива, банк 1", "%", 1) { trim(it.a) })
        add(Pid(0x08, "Краткосрочная коррекция топлива, банк 2", "%", 1) { trim(it.a) })
        add(Pid(0x09, "Долгосрочная коррекция топлива, банк 2", "%", 1) { trim(it.a) })
        add(Pid(0x0A, "Давление топлива", "кПа") { it.a * 3.0 })
        add(Pid(0x22, "Давление в топливной рампе (отн. разрежения)", "кПа", 1, bytes = 2) { it.ab * 0.079 })
        add(Pid(0x23, "Давление в топливной рампе", "кПа", bytes = 2) { it.ab * 10.0 })
        add(Pid(0x59, "Абсолютное давление в топливной рампе", "кПа", bytes = 2) { it.ab * 10.0 })
        add(Pid(0x5D, "Момент впрыска топлива", "°", 2, bytes = 2) { it.ab / 128.0 - 210 })
        add(Pid(0x52, "Доля этанола в топливе", "%") { pct(it.a) })
        add(Pid(0x44, "Заданный коэффициент избытка воздуха", "λ", 3, bytes = 2) { it.ab * 2 / 65536.0 })
        add(Pid(0x2E, "Заданная продувка адсорбера", "%") { pct(it.a) })
        add(Pid(0x32, "Давление паров в системе EVAP", "Па", 1, bytes = 2) {
            ((it.ab xor 0x8000) - 0x8000) / 4.0
        })
        // кислородные датчики (узкополосные)
        val o2 = listOf("Б1 Д1", "Б1 Д2", "Б1 Д3", "Б1 Д4", "Б2 Д1", "Б2 Д2", "Б2 Д3", "Б2 Д4")
        o2.forEachIndexed { i, s ->
            add(Pid(0x14 + i, "Датчик кислорода $s, напряжение", "В", 3, size = 2) { it.a / 200.0 })
        }
        // широкополосные (лямбда)
        o2.forEachIndexed { i, s ->
            add(Pid(0x24 + i, "Лямбда $s", "λ", 3, bytes = 2, size = 4) { it.ab * 2 / 65536.0 })
        }
        o2.forEachIndexed { i, s ->
            add(Pid(0x34 + i, "Ток ШДК $s", "мА", 2, bytes = 4) { it.cd / 256.0 - 128 })
        }
        // дроссель и педаль
        add(Pid(0x45, "Относительное положение дросселя", "%") { pct(it.a) })
        add(Pid(0x47, "Абсолютное положение дросселя B", "%") { pct(it.a) })
        add(Pid(0x48, "Абсолютное положение дросселя C", "%") { pct(it.a) })
        add(Pid(0x49, "Положение педали газа D", "%") { pct(it.a) })
        add(Pid(0x4A, "Положение педали газа E", "%") { pct(it.a) })
        add(Pid(0x4B, "Положение педали газа F", "%") { pct(it.a) })
        add(Pid(0x4C, "Заданное положение дросселя", "%") { pct(it.a) })
        add(Pid(0x5A, "Относительное положение педали газа", "%") { pct(it.a) })
        // нагрузка и момент
        add(Pid(0x43, "Абсолютная нагрузка", "%", bytes = 2) { it.ab * 100 / 255.0 })
        add(Pid(0x61, "Запрошенный крутящий момент", "%") { it.a - 125.0 })
        add(Pid(0x62, "Фактический крутящий момент", "%") { it.a - 125.0 })
        add(Pid(0x63, "Номинальный крутящий момент", "Н·м", bytes = 2) { it.ab.toDouble() })
        // EGR
        add(Pid(0x2C, "Заданная рециркуляция ОГ (EGR)", "%") { pct(it.a) })
        add(Pid(0x2D, "Ошибка EGR", "%", 1) { trim(it.a) })
        // катализатор
        add(Pid(0x3C, "Температура катализатора Б1 Д1", "°C", 1, bytes = 2) { it.ab / 10.0 - 40 })
        add(Pid(0x3D, "Температура катализатора Б2 Д1", "°C", 1, bytes = 2) { it.ab / 10.0 - 40 })
        add(Pid(0x3E, "Температура катализатора Б1 Д2", "°C", 1, bytes = 2) { it.ab / 10.0 - 40 })
        add(Pid(0x3F, "Температура катализатора Б2 Д2", "°C", 1, bytes = 2) { it.ab / 10.0 - 40 })
        // счётчики
        add(Pid(0x1F, "Время работы с запуска", "с", bytes = 2) { it.ab.toDouble() })
        add(Pid(0x21, "Пробег с горящим Check Engine", "км", bytes = 2) { it.ab.toDouble() })
        add(Pid(0x31, "Пробег после сброса ошибок", "км", bytes = 2) { it.ab.toDouble() })
        add(Pid(0x30, "Прогревов после сброса ошибок", "") { it.a.toDouble() })
        add(Pid(0x4D, "Время с горящим Check Engine", "мин", bytes = 2) { it.ab.toDouble() })
        add(Pid(0x4E, "Время после сброса ошибок", "мин", bytes = 2) { it.ab.toDouble() })
        add(Pid(0xA6, "Одометр", "км", 1, bytes = 4) { it.abcd / 10.0 })
        // гибрид
        add(Pid(0x5B, "Заряд гибридной батареи", "%") { pct(it.a) })
    }

    val byCode = all.associateBy { it.code }

    val defaultSelection = setOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F, 0x42)
}
