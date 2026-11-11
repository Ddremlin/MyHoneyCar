package com.dre.carbook.elm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.dre.carbook.elm.elm.ElmClient
import com.dre.carbook.elm.elm.ElmTimeoutException
import com.dre.carbook.elm.obd.CommandGuard
import com.dre.carbook.elm.obd.ObdParser
import com.dre.carbook.elm.obd.PidPoller
import com.dre.carbook.elm.obd.Pids
import com.dre.carbook.elm.transport.BleTransport
import com.dre.carbook.elm.transport.BluetoothClassicTransport
import com.dre.carbook.elm.transport.ElmTransport
import com.dre.carbook.elm.transport.WifiTransport
import com.dre.carbook.elm.trip.TripColumn
import com.dre.carbook.elm.trip.TripRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/** Что опрашивать: все датчики (экран «Датчики») или только выбранные (панель, запись). */
enum class PollScope { ALL, SELECTED }

enum class LinkType { BLUETOOTH, BLE, WIFI }

/** Точка графика: время (мс) и значение. */
data class Sample(val t: Long, val v: Double)

/** Ключ истории для напряжения адаптера (ATRV) — не PID. */
const val VOLTAGE_KEY = -1

/** Сколько истории хранить для графиков. */
const val HISTORY_MS = 15 * 60_000L

data class RecordingInfo(
    val fileName: String,
    val startedAt: Long,
    val rows: Int,
    val distanceKm: Double,
    val maxSpeed: Double,
)

data class TripFile(val name: String, val size: Long, val modified: Long)

data class DeviceItem(val name: String, val address: String)

data class DtcReport(
    val mil: Boolean?,
    val count: Int?,
    val stored: List<String>,
    val pending: List<String>,
)

data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val status: String = "Не подключено",
    val bluetoothEnabled: Boolean = true,
    val bondedDevices: List<DeviceItem> = emptyList(),
    val bleDevices: List<DeviceItem> = emptyList(),
    val scanning: Boolean = false,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: String = "35000",
    val adapterVersion: String? = null,
    val protocol: String? = null,
    val voltage: String? = null,
    val ecuResponding: Boolean = false,
    val supportedPids: Set<Int> = emptySet(),
    val selectedPids: Set<Int> = Pids.defaultSelection,
    val liveValues: Map<Int, Double?> = emptyMap(),
    val history: Map<Int, List<Sample>> = emptyMap(),
    val polling: Boolean = false,
    /** Ускоренный опрос разрешён пользователем. */
    val fastAllowed: Boolean = true,
    /** Ускоренный опрос реально работает (CAN и не было отката). */
    val fastActive: Boolean = false,
    /** Значений в секунду за последний круг опроса. */
    val pollRate: Double = 0.0,
    val busy: Boolean = false,
    val dtc: DtcReport? = null,
    val vin: String? = null,
    val log: List<String> = emptyList(),
    val recording: RecordingInfo? = null,
    val trips: List<TripFile> = emptyList(),
)

@SuppressLint("MissingPermission")
/**
 * Сеанс связи с адаптером: подключение, опрос, запись поездки.
 * Живёт на уровне приложения (а не экрана), чтобы запись продолжалась в фоне
 * под [TripService].
 */
class ObdSession(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)


    private val prefs = app.getSharedPreferences("elm", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            wifiHost = prefs.getString("wifiHost", null) ?: "192.168.0.10",
            wifiPort = prefs.getString("wifiPort", null) ?: "35000",
            fastAllowed = prefs.getBoolean("fastPolling", true),
            selectedPids = prefs.getStringSet("pids", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: Pids.defaultSelection,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val btAdapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    private var transport: ElmTransport? = null
    private var client: ElmClient? = null
    private var poller: PidPoller? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var recorder: TripRecorder? = null

    val tripsDir: File get() = File(app.filesDir, "trips")

    init {
        refreshTrips()
    }

    val lastAddress: String? get() = prefs.getString("lastAddress", null)

    fun hasBluetoothPermissions(): Boolean {
        val ctx = app
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun refreshBluetooth() {
        val adapter = btAdapter
        if (adapter == null) {
            _state.update { it.copy(bluetoothEnabled = false, bondedDevices = emptyList()) }
            return
        }
        if (!hasBluetoothPermissions()) return
        val enabled = adapter.isEnabled
        val devices = if (enabled) {
            adapter.bondedDevices.orEmpty()
                .map { DeviceItem(it.name ?: it.address, it.address) }
                .sortedWith(compareByDescending<DeviceItem> { looksLikeElm(it.name) }.thenBy { it.name })
        } else {
            emptyList()
        }
        _state.update { it.copy(bluetoothEnabled = enabled, bondedDevices = devices) }
    }

    private fun looksLikeElm(name: String): Boolean {
        val n = name.uppercase()
        return ELM_NAMES.any { it in n }
    }

    fun startBleScan() {
        val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !hasBluetoothPermissions()) {
            toast("Bluetooth выключен или нет разрешений")
            return
        }
        stopBleScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: d.name ?: return
                _state.update { s ->
                    if (s.bleDevices.any { it.address == d.address }) s
                    else s.copy(
                        bleDevices = (s.bleDevices + DeviceItem(name, d.address))
                            .sortedByDescending { looksLikeElm(it.name) }
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.update { it.copy(scanning = false) }
                toast("Ошибка сканирования BLE ($errorCode)")
            }
        }
        scanCallback = cb
        _state.update { it.copy(bleDevices = emptyList(), scanning = true) }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scope.launch {
            delay(12_000)
            if (scanCallback === cb) stopBleScan()
        }
    }

    fun stopBleScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { btAdapter?.bluetoothLeScanner?.stopScan(cb) }
        _state.update { it.copy(scanning = false) }
    }

    fun setWifi(host: String, port: String) = _state.update { it.copy(wifiHost = host, wifiPort = port) }

    fun connect(type: LinkType, address: String? = null) {
        if (_state.value.conn != ConnState.DISCONNECTED) return
        stopBleScan()
        val ctx = app
        val s = _state.value
        val t: ElmTransport = when (type) {
            LinkType.BLUETOOTH, LinkType.BLE -> {
                val adapter = btAdapter ?: return toast("Bluetooth недоступен")
                val device = adapter.getRemoteDevice(address ?: return)
                prefs.edit { putString("lastAddress", address) }
                if (type == LinkType.BLE) BleTransport(ctx, device) else BluetoothClassicTransport(adapter, device)
            }
            LinkType.WIFI -> {
                val port = s.wifiPort.toIntOrNull() ?: return toast("Неверный порт")
                prefs.edit { putString("wifiHost", s.wifiHost); putString("wifiPort", s.wifiPort) }
                WifiTransport(ctx, s.wifiHost.trim(), port)
            }
        }
        transport = t
        connectJob = scope.launch {
            _state.update {
                it.copy(
                    conn = ConnState.CONNECTING, status = "Подключение…", adapterVersion = null,
                    protocol = null, voltage = null, supportedPids = emptySet(), liveValues = emptyMap(), history = emptyMap(),
                    dtc = null, vin = null,
                )
            }
            try {
                t.connect()
                val c = ElmClient(t, ::log)
                client = c
                _state.update { it.copy(status = "Инициализация адаптера…") }
                val info = c.initialize()
                poller = PidPoller(c, _state.value.fastAllowed, ::log)
                _state.update {
                    it.copy(
                        adapterVersion = info.version, protocol = info.protocol, voltage = info.voltage,
                        ecuResponding = info.ecuResponding,
                        status = if (info.ecuResponding) "Определяю поддерживаемые параметры…" else it.status,
                    )
                }
                val supported = if (info.ecuResponding) readSupportedPids(c) else emptySet()
                _state.update {
                    it.copy(
                        conn = ConnState.CONNECTED,
                        supportedPids = supported,
                        status = if (info.ecuResponding) "Подключено" else "Адаптер подключён, ЭБУ не отвечает. Включите зажигание",
                    )
                }
                if (info.ecuResponding) {
                    syncOnConnect(c, supported)
                    startPolling()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                releaseTransport()
                _state.update { it.copy(conn = ConnState.DISCONNECTED, status = "Ошибка: ${e.message}") }
            }
        }
    }

    private suspend fun readSupportedPids(c: ElmClient): Set<Int> {
        val result = mutableSetOf<Int>()
        var base = 0
        while (base <= 0xC0) {
            val chunk = try {
                ObdParser.supportedPids(c.send("01%02X".format(base), 6000), base)
            } catch (_: ElmTimeoutException) {
                emptySet()
            }
            result += chunk
            if (base + 0x20 !in chunk) break
            base += 0x20
        }
        return result
    }

    /**
     * При подключении читаем VIN и одометр (PID A6, если ЭБУ его отдаёт)
     * и передаём туда. Делается до запуска опроса, чтобы не мешать ему.
     */
    private suspend fun syncOnConnect(c: ElmClient, supported: Set<Int>) {
        if (!CarBookSync.isAvailable(app)) return
        val vin = try {
            ObdParser.parseVin(c.send("0902", 5000))?.takeIf { it.length == 17 }
        } catch (_: ElmTimeoutException) {
            null
        }
        if (vin != null) _state.update { it.copy(vin = vin) }
        val odo = readOdometer(c, supported)
        if (odo != null) _state.update { it.copy(liveValues = it.liveValues + (0xA6 to odo)) }
        CarBookSync.send(app, vin = vin, odometerKm = odo)
    }

    private suspend fun readOdometer(c: ElmClient, supported: Set<Int>): Double? {
        if (0xA6 !in supported) return null
        return try {
            ObdParser.mode01(c.send("01A6", 4000), 0xA6)?.let { Pids.byCode[0xA6]?.decode(it) }?.takeIf { it > 0 }
        } catch (_: ElmTimeoutException) {
            null
        }
    }

    private val knownVin: String? get() = _state.value.vin?.takeIf { it.length == 17 }

    /** Повторная попытка связаться с ЭБУ (например, после включения зажигания). */
    fun retryEcu() = launchBusy { c ->
        val ok = c.send("0100", 20_000, log = true).any { it.replace(" ", "").startsWith("4100") }
        if (!ok) {
            toast("ЭБУ не отвечает")
            return@launchBusy
        }
        val protocol = c.refreshProtocol()
        poller = PidPoller(c, _state.value.fastAllowed, ::log)
        val supported = readSupportedPids(c)
        _state.update { it.copy(ecuResponding = true, protocol = protocol, supportedPids = supported, status = "Подключено") }
        if (pollJob?.isActive != true) startPolling()
    }

    fun disconnect() {
        connectJob?.cancel()
        pollJob?.cancel()
        releaseTransport()
        _state.update { it.copy(conn = ConnState.DISCONNECTED, status = "Отключено", polling = false, busy = false) }
    }

    private fun releaseTransport() {
        transport?.close()
        transport = null
        client = null
        poller = null
    }

    private fun connectionLost(e: Exception) {
        pollJob?.cancel()
        releaseTransport()
        _state.update {
            it.copy(conn = ConnState.DISCONNECTED, status = "Связь потеряна: ${e.message}", polling = false, busy = false)
        }
    }

    fun setSelectedPids(pids: Set<Int>) {
        prefs.edit { putStringSet("pids", pids.map { it.toString() }.toSet()) }
        _state.update { it.copy(selectedPids = pids) }
    }

    fun togglePolling() {
        if (pollJob?.isActive == true) {
            pollJob?.cancel()
            return
        }
        startPolling()
    }

    @Volatile
    private var pollScope = PollScope.SELECTED

    /** Экран сообщает, какие параметры ему нужны — опрос подстраивается со следующего круга. */
    fun setPollScope(scope: PollScope) {
        pollScope = scope
    }

    /** Известные приложению параметры, которые поддерживает машина. */
    fun availablePids(s: UiState = _state.value) =
        Pids.all.filter { s.supportedPids.isEmpty() || it.code in s.supportedPids }

    private var lastVoltageAt = 0L

    fun setFastAllowed(allowed: Boolean) {
        prefs.edit { putBoolean("fastPolling", allowed) }
        poller?.setFastAllowed(allowed)
        _state.update { it.copy(fastAllowed = allowed, fastActive = poller?.fast ?: false) }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val c = client ?: return
        pollJob = scope.launch {
            _state.update { it.copy(polling = true) }
            try {
                while (isActive) {
                    val s = _state.value
                    val available = availablePids(s)
                    val pids = if (pollScope == PollScope.ALL) available else available.filter { it.code in s.selectedPids }
                    val cycle = mutableMapOf<Int, Double?>()
                    val started = System.currentTimeMillis()
                    val pp = poller ?: break
                    pp.poll(pids) { p, value ->
                        cycle[p.code] = value
                        _state.update {
                            it.copy(liveValues = it.liveValues + (p.code to value), history = it.history.append(p.code, value))
                        }
                    }
                    val elapsed = System.currentTimeMillis() - started
                    // напряжение — не чаще раза в 2 с, чтобы не тормозить быстрый опрос
                    if (started - lastVoltageAt >= 2000) {
                        lastVoltageAt = started
                        val volt = c.readVoltage()
                        cycle[VOLTAGE_KEY] = volt?.removeSuffix("V")?.toDoubleOrNull()
                        _state.update {
                            it.copy(
                                voltage = volt ?: it.voltage,
                                history = it.history.append(VOLTAGE_KEY, volt?.removeSuffix("V")?.toDoubleOrNull()),
                            )
                        }
                    }
                    _state.update {
                        it.copy(
                            fastActive = pp.fast,
                            pollRate = if (elapsed > 0 && pids.isNotEmpty()) pids.size * 1000.0 / elapsed else 0.0,
                        )
                    }
                    recorder?.let { rec ->
                        withContext(Dispatchers.IO) { rec.writeRow(cycle) }
                        _state.update { it.copy(recording = rec.info()) }
                    }
                    if (pids.isEmpty()) delay(1000)
                }
            } catch (e: IOException) {
                connectionLost(e)
            } finally {
                stopRecording()
                _state.update { it.copy(polling = false) }
            }
        }
    }

    /** Запись идёт вместе с опросом: старт записи запускает опрос, остановка опроса завершает запись. */
    fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        if (client == null) return toast("Нет подключения")
        val s = _state.value
        val pids = availablePids(s).filter { it.code in s.selectedPids }
        val columns = listOf(TripColumn(VOLTAGE_KEY, "Напряжение адаптера, В", 1)) +
            pids.map { TripColumn(it.code, "${it.name}, ${it.unit}", it.decimals.coerceAtLeast(1)) }
        val rec = try {
            TripRecorder(tripsDir, columns, speedKey = 0x0D.takeIf { p -> pids.any { it.code == p } })
        } catch (e: IOException) {
            return toast("Не удалось создать файл: ${e.message}")
        }
        recorder = rec
        _state.update { it.copy(recording = rec.info()) }
        startPolling()
        TripService.start(app)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        rec.close()
        _state.update { it.copy(recording = null) }
        toast("Поездка сохранена: ${rec.file.name} (${rec.rows} строк)")
        refreshTrips()
        val odo = _state.value.liveValues[0xA6]?.takeIf { it > 0 }
        if (rec.distanceKm >= 0.1 || odo != null) {
            CarBookSync.send(
                app, vin = knownVin, odometerKm = odo,
                trip = CarBookSync.Trip(rec.distanceKm, rec.startedAt, System.currentTimeMillis()),
                sensors = sensorSnapshot(),
            )
        }
    }

    private fun TripRecorder.info() = RecordingInfo(file.name, startedAt, rows, distanceKm, maxSpeed)

    fun refreshTrips() {
        val files = tripsDir.listFiles { f -> f.extension == "csv" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { TripFile(it.name, it.length(), it.lastModified()) }
        _state.update { it.copy(trips = files) }
    }

    fun deleteTrip(name: String) {
        if (name == recorder?.file?.name) return toast("Сначала остановите запись")
        File(tripsDir, name).delete()
        refreshTrips()
    }

    /** Копия в общую папку «Загрузки/MyHoneyCar» (Android 10+). */
    fun exportToDownloads(name: String) {
        if (Build.VERSION.SDK_INT < 29) return toast("Доступно с Android 10, используйте «Поделиться»")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/MyHoneyCar")
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(uri)!!.use { out -> File(tripsDir, name).inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            toast(if (ok) "Сохранено в «Загрузки/MyHoneyCar»" else "Не удалось сохранить файл")
        }
    }

    fun readDtcs() = launchBusy { c ->
        // команды и ответы видны в терминале — по ним проще разбираться с нестандартными ЭБУ
        val status = ObdParser.mode01(c.send("0101", 6000, log = true), 0x01)
        val stored = ObdParser.parseDtcs(c.send("03", 10_000, log = true), 0x43, c.isCan)
        val pending = ObdParser.parseDtcs(c.send("07", 10_000, log = true), 0x47, c.isCan)
        log("коды: сохранённые ${stored.ifEmpty { listOf("нет") }.joinToString()}, ожидающие ${pending.ifEmpty { listOf("нет") }.joinToString()}")
        _state.update {
            it.copy(
                dtc = DtcReport(
                    mil = status?.firstOrNull()?.let { a -> a and 0x80 != 0 },
                    count = status?.firstOrNull()?.let { a -> a and 0x7F },
                    stored = stored,
                    pending = pending,
                )
            )
        }
        CarBookSync.send(
            app, vin = knownVin, dtc = stored + pending,
            mil = _state.value.dtc?.mil, sensors = sensorSnapshot(),
        )
    }

    /**
     * Сводка по истории опроса (последние [HISTORY_MS]): min/среднее/max/последнее по каждому параметру.
     * CarBook передаёт её нейросети вместе с кодами ошибок.
     */
    fun sensorSnapshot(s: UiState = _state.value): List<CarBookSync.Sensor> =
        s.history.mapNotNull { (key, samples) ->
            if (samples.isEmpty()) return@mapNotNull null
            val (name, unit) = if (key == VOLTAGE_KEY) "Напряжение бортсети (адаптер)" to "В"
                else Pids.byCode[key]?.let { it.name to it.unit } ?: return@mapNotNull null
            // счётчики и служебные PID анализу не помогают
            if (key == 0x01 || key == 0xA6) return@mapNotNull null
            val v = samples.map { it.v }
            CarBookSync.Sensor(
                code = key, name = name, unit = unit,
                min = v.min(), avg = v.average(), max = v.max(), last = v.last(),
                n = v.size, from = samples.first().t, to = samples.last().t,
            )
        }.sortedBy { if (it.code == VOLTAGE_KEY) Int.MAX_VALUE else it.code }

    /** Ручное сохранение: всё, что известно сейчас (ошибки, датчики) — для анализа нейросетью. */
    fun sendToCarBook() {
        val s = _state.value
        val sensors = sensorSnapshot(s)
        val dtc = s.dtc?.let { it.stored + it.pending }
        if (sensors.isEmpty() && dtc == null) return toast("Нет данных: дождитесь опроса датчиков или прочитайте ошибки")
        val ok = CarBookSync.send(app, vin = knownVin, dtc = dtc, mil = s.dtc?.mil, sensors = sensors)
        toast(if (ok) "Сохранено для анализа: датчиков ${sensors.size}" + (dtc?.let { ", ошибок ${it.size}" } ?: "") +
            ". Анализ — на вкладке «Авто»."
            else "Не удалось сохранить: проверьте, что выбран нужный автомобиль (VIN)")
    }

    fun clearDtcs() = launchBusy { c ->
        val ok = c.send("04", 10_000, log = true).any { it.replace(" ", "").startsWith("44") }
        toast(if (ok) "Ошибки сброшены" else "ЭБУ отклонил сброс (заглушите двигатель, зажигание включено)")
        if (ok) {
            val stored = ObdParser.parseDtcs(c.send("03", 10_000), 0x43, c.isCan)
            val pending = ObdParser.parseDtcs(c.send("07", 10_000), 0x47, c.isCan)
            _state.update { it.copy(dtc = DtcReport(mil = null, count = stored.size, stored = stored, pending = pending)) }
            CarBookSync.send(app, vin = knownVin, dtc = stored + pending)
        }
    }

    fun readVin() = launchBusy { c ->
        val vin = ObdParser.parseVin(c.send("0902", 10_000, log = true))
        _state.update { it.copy(vin = vin ?: "не получен") }
        if (vin?.length == 17) CarBookSync.send(app, vin = vin)
    }

    /**
     * Команда из терминала. Опасные команды ([CommandGuard.risk]) уходят только
     * с [confirmed] = true — экран сначала спрашивает пользователя.
     */
    fun sendRaw(cmd: String, confirmed: Boolean = false) {
        val c = client ?: return toast("Нет подключения")
        val text = CommandGuard.normalize(cmd) ?: return
        if (!confirmed && CommandGuard.risk(text) != null) return toast("Команда требует подтверждения")
        scope.launch {
            try {
                c.send(text, 10_000, log = true)
            } catch (_: ElmTimeoutException) {
            } catch (e: IOException) {
                connectionLost(e)
            }
        }
    }

    fun clearHistory() = _state.update { it.copy(history = emptyMap()) }

    private fun Map<Int, List<Sample>>.append(key: Int, value: Double?): Map<Int, List<Sample>> {
        if (value == null) return this
        val now = System.currentTimeMillis()
        val list = (this[key].orEmpty() + Sample(now, value)).dropWhile { it.t < now - HISTORY_MS }
        return this + (key to list)
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    private fun launchBusy(block: suspend (ElmClient) -> Unit) {
        val c = client ?: return toast("Нет подключения")
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block(c)
            } catch (e: ElmTimeoutException) {
                toast(e.message ?: "Нет ответа")
            } catch (e: IOException) {
                connectionLost(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(500)) }

    private fun toast(text: String) {
        _messages.trySend(text)
    }

    private companion object {
        val ELM_NAMES = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "KW9", "CAN", "SCAN")
    }
}
