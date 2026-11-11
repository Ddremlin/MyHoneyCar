package com.dre.carbook.elm

import android.content.Context
import android.util.Log
import com.dre.carbook.ObdPacket
import com.dre.carbook.Reminders
import com.dre.carbook.SensorStat
import com.dre.carbook.repo

/**
 * Передача данных диагностики в учёт автомобиля (тот же процесс, без broadcast):
 * пробег по одометру, км записанных поездок, VIN, коды ошибок и сводка по датчикам.
 */
object CarBookSync {
    private val VIN_RE = Regex("[A-HJ-NPR-Z0-9]{17}")
    private val DTC_RE = Regex("[PCBU][0-9A-F]{4}")

    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(ctx: Context): Boolean = true

    /**
     * @param vin VIN автомобиля, если известен (по нему выбирается машина)
     * @param odometerKm показания одометра из ЭБУ (PID 01A6)
     * @param trip пройденное в поездке расстояние и её время
     * @param dtc коды ошибок (сохранённые + ожидающие), если их только что прочитали
     * @param mil горит ли Check Engine (из 0101), если известно
     * @param sensors сводка по датчикам за последние минуты опроса — для анализа нейросетью
     * @return true, если данные применены к автомобилю
     */
    fun send(
        ctx: Context,
        vin: String? = null,
        odometerKm: Double? = null,
        trip: Trip? = null,
        dtc: List<String>? = null,
        mil: Boolean? = null,
        sensors: List<Sensor>? = null,
    ): Boolean {
        val v = vin?.uppercase()?.takeIf { VIN_RE.matches(it) }
        val odo = odometerKm?.takeIf { it in 1.0..3_000_000.0 }
        val tripKm = trip?.km?.takeIf { it in 0.0..3000.0 }
        val codes = dtc?.map { it.uppercase() }?.filter { DTC_RE.matches(it) }?.distinct()?.take(50)
        val stats = sensors?.take(MAX_SENSORS)?.filter { s ->
            listOf(s.min, s.avg, s.max, s.last).all { !it.isNaN() && !it.isInfinite() }
        }?.map { SensorStat(it.code, it.name, it.unit, it.min, it.avg, it.max, it.last, it.n, it.from, it.to) }
            ?.takeIf { it.isNotEmpty() }
        if (v == null && odo == null && tripKm == null && codes == null && stats == null) return false
        return runCatching {
            val p = ObdPacket(System.currentTimeMillis(), v, odo, tripKm, trip?.end?.takeIf { tripKm != null }, codes, mil, stats)
            val applied = ctx.repo.applyObd(p) != null
            if (applied) Reminders.check(ctx)
            applied
        }.onFailure { Log.w("CarBookSync", "apply failed", it) }.getOrDefault(false)
    }

    data class Trip(val km: Double, val start: Long, val end: Long)

    /** Сводка по одному параметру за окно опроса [from]..[to] (мс). */
    data class Sensor(
        val code: Int, val name: String, val unit: String,
        val min: Double, val avg: Double, val max: Double, val last: Double,
        val n: Int, val from: Long, val to: Long,
    )

    private const val MAX_SENSORS = 100
}
