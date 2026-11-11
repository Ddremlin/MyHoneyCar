package com.dre.carbook

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class CarApp : Application() {
    lateinit var repo: Repo
        private set

    /** Связь с адаптером ELM327 — на уровне приложения, чтобы запись поездки шла в фоне. */
    val obd: com.dre.carbook.elm.ObdSession by lazy { com.dre.carbook.elm.ObdSession(this) }

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
    }
}

val Context.repo: Repo get() = (applicationContext as CarApp).repo

/** Все данные — один JSON-файл во внутреннем хранилище приложения. */
data class ObdPacket(
    val time: Long,
    val vin: String?,
    val odometerKm: Double?,
    val tripKm: Double?,
    val tripEnd: Long?,
    val dtc: List<String>?,
    val mil: Boolean? = null,
    val sensors: List<SensorStat>? = null,
)

class Repo(context: Context) {
    private val file = File(context.filesDir, "data.json")
    /** Фото чеков: JPEG-файлы, в данных хранятся только имена. */
    val receiptsDir = File(context.filesDir, "receipts").apply { mkdirs() }
    private val tmp = File(context.filesDir, "data.json.tmp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = Channel<AppData>(Channel.CONFLATED)

    /** Файл старой версии формата — после разбора сразу пересохраняем, чтобы перенос данных был однократным. */
    private var outdated = false
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    init {
        scope.launch { for (d in saveSignal) write(d) }
        if (outdated) saveSignal.trySend(_data.value)
        currencySymbol = _data.value.currency.symbol
    }

    private fun load(): AppData = try {
        if (file.exists()) file.readText().let { t ->
            outdated = org.json.JSONObject(t).optInt("version", 1) < Json.VERSION
            Json.decode(t)
        } else AppData()
    } catch (e: Exception) {
        Log.w(TAG, "load failed", e)
        // не затираем повреждённый файл — сохраняем копию
        runCatching { file.copyTo(File(file.parentFile, "data.broken.${System.currentTimeMillis()}.json"), overwrite = true) }
        AppData()
    }

    private fun write(d: AppData) {
        try {
            tmp.writeText(Json.encode(d))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    private fun edit(f: (AppData) -> AppData) {
        _data.update(f)
        currencySymbol = _data.value.currency.symbol
        saveSignal.trySend(_data.value)
    }

    // ---------- автомобили ----------

    fun saveCar(car: Car, withDefaultServices: Boolean = false) = edit { d ->
        val exists = d.cars.any { it.id == car.id }
        d.copy(
            cars = if (exists) d.cars.map { if (it.id == car.id) car else it } else d.cars + car,
            services = if (!exists && withDefaultServices) d.services + defaultServices(car.id) else d.services,
            selectedCarId = if (exists) d.selectedCarId else car.id,
        )
    }

    fun deleteCar(id: String) = edit { d ->
        deletePhotos(d.expenses.filter { it.carId == id }.flatMap { it.photos })
        val cars = d.cars.filter { it.id != id }
        d.copy(
            cars = cars,
            expenses = d.expenses.filter { it.carId != id },
            services = d.services.filter { it.carId != id },
            selectedCarId = if (d.selectedCarId == id) cars.firstOrNull()?.id else d.selectedCarId,
        )
    }

    fun selectCar(id: String) = edit { it.copy(selectedCarId = id) }

    fun setCurrency(c: Currency) = edit { it.copy(currency = c) }

    fun setOdometer(carId: String, km: Int) = edit { d ->
        d.copy(cars = d.cars.map { if (it.id == carId) it.copy(odometer = km, tripKm = 0.0) else it })
    }

    /** Введённый пробег не меньше текущего — он становится точкой отсчёта, оценка по поездкам сбрасывается. */
    private fun AppData.withKnownKm(carId: String, km: Int?): AppData {
        val car = cars.firstOrNull { it.id == carId } ?: return this
        if (km == null || km < currentKm(car)) return this
        return copy(cars = cars.map { if (it.id == carId) it.copy(odometer = km, tripKm = 0.0) else it })
    }

    // ---------- расходы ----------

    fun saveExpense(e: Expense) = edit { d ->
        val old = d.expenses.firstOrNull { it.id == e.id }
        old?.let { deletePhotos(it.photos - e.photos.toSet()) }
        d.withKnownKm(e.carId, e.mileage)
            .copy(expenses = if (old != null) d.expenses.map { if (it.id == e.id) e else it } else d.expenses + e)
    }

    fun deleteExpense(id: String) = edit { d ->
        d.expenses.firstOrNull { it.id == id }?.let { deletePhotos(it.photos) }
        d.copy(expenses = d.expenses.filter { it.id != id })
    }

    fun deletePhotos(names: Collection<String>) = names.forEach { photoFile(it)?.delete() }

    /** Файл фото по имени из данных (имя без путей — защита от «../»). */
    fun photoFile(name: String): File? =
        name.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }?.let { File(receiptsDir, it) }

    // ---------- ТО ----------

    fun saveService(s: ServiceItem) = edit { d ->
        val exists = d.services.any { it.id == s.id }
        d.copy(services = if (exists) d.services.map { if (it.id == s.id) s else it } else d.services + s)
    }

    fun deleteService(id: String) = edit { d ->
        d.copy(
            services = d.services.filter { it.id != id },
            expenses = d.expenses.map { if (it.serviceId == id) it.copy(serviceId = null) else it },
        )
    }

    /** Отметить выполнение работы; при сумме > 0 создаётся запись расхода. */
    fun markServiceDone(s: ServiceItem, date: java.time.LocalDate, km: Int?, amount: Long?, note: String) = edit { d ->
        val updated = s.copy(lastDate = date, lastKm = km ?: s.lastKm)
        val expenses = if (amount != null && amount > 0) d.expenses + Expense(
            carId = s.carId, date = date, category = if (s.isInsurance()) Category.INSURANCE else Category.SERVICE, amount = amount,
            mileage = km, note = listOf(s.name, note).filter { it.isNotBlank() }.joinToString(" — "),
            serviceId = s.id,
        ) else d.expenses
        d.withKnownKm(s.carId, km).copy(services = d.services.map { if (it.id == s.id) updated else it }, expenses = expenses)
    }

    // ---------- ELM OBD ----------

    /** Данные от ELM OBD. Возвращает автомобиль, к которому они применены (или null). */
    fun applyObd(p: ObdPacket): Car? {
        var target: Car? = null
        edit { d ->
            val car = pickCar(d, p.vin) ?: return@edit d
            var c = car.copy(obdSyncAt = p.time)
            if (p.vin != null && c.vin.isBlank()) c = c.copy(vin = p.vin)
            if (p.odometerKm != null) {
                c = c.copy(odometer = p.odometerKm.toInt(), tripKm = 0.0, obdOdoAt = p.time)
            }
            if (p.tripKm != null && p.tripEnd != null && p.tripEnd > c.lastTripEnd) {
                c = c.copy(lastTripEnd = p.tripEnd)
                // пробег из одометра точнее — тогда поездку не прибавляем
                if (p.odometerKm == null) c = c.copy(tripKm = c.tripKm + p.tripKm)
            }
            if (p.dtc != null) c = c.copy(dtc = p.dtc, dtcAt = p.time)
            if (p.dtc != null) c = c.copy(mil = p.mil)
            if (!p.sensors.isNullOrEmpty()) c = c.copy(sensors = p.sensors, sensorsAt = p.time)
            target = c
            d.copy(cars = d.cars.map { if (it.id == c.id) c else it })
        }
        return target
    }

    fun saveAiReport(carId: String, text: String, at: Long) = edit { d ->
        d.copy(cars = d.cars.map { if (it.id == carId) it.copy(aiReport = text, aiReportAt = at) else it })
    }

    private fun pickCar(d: AppData, vin: String?): Car? {
        if (vin != null) d.cars.firstOrNull { it.vin.equals(vin, ignoreCase = true) }?.let { return it }
        val sel = d.car ?: return null
        // VIN известен и у выбранной машины другой — это не она, ничего не трогаем
        return if (vin != null && sel.vin.isNotBlank()) null else sel
    }

    // ---------- резервная копия ----------

    /** ZIP: data.json и папка receipts с фото. */
    fun exportZip(out: java.io.OutputStream) {
        val d = _data.value
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data.json"))
            zip.write(Json.encode(d).toByteArray())
            zip.closeEntry()
            d.expenses.flatMap { it.photos }.distinct().forEach { name ->
                val f = photoFile(name)?.takeIf { it.exists() } ?: return@forEach
                zip.putNextEntry(java.util.zip.ZipEntry("receipts/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Разбирает копию (ZIP или старый JSON) без применения. */
    fun readBackup(bytes: ByteArray): Backup {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var json: String? = null
            val photos = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val data = zip.readBytes()
                    total += data.size
                    require(total < 512L * 1024 * 1024) { "Слишком большой архив" }
                    when {
                        e.name == "data.json" -> json = data.decodeToString()
                        e.name.startsWith("receipts/") -> {
                            val name = e.name.removePrefix("receipts/")
                            if (photoFile(name) != null) photos[name] = data
                        }
                    }
                }
            }
            return Backup(Json.decode(json ?: error("В архиве нет data.json")), photos)
        }
        return Backup(Json.decode(bytes.decodeToString()), emptyMap())
    }

    /** Полностью заменяет данные содержимым копии. */
    fun restore(b: Backup) {
        receiptsDir.listFiles()?.forEach { it.delete() }
        b.photos.forEach { (name, data) -> photoFile(name)?.writeBytes(data) }
        edit { b.data }
    }

    class Backup(val data: AppData, val photos: Map<String, ByteArray>)

    companion object { private const val TAG = "CarBook" }
}
