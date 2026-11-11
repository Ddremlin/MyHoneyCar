package com.dre.carbook.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import androidx.compose.material3.Switch
import com.dre.carbook.AppData
import com.dre.carbook.Reminders
import com.dre.carbook.SOON_DAYS
import com.dre.carbook.SOON_KM
import com.dre.carbook.Car
import com.dre.carbook.Catalog
import com.dre.carbook.km
import com.dre.carbook.parseInt
import com.dre.carbook.repo
import com.dre.carbook.toCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun CarScreen(data: AppData, car: Car, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Car?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<com.dre.carbook.Repo.Backup?>(null) }
    var remindersOn by remember { mutableStateOf(Reminders.isEnabled(ctx)) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { Reminders.setEnabled(ctx, true); remindersOn = true }
        else toast("Без разрешения на уведомления напоминания не придут. Его можно дать в настройках приложения.")
    }

    fun writeTo(uri: android.net.Uri?, write: (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use(write)
                }
            }
            toast(if (r.isSuccess) "Файл сохранён" else "Ошибка: ${r.exceptionOrNull()?.message}")
        }
    }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        writeTo(uri) { repo.exportZip(it) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = toCsv(car, data.expensesOf(car.id))
        writeTo(uri) { it.write(csv.toByteArray()) }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    repo.readBackup(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                }
            }
            r.onSuccess { pendingImport = it }.onFailure { toast("Не удалось прочитать копию: ${it.message}") }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(car.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (car.generation.isNotBlank()) Text("Поколение: ${car.generation}")
                if (car.plate.isNotBlank()) Text("Госномер: ${car.plate}")
                car.year?.let { Text("Год выпуска: $it") }
                if (car.vin.isNotBlank()) Text("VIN: ${car.vin}")
                car.tankLiters?.let { Text("Объём бака: $it л") }
                Text("Пробег: ${km(data.currentKm(car))}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { editing = car }) { Text("Изменить") }
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Автомобили", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                data.cars.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { repo.selectCar(c.id) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = c.id == car.id, onClick = { repo.selectCar(c.id) })
                        Column {
                            Text(c.name)
                            if (c.plate.isNotBlank()) Text(c.plate, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { creating = true }, Modifier.padding(horizontal = 8.dp)) { Text("+ Добавить автомобиль") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Напоминания о ТО", style = MaterialTheme.typography.titleSmall)
                        Text("Уведомление около 10:00, когда работа подходит (за $SOON_KM км или $SOON_DAYS дней) или просрочена",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = remindersOn, onCheckedChange = { on ->
                        if (on && !Reminders.hasPermission(ctx) && Build.VERSION.SDK_INT >= 33) {
                            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Reminders.setEnabled(ctx, on); remindersOn = on
                        }
                    })
                }
                if (remindersOn && !Reminders.hasPermission(ctx))
                    Text("Уведомления запрещены в настройках системы.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
            }
        }

        CurrencyCard(data.currency) { repo.setCurrency(it) }

        ObdCard(car)

        AiCard(data, car)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Данные", style = MaterialTheme.typography.titleSmall)
                Text("Всё хранится только на телефоне. Делайте резервную копию, чтобы не потерять записи при смене телефона.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { exportZip.launch("carbook-${LocalDate.now()}.zip") }, Modifier.fillMaxWidth()) {
                    Text("Сохранить резервную копию (с фото)")
                }
                OutlinedButton(onClick = { importJson.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")) },
                    Modifier.fillMaxWidth()) { Text("Восстановить из копии") }
                HorizontalDivider()
                OutlinedButton(onClick = { exportCsv.launch("${car.name}-расходы-${LocalDate.now()}.csv") }, Modifier.fillMaxWidth()) {
                    Text("Выгрузить расходы в CSV (Excel)")
                }
            }
        }
    }

    if (creating || editing != null) {
        CarEditor(
            initial = editing, first = false,
            onDismiss = { creating = false; editing = null },
            onSave = { c, defaults -> repo.saveCar(c, defaults); creating = false; editing = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${car.name}»?") },
            text = { Text("Будут удалены все расходы и регламент ТО этого автомобиля. Отменить нельзя.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; repo.deleteCar(car.id) }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Восстановить данные?") },
            text = {
                Text("Текущие данные будут заменены содержимым резервной копии: автомобилей — ${backup.data.cars.size}, " +
                    "записей — ${backup.data.expenses.size}, фото — ${backup.photos.size}.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    runCatching { repo.restore(backup) }
                        .onSuccess { toast("Данные восстановлены") }
                        .onFailure { toast("Ошибка: ${it.message}") }
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Отмена") } },
        )
    }
}

/** Форма автомобиля. При first = true показывается как экран первого запуска. */
@Composable
fun CarEditor(initial: Car?, first: Boolean, onDismiss: () -> Unit, onSave: (Car, Boolean) -> Unit) {
    var brand by remember { mutableStateOf(initial?.brand ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var generation by remember { mutableStateOf(initial?.generation ?: "") }
    var plate by remember { mutableStateOf(initial?.plate ?: "") }
    var year by remember { mutableStateOf(initial?.year?.toString() ?: "") }
    var vin by remember { mutableStateOf(initial?.vin ?: "") }
    // показываем пробег с учётом поездок из ELM OBD
    val shownOdo = initial?.let { it.odometer + it.tripKm.toInt() }
    var odo by remember { mutableStateOf(shownOdo?.takeIf { it > 0 }?.toString() ?: "") }
    var tank by remember { mutableStateOf(initial?.tankLiters?.toString() ?: "") }
    var defaults by remember { mutableStateOf(true) }

    fun bad(s: String) = s.isNotBlank() && parseInt(s) == null
    val ok = brand.isNotBlank() && !bad(year) && !bad(odo) && !bad(tank)

    val save = {
        onSave(
            (initial ?: Car(brand = "")).let { base ->
                val newOdo = parseInt(odo) ?: 0
                val odoChanged = newOdo != (shownOdo ?: 0)
                base.copy(
                    brand = brand.trim(), model = model.trim(), generation = generation.trim(), plate = plate.trim().uppercase(),
                    year = parseInt(year), vin = vin.trim().uppercase(),
                    odometer = if (odoChanged) newOdo else base.odometer,
                    tripKm = if (odoChanged) 0.0 else base.tripKm,
                    tankLiters = parseInt(tank)?.takeIf { it > 0 },
                )
            },
            initial == null && defaults,
        )
    }

    val form = @Composable {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestField(brand, {
                // сменили марку — модель и поколение от старой уже не подходят
                if (!it.equals(brand, ignoreCase = true) && Catalog.brand(it) != null && Catalog.model(it, model) == null) {
                    model = ""; generation = ""
                }
                brand = it
            }, "Марка *", { Catalog.suggestBrands(it) }, placeholder = "Toyota, Лада…")
            SuggestField(model, {
                if (Catalog.model(brand, it) != null && !it.equals(model, ignoreCase = true)) generation = ""
                model = it
            }, "Модель", { Catalog.suggestModels(brand, it) })
            SuggestField(generation, {
                generation = it
                // год не введён — подставим первый год выпуска поколения
                if (year.isBlank()) Regex("\\((\\d{4})").find(it)?.let { m -> year = m.groupValues[1] }
            }, "Поколение / кузов", { Catalog.suggestGenerations(brand, model, it) })
            Field(plate, { plate = it }, "Госномер")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(year, { year = it }, "Год", Modifier.weight(1f), KeyboardType.Number, error = bad(year))
                Field(tank, { tank = it }, "Бак", Modifier.weight(1f), KeyboardType.Number, "л", bad(tank))
            }
            Field(odo, { odo = it }, "Текущий пробег", keyboard = KeyboardType.Number, suffix = "км", error = bad(odo))
            Field(vin, { vin = it }, "VIN")
            if (initial == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { defaults = !defaults }) {
                    Checkbox(defaults, { defaults = it })
                    Text("Добавить типовой регламент ТО (масло, фильтры, жидкости, страховка, автокаско)")
                }
            }
        }
    }

    if (first) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding().imePadding().padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MyHoneyCar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Учёт расходов, заправок и обслуживания. Для начала добавьте автомобиль.")
                    Box(Modifier.weight(1f, fill = false)) { form() }
                    Button(onClick = save, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Начать") }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (initial == null) "Новый автомобиль" else "Автомобиль") },
            text = { form() },
            confirmButton = { TextButton(enabled = ok, onClick = save) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
    }
}

/** Выбор валюты записей. */
@Composable
private fun CurrencyCard(current: com.dre.carbook.Currency, onPick: (com.dre.carbook.Currency) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = true }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Валюта", style = MaterialTheme.typography.titleSmall)
                Text("${current.title} (${current.symbol})", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { open = true }) { Text("Изменить") }
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Валюта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Меняется только обозначение: суммы не пересчитываются по курсу.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                com.dre.carbook.Currency.entries.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c); open = false },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = c == current, onClick = { onPick(c); open = false })
                        Text("${c.symbol}  ${c.title}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
    )
}

/** Данные с адаптера ELM327 (вкладка «OBD»): что и когда пришло. */
@Composable
private fun ObdCard(car: Car) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Данные OBD", style = MaterialTheme.typography.titleSmall)
            when {
                car.obdSyncAt == null -> Text(
                    "Подключитесь к адаптеру ELM327 на вкладке «OBD» — пробег (если ЭБУ отдаёт одометр), VIN, км записанных поездок, " +
                        "коды ошибок и показания датчиков придут сюда автоматически.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("Последний обмен: ${timeText(car.obdSyncAt)}", style = MaterialTheme.typography.bodyMedium)
                    car.obdOdoAt?.let { Text("Пробег из одометра ЭБУ: ${timeText(it)}", style = MaterialTheme.typography.bodySmall) }
                    if (car.tripKm >= 1) Text("Добавлено по поездкам: ≈${km(car.tripKm.toInt())} (оценка по скорости)",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            car.dtcAt?.let { at ->
                if (car.dtc.isEmpty()) Text("Ошибок нет (${timeText(at)})", color = stateColor(com.dre.carbook.DueState.OK),
                    style = MaterialTheme.typography.bodyMedium)
                else Text("Ошибки двигателя: ${car.dtc.joinToString(", ")} (${timeText(at)})",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun timeText(ms: Long): String {
    val dt = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    return com.dre.carbook.dateText(dt.toLocalDate()) + " " + "%02d:%02d".format(dt.hour, dt.minute)
}
