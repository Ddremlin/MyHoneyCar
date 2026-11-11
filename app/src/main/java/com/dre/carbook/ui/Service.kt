package com.dre.carbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dre.carbook.AppData
import com.dre.carbook.Car
import com.dre.carbook.DueState
import com.dre.carbook.ServiceItem
import com.dre.carbook.ServiceStatus
import com.dre.carbook.dateText
import com.dre.carbook.km
import com.dre.carbook.money
import com.dre.carbook.currencySymbol
import com.dre.carbook.parseInt
import com.dre.carbook.parseMoney
import com.dre.carbook.repo
import com.dre.carbook.sortedStatuses
import java.time.LocalDate

@Composable
fun ServiceScreen(data: AppData, car: Car, modifier: Modifier) {
    val repo = LocalContext.current.repo
    val currentKm = data.currentKm(car)
    val statuses = sortedStatuses(data.servicesOf(car.id), currentKm)
    var selected by remember { mutableStateOf<ServiceStatus?>(null) }
    var editing by remember { mutableStateOf<ServiceItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var doneFor by remember { mutableStateOf<ServiceItem?>(null) }
    var odoDialog by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Текущий пробег", style = MaterialTheme.typography.labelMedium)
                            Text(km(currentKm), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (car.tripKm >= 1) Text("в т.ч. ≈${km(car.tripKm.toInt())} по поездкам ELM",
                                style = MaterialTheme.typography.bodySmall)
                            if (car.dtc.isNotEmpty()) Text("Ошибки: ${car.dtc.joinToString(", ")}",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { odoDialog = true }) { Text("Обновить") }
                    }
                }
            }
            val overdue = statuses.count { it.state == DueState.OVERDUE }
            val soon = statuses.count { it.state == DueState.SOON }
            if (overdue + soon > 0) item {
                Text(
                    buildList {
                        if (overdue > 0) add("просрочено: $overdue")
                        if (soon > 0) add("скоро: $soon")
                    }.joinToString(", ").replaceFirstChar { it.uppercase() },
                    Modifier.padding(horizontal = 16.dp),
                    color = stateColor(if (overdue > 0) DueState.OVERDUE else DueState.SOON),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (statuses.isEmpty()) item {
                Text("Регламент пуст. Добавьте работы кнопкой «+».", Modifier.padding(24.dp))
            }
            items(statuses, key = { it.item.id }) { st ->
                ServiceCard(st) { selected = st }
            }
            item { Spacer(Modifier.size(88.dp)) }
        }
        FloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, "Добавить работу")
        }
    }

    selected?.let { st ->
        val history = data.expensesOf(car.id).filter { it.serviceId == st.item.id }.sortedByDescending { it.date }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(st.item.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(intervalText(st.item))
                    Text("Последний раз: " + lastText(st.item))
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text("История", style = MaterialTheme.typography.titleSmall)
                        history.forEach { e ->
                            Text("${dateText(e.date)}${e.mileage?.let { " · " + km(it) } ?: ""} — ${money(e.amount)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { doneFor = st.item; selected = null }) { Text("Выполнено") } },
            dismissButton = {
                TextButton(onClick = { editing = st.item; selected = null }) {
                    Icon(Icons.Filled.Edit, null); Text(" Изменить")
                }
            },
        )
    }

    doneFor?.let { item ->
        DoneDialog(item, currentKm, onDismiss = { doneFor = null }) { date, kmVal, amount, note ->
            repo.markServiceDone(item, date, kmVal, amount, note)
            doneFor = null
        }
    }

    if (creating || editing != null) {
        ServiceEditor(
            initial = editing, carId = car.id, currentKm = currentKm,
            onDismiss = { creating = false; editing = null },
            onSave = { repo.saveService(it); creating = false; editing = null },
            onDelete = { repo.deleteService(it.id); editing = null },
        )
    }

    if (odoDialog) {
        var v by remember { mutableStateOf(currentKm.toString()) }
        val parsed = parseInt(v)
        AlertDialog(
            onDismissRequest = { odoDialog = false },
            title = { Text("Пробег по одометру") },
            text = { Field(v, { v = it }, "Пробег", keyboard = KeyboardType.Number, suffix = "км", error = parsed == null) },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = { repo.setOdometer(car.id, parsed!!); odoDialog = false }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { odoDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun intervalText(s: ServiceItem): String {
    val parts = listOfNotNull(s.intervalKm?.let { km(it) }, s.intervalMonths?.let { monthsText(it) })
    return if (parts.isEmpty()) "Без интервала" else "Каждые " + parts.joinToString(" или ")
}

private fun lastText(s: ServiceItem): String {
    val parts = listOfNotNull(s.lastDate?.let { dateText(it) }, s.lastKm?.let { "на " + km(it) })
    return if (parts.isEmpty()) "не отмечено" else parts.joinToString(", ")
}

fun monthsText(m: Int): String {
    val w = when {
        m % 10 == 1 && m % 100 != 11 -> "месяц"
        m % 10 in 2..4 && m % 100 !in 12..14 -> "месяца"
        else -> "месяцев"
    }
    return "$m $w"
}

private fun daysText(d: Long): String {
    val n = kotlin.math.abs(d)
    val w = when {
        n % 10 == 1L && n % 100 != 11L -> "день"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дня"
        else -> "дней"
    }
    return "$n $w"
}

@Composable
private fun ServiceCard(st: ServiceStatus, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(stateColor(st.state), CircleShape))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(st.item.name, style = MaterialTheme.typography.titleMedium)
                val lines = buildList {
                    st.leftKm?.let { add(if (it >= 0) "через ${km(it)} (на ${km(st.nextKm!!)})" else "просрочено на ${km(-it)}") }
                    st.leftDays?.let { d ->
                        add(if (d >= 0) "до ${dateText(st.nextDate!!)} (${daysText(d)})" else "срок прошёл ${daysText(d)} назад")
                    }
                    if (isEmpty()) add(if (st.item.lastKm == null && st.item.lastDate == null)
                        "отметьте последнее выполнение" else intervalText(st.item))
                }
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (st.state == DueState.OVERDUE) stateColor(st.state) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DoneDialog(item: ServiceItem, currentKm: Int, onDismiss: () -> Unit, onDone: (LocalDate, Int?, Long?, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var kmText by remember { mutableStateOf(currentKm.takeIf { it > 0 }?.toString() ?: "") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val kmVal = parseInt(kmText)
    val amountVal = parseMoney(amount)
    val ok = (kmText.isBlank() || kmVal != null) && (amount.isBlank() || amountVal != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(date, { date = it })
                Field(kmText, { kmText = it }, "Пробег", keyboard = KeyboardType.Number, suffix = "км",
                    error = kmText.isNotBlank() && kmVal == null)
                Field(amount, { amount = it }, "Стоимость (необязательно)", keyboard = KeyboardType.Decimal, suffix = currencySymbol,
                    error = amount.isNotBlank() && amountVal == null)
                Field(note, { note = it }, "Комментарий (сервис, запчасти)")
                Text("Если указана стоимость, она попадёт в расходы.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(date, kmVal, amountVal, note.trim()) }) { Text("Отметить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ServiceEditor(
    initial: ServiceItem?,
    carId: String,
    currentKm: Int,
    onDismiss: () -> Unit,
    onSave: (ServiceItem) -> Unit,
    onDelete: (ServiceItem) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var intKm by remember { mutableStateOf(initial?.intervalKm?.toString() ?: "") }
    var intMonths by remember { mutableStateOf(initial?.intervalMonths?.toString() ?: "") }
    var lastKm by remember { mutableStateOf(initial?.lastKm?.toString() ?: "") }
    var hasLastDate by remember { mutableStateOf(initial?.lastDate != null) }
    var lastDate by remember { mutableStateOf(initial?.lastDate ?: LocalDate.now()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun bad(s: String) = s.isNotBlank() && parseInt(s) == null
    val ok = name.isNotBlank() && !bad(intKm) && !bad(intMonths) && !bad(lastKm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая работа" else "Работа") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "Название")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(intKm, { intKm = it }, "Каждые", Modifier.weight(1f), KeyboardType.Number, "км", bad(intKm))
                    Field(intMonths, { intMonths = it }, "или", Modifier.weight(1f), KeyboardType.Number, "мес", bad(intMonths))
                }
                Text("Последнее выполнение", style = MaterialTheme.typography.labelLarge)
                Field(lastKm, { lastKm = it }, "Пробег (сейчас ${km(currentKm)})", keyboard = KeyboardType.Number,
                    suffix = "км", error = bad(lastKm))
                if (hasLastDate) {
                    DateField(lastDate, { lastDate = it })
                    TextButton(onClick = { hasLastDate = false }) { Text("Убрать дату") }
                } else {
                    TextButton(onClick = { hasLastDate = true }) { Text("Указать дату") }
                }
                if (initial != null) TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить работу", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                onSave(
                    ServiceItem(
                        id = initial?.id ?: com.dre.carbook.newId(), carId = carId, name = name.trim(),
                        intervalKm = parseInt(intKm)?.takeIf { it > 0 }, intervalMonths = parseInt(intMonths)?.takeIf { it > 0 },
                        lastKm = parseInt(lastKm), lastDate = if (hasLastDate) lastDate else null,
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${initial.name}»?") },
            text = { Text("Записи расходов сохранятся.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
