package com.dre.carbook

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// ---------- деньги и числа ----------

/** "1 234,50" / "1234.5" / "1 234" -> копейки; null, если не число. */
fun parseMoney(s: String): Long? {
    val t = s.replace(" ", "").replace(" ", "").replace(',', '.').trim()
    if (t.isEmpty()) return null
    val v = t.toBigDecimalOrNull() ?: return null
    if (v.signum() < 0) return null
    return v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}

fun parseDecimal(s: String): Double? =
    s.replace(" ", "").replace(',', '.').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }

fun parseInt(s: String): Int? = s.replace(" ", "").trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it >= 0 }

private fun groupThousands(n: Long): String {
    val s = abs(n).toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, ch ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(ch)
    }
    return (if (n < 0) "-" else "") + sb
}

/** Валюта, в которой ведутся записи. Только обозначение — суммы при смене не пересчитываются. */
enum class Currency(val symbol: String, val title: String) {
    RUB("₽", "Российский рубль"),
    USD("$", "Доллар США"),
    EUR("€", "Евро"),
    BYN("Br", "Белорусский рубль"),
    KZT("₸", "Казахстанский тенге"),
    UAH("₴", "Украинская гривна"),
    UZS("сўм", "Узбекский сум"),
    KGS("сом", "Киргизский сом"),
    AMD("֏", "Армянский драм"),
    GEL("₾", "Грузинский лари"),
    AZN("₼", "Азербайджанский манат"),
    TRY("₺", "Турецкая лира"),
    CNY("¥", "Китайский юань"),
    AED("AED", "Дирхам ОАЭ"),
    PLN("zł", "Польский злотый"),
    GBP("£", "Фунт стерлингов");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.name == code } ?: RUB
    }
}

/** Текущий знак валюты для форматирования; выставляет Repo при загрузке и смене. */
@Volatile var currencySymbol: String = Currency.RUB.symbol

/** Копейки -> "1 234,50 ₽" (копейки скрываются, если их нет). */
fun money(kop: Long, withSign: Boolean = true): String {
    val rub = kop / 100
    val k = abs(kop % 100)
    val body = groupThousands(rub) + if (k != 0L) ",%02d".format(k) else ""
    return if (withSign) "$body $currencySymbol" else body
}

/** Копейки -> строка для поля ввода. */
fun moneyInput(kop: Long): String = if (kop % 100 == 0L) (kop / 100).toString() else "%d,%02d".format(kop / 100, kop % 100)

fun km(n: Int) = groupThousands(n.toLong()) + " км"

fun num1(d: Double): String = "%.1f".format(d).replace('.', ',')
fun num2(d: Double): String = "%.2f".format(d).replace('.', ',').removeSuffix(",00")

private val MONTHS = arrayOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
    "сентябрь", "октябрь", "ноябрь", "декабрь")
private val MONTHS_GEN = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
    "сентября", "октября", "ноября", "декабря")
private val MONTHS_SHORT = arrayOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

fun monthTitle(ym: YearMonth) = MONTHS[ym.monthValue - 1].replaceFirstChar { it.uppercase() } + " " + ym.year
fun monthShort(m: Int) = MONTHS_SHORT[m - 1]
fun dateText(d: LocalDate, withYear: Boolean = true) =
    "${d.dayOfMonth} ${MONTHS_GEN[d.monthValue - 1]}" + if (withYear) " ${d.year}" else ""

// ---------- расход топлива ----------

data class FuelStats(
    /** Средний расход, л/100 км (по полным заправкам), или null, если данных мало. */
    val avgPer100: Double?,
    val lastPer100: Double?,
    val totalLiters: Double,
    val avgPricePerLiter: Double?,
)

/**
 * Метод «от полного бака до полного»: между двумя заправками до полного считаем
 * литры всех заправок после первой (включая последнюю) и делим на пройденный путь.
 */
fun fuelStats(expenses: List<Expense>): FuelStats {
    val fuel = expenses.filter { it.category == Category.FUEL && (it.liters ?: 0.0) > 0 }
    val totalLiters = fuel.sumOf { it.liters ?: 0.0 }
    val totalMoney = fuel.sumOf { it.amount }
    val avgPrice = if (totalLiters > 0) totalMoney / 100.0 / totalLiters else null

    val withKm = fuel.filter { it.mileage != null }.sortedWith(compareBy({ it.mileage }, { it.date }))
    var startKm: Int? = null
    var acc = 0.0
    var sumLiters = 0.0
    var sumKm = 0
    var last: Double? = null
    for (e in withKm) {
        if (startKm == null) {
            if (e.fullTank) startKm = e.mileage
            continue
        }
        acc += e.liters!!
        if (e.fullTank) {
            val dist = e.mileage!! - startKm
            if (dist > 0) {
                sumLiters += acc; sumKm += dist
                last = acc / dist * 100
            }
            startKm = e.mileage; acc = 0.0
        }
    }
    val avg = if (sumKm > 0) sumLiters / sumKm * 100 else null
    return FuelStats(avg, last, totalLiters, avgPrice)
}

// ---------- регламент ТО ----------

enum class DueState { OK, SOON, OVERDUE, UNKNOWN }

data class ServiceStatus(
    val item: ServiceItem,
    val nextKm: Int?,
    val leftKm: Int?,
    val nextDate: LocalDate?,
    val leftDays: Long?,
    val state: DueState,
)

const val SOON_KM = 1000
const val SOON_DAYS = 30L

fun serviceStatus(s: ServiceItem, currentKm: Int, today: LocalDate = LocalDate.now()): ServiceStatus {
    val nextKm = if (s.intervalKm != null && s.lastKm != null) s.lastKm + s.intervalKm else null
    val leftKm = nextKm?.let { it - currentKm }
    val nextDate = if (s.intervalMonths != null && s.lastDate != null) s.lastDate.plusMonths(s.intervalMonths.toLong()) else null
    val leftDays = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
    val state = when {
        leftKm == null && leftDays == null -> DueState.UNKNOWN
        (leftKm != null && leftKm < 0) || (leftDays != null && leftDays < 0) -> DueState.OVERDUE
        (leftKm != null && leftKm <= SOON_KM) || (leftDays != null && leftDays <= SOON_DAYS) -> DueState.SOON
        else -> DueState.OK
    }
    return ServiceStatus(s, nextKm, leftKm, nextDate, leftDays, state)
}

fun sortedStatuses(items: List<ServiceItem>, currentKm: Int, today: LocalDate = LocalDate.now()) =
    items.map { serviceStatus(it, currentKm, today) }.sortedWith(
        compareBy<ServiceStatus>({
            when (it.state) { DueState.OVERDUE -> 0; DueState.SOON -> 1; DueState.OK -> 2; DueState.UNKNOWN -> 3 }
        }, { it.leftKm ?: Int.MAX_VALUE }, { it.leftDays ?: Long.MAX_VALUE })
    )

// ---------- сводка за период ----------

data class Summary(
    val total: Long,
    val count: Int,
    val byCategory: List<Pair<Category, Long>>,
    /** Пройдено км по записям периода (макс − мин пробег). */
    val distance: Int?,
    val perKm: Double?,
)

fun summary(expenses: List<Expense>): Summary {
    val total = expenses.sumOf { it.amount }
    val byCat = expenses.groupBy { it.category }.map { (c, l) -> c to l.sumOf { it.amount } }
        .filter { it.second > 0 }.sortedByDescending { it.second }
    val kms = expenses.mapNotNull { it.mileage }
    val dist = if (kms.size >= 2) kms.max() - kms.min() else null
    val perKm = if (dist != null && dist > 0) total / 100.0 / dist else null
    return Summary(total, expenses.size, byCat, dist?.takeIf { it > 0 }, perKm)
}

/** Сумма по месяцам года, индекс 0 = январь. */
fun monthlyTotals(expenses: List<Expense>, year: Int): LongArray {
    val arr = LongArray(12)
    expenses.filter { it.date.year == year }.forEach { arr[it.date.monthValue - 1] += it.amount }
    return arr
}

// ---------- CSV ----------

fun toCsv(car: Car, expenses: List<Expense>): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("﻿Дата;Категория;Сумма, $currencySymbol;Пробег;Литры;Полный бак;Комментарий\n")
    expenses.sortedBy { it.date }.forEach { e ->
        sb.append(e.date).append(';').append(q(e.category.title)).append(';')
            .append(moneyInput(e.amount)).append(';').append(e.mileage ?: "").append(';')
            .append(e.liters?.let { num2(it) } ?: "").append(';')
            .append(if (e.category == Category.FUEL) (if (e.fullTank) "да" else "нет") else "").append(';')
            .append(q(e.note)).append('\n')
    }
    return sb.toString()
}
