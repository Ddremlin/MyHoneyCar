package com.dre.carbook

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

enum class Category(val title: String, val emoji: String) {
    FUEL("Топливо", "⛽"),
    SERVICE("ТО и обслуживание", "🔧"),
    REPAIR("Ремонт", "🛠"),
    PARTS("Запчасти", "⚙"),
    TIRES("Шины", "🛞"),
    INSURANCE("Страховка", "📄"),
    TAX("Налоги и сборы", "🏛"),
    WASH("Мойка", "🧽"),
    PARKING("Парковка, платные дороги", "🅿"),
    FINES("Штрафы", "🚨"),
    ACCESSORIES("Аксессуары", "🎒"),
    OTHER("Прочее", "•");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

data class Car(
    val id: String = newId(),
    val brand: String,
    val model: String = "",
    /** Поколение / кузов, например «XV70 (2017–2024)». */
    val generation: String = "",
    val plate: String = "",
    val year: Int? = null,
    val vin: String = "",
    /** Пробег, введённый вручную (актуальный пробег = максимум из этого и записей). */
    val odometer: Int = 0,
    val tankLiters: Int? = null,
    /** Км, пройденные в поездках из ELM OBD после последнего известного пробега (оценка). */
    val tripKm: Double = 0.0,
    /** Конец последней учтённой поездки из ELM OBD (мс), чтобы не учесть её дважды. */
    val lastTripEnd: Long = 0,
    /** Последний обмен с ELM OBD, мс. */
    val obdSyncAt: Long? = null,
    /** Когда пробег последний раз получен из одометра ЭБУ, мс. */
    val obdOdoAt: Long? = null,
    /** Коды ошибок из последнего чтения в ELM OBD. */
    val dtc: List<String> = emptyList(),
    val dtcAt: Long? = null,
    /** Горел ли Check Engine при последнем чтении ошибок. */
    val mil: Boolean? = null,
    /** Сводка по датчикам из последнего опроса в ELM OBD. */
    val sensors: List<SensorStat> = emptyList(),
    val sensorsAt: Long? = null,
    /** Последний ответ нейросети и когда он получен. */
    val aiReport: String = "",
    val aiReportAt: Long? = null,
) {
    /** Как показывать машину: «Марка Модель». */
    val name: String get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Автомобиль" }
}

/** Параметр OBD за окно опроса [from]..[to] (мс): минимум, среднее, максимум, последнее, число замеров. */
data class SensorStat(
    val pid: Int,
    val name: String,
    val unit: String,
    val min: Double,
    val avg: Double,
    val max: Double,
    val last: Double,
    val n: Int,
    val from: Long,
    val to: Long,
)

/** Сумма хранится в копейках, чтобы не терять точность. */
data class Expense(
    val id: String = newId(),
    val carId: String,
    val date: LocalDate,
    val category: Category,
    val amount: Long,
    val mileage: Int? = null,
    val liters: Double? = null,
    val fullTank: Boolean = true,
    val note: String = "",
    val serviceId: String? = null,
    /** Имена файлов фото чеков в каталоге receipts. */
    val photos: List<String> = emptyList(),
)

data class ServiceItem(
    val id: String = newId(),
    val carId: String,
    val name: String,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastKm: Int? = null,
    val lastDate: LocalDate? = null,
)

data class AppData(
    val cars: List<Car> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val services: List<ServiceItem> = emptyList(),
    val selectedCarId: String? = null,
    val currency: Currency = Currency.RUB,
) {
    val car: Car? get() = cars.firstOrNull { it.id == selectedCarId } ?: cars.firstOrNull()
    fun expensesOf(carId: String) = expenses.filter { it.carId == carId }
    fun servicesOf(carId: String) = services.filter { it.carId == carId }

    /** Актуальный пробег автомобиля. */
    fun currentKm(car: Car): Int =
        maxOf(car.odometer + car.tripKm.toInt(), expensesOf(car.id).maxOfOrNull { it.mileage ?: 0 } ?: 0,
            servicesOf(car.id).maxOfOrNull { it.lastKm ?: 0 } ?: 0)
}

fun newId(): String = UUID.randomUUID().toString()

fun defaultServices(carId: String) = listOf(
    ServiceItem(carId = carId, name = "Моторное масло и масляный фильтр", intervalKm = 10_000, intervalMonths = 12),
    ServiceItem(carId = carId, name = "Воздушный фильтр", intervalKm = 20_000, intervalMonths = 24),
    ServiceItem(carId = carId, name = "Салонный фильтр", intervalKm = 15_000, intervalMonths = 12),
    ServiceItem(carId = carId, name = "Тормозная жидкость", intervalMonths = 24),
    ServiceItem(carId = carId, name = "Тормозные колодки (проверка)", intervalKm = 15_000),
    ServiceItem(carId = carId, name = "Свечи зажигания", intervalKm = 30_000),
    ServiceItem(carId = carId, name = "Охлаждающая жидкость", intervalKm = 60_000, intervalMonths = 48),
    ServiceItem(carId = carId, name = "Масло в КПП", intervalKm = 60_000),
    ServiceItem(carId = carId, name = "Ремень/цепь ГРМ", intervalKm = 90_000),
    ServiceItem(carId = carId, name = INSURANCE_NAME, intervalMonths = 12),
    ServiceItem(carId = carId, name = CASCO_NAME, intervalMonths = 12),
)

const val INSURANCE_NAME = "Страховка"
const val CASCO_NAME = "Автокаско"

/** Пункт регламента про страховку — его выполнение записываем в расходы как «Страховка», а не ТО. */
fun ServiceItem.isInsurance(): Boolean = name.lowercase().let { n -> listOf("страхов", "осаго", "каско").any { it in n } }

// ---------- JSON ----------

object Json {
    const val VERSION = 2

    fun encode(d: AppData): String = JSONObject().apply {
        put("version", VERSION)
        put("selectedCarId", d.selectedCarId ?: JSONObject.NULL)
        put("currency", d.currency.name)
        put("cars", JSONArray(d.cars.map { c ->
            JSONObject().apply {
                put("id", c.id); put("brand", c.brand); put("model", c.model); put("generation", c.generation)
                put("name", c.name); put("plate", c.plate)
                putOpt("year", c.year); put("vin", c.vin); put("odometer", c.odometer)
                putOpt("tankLiters", c.tankLiters)
                put("tripKm", c.tripKm); put("lastTripEnd", c.lastTripEnd)
                putOpt("obdSyncAt", c.obdSyncAt); putOpt("obdOdoAt", c.obdOdoAt)
                put("dtc", JSONArray(c.dtc)); putOpt("dtcAt", c.dtcAt)
                putOpt("mil", c.mil)
                if (c.sensors.isNotEmpty()) put("sensors", encodeSensors(c.sensors))
                putOpt("sensorsAt", c.sensorsAt)
                if (c.aiReport.isNotEmpty()) put("aiReport", c.aiReport)
                putOpt("aiReportAt", c.aiReportAt)
            }
        }))
        put("expenses", JSONArray(d.expenses.map { e ->
            JSONObject().apply {
                put("id", e.id); put("carId", e.carId); put("date", e.date.toString())
                put("category", e.category.name); put("amount", e.amount)
                putOpt("mileage", e.mileage); putOpt("liters", e.liters)
                put("fullTank", e.fullTank); put("note", e.note); putOpt("serviceId", e.serviceId)
                if (e.photos.isNotEmpty()) put("photos", JSONArray(e.photos))
            }
        }))
        put("services", JSONArray(d.services.map { s ->
            JSONObject().apply {
                put("id", s.id); put("carId", s.carId); put("name", s.name)
                putOpt("intervalKm", s.intervalKm); putOpt("intervalMonths", s.intervalMonths)
                putOpt("lastKm", s.lastKm); putOpt("lastDate", s.lastDate?.toString())
            }
        }))
    }.toString(1)

    fun decode(text: String): AppData {
        val o = JSONObject(text)
        val cars = o.optJSONArray("cars").objects().map { c ->
            // до версии 2 было одно поле «Марка и модель»
            val (brand, model) = if (c.has("brand")) c.optString("brand") to c.optString("model")
                else Catalog.splitName(c.optString("name"))
            Car(
                id = c.getString("id"), brand = brand, model = model,
                generation = c.optString("generation"), plate = c.optString("plate"),
                year = c.optIntOrNull("year"), vin = c.optString("vin"), odometer = c.optInt("odometer"),
                tankLiters = c.optIntOrNull("tankLiters"),
                tripKm = c.optDouble("tripKm", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                lastTripEnd = c.optLong("lastTripEnd", 0),
                obdSyncAt = c.optLongOrNull("obdSyncAt"), obdOdoAt = c.optLongOrNull("obdOdoAt"),
                dtc = c.optJSONArray("dtc").strings(), dtcAt = c.optLongOrNull("dtcAt"),
                mil = if (c.has("mil") && !c.isNull("mil")) c.getBoolean("mil") else null,
                sensors = decodeSensors(c.optJSONArray("sensors")), sensorsAt = c.optLongOrNull("sensorsAt"),
                aiReport = c.optString("aiReport"), aiReportAt = c.optLongOrNull("aiReportAt"),
            )
        }
        val expenses = o.optJSONArray("expenses").objects().map { e ->
            Expense(
                id = e.getString("id"), carId = e.getString("carId"),
                date = LocalDate.parse(e.getString("date")), category = Category.of(e.optString("category")),
                amount = e.getLong("amount"), mileage = e.optIntOrNull("mileage"),
                liters = if (e.has("liters") && !e.isNull("liters")) e.getDouble("liters") else null,
                fullTank = e.optBoolean("fullTank", true), note = e.optString("note"),
                serviceId = e.optStringOrNull("serviceId"),
                photos = e.optJSONArray("photos").strings(),
            )
        }
        val services = o.optJSONArray("services").objects().map { s ->
            ServiceItem(
                id = s.getString("id"), carId = s.getString("carId"), name = s.getString("name"),
                intervalKm = s.optIntOrNull("intervalKm"), intervalMonths = s.optIntOrNull("intervalMonths"),
                lastKm = s.optIntOrNull("lastKm"), lastDate = s.optStringOrNull("lastDate")?.let(LocalDate::parse),
            )
        }
        val migrated = if (o.optInt("version", 1) < 2) migrateInsurance(services) else services
        return AppData(cars, expenses, migrated, o.optStringOrNull("selectedCarId"),
            Currency.of(o.optStringOrNull("currency")))
    }

    /** v1 → v2: «ОСАГО» стало «Страховка», рядом добавляется «Автокаско» (12 мес.). */
    internal fun migrateInsurance(services: List<ServiceItem>): List<ServiceItem> {
        val renamed = services.map { if (it.name.trim().equals("ОСАГО", ignoreCase = true)) it.copy(name = INSURANCE_NAME) else it }
        val casco = services.filter { it.name.trim().equals("ОСАГО", ignoreCase = true) }.map { it.carId }.distinct()
            .filter { car -> renamed.none { it.carId == car && it.name.contains("каско", ignoreCase = true) } }
            .map { ServiceItem(carId = it, name = CASCO_NAME, intervalMonths = 12) }
        return renamed + casco
    }

    fun encodeSensors(list: List<SensorStat>): JSONArray = JSONArray(list.map { x ->
        JSONObject().apply {
            put("pid", x.pid); put("name", x.name); put("unit", x.unit)
            put("min", x.min); put("avg", x.avg); put("max", x.max); put("last", x.last)
            put("n", x.n); put("from", x.from); put("to", x.to)
        }
    })

    /**
     * Разбор сводки датчиков. Источник — внешний (ELM OBD или файл копии),
     * поэтому отбрасываем всё подозрительное и ограничиваем размеры.
     */
    fun decodeSensors(a: JSONArray?): List<SensorStat> = a.objects().take(MAX_SENSORS).mapNotNull { o ->
        runCatching {
            val nums = listOf("min", "avg", "max", "last").map { o.getDouble(it) }
            if (nums.any { it.isNaN() || it.isInfinite() || kotlin.math.abs(it) > 1e7 }) return@mapNotNull null
            SensorStat(
                pid = o.getInt("pid").takeIf { it in -1..0xFF } ?: return@mapNotNull null,
                name = o.getString("name").filter { !it.isISOControl() }.take(80).ifBlank { return@mapNotNull null },
                unit = o.optString("unit").filter { !it.isISOControl() }.take(10),
                min = nums[0], avg = nums[1], max = nums[2], last = nums[3],
                n = o.optInt("n").coerceAtLeast(0), from = o.optLong("from"), to = o.optLong("to"),
            )
        }.getOrNull()
    }

    const val MAX_SENSORS = 100

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) getString(k).ifEmpty { null } else null
}
