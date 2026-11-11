package com.dre.carbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dre.carbook.AppData
import com.dre.carbook.Car
import com.dre.carbook.Category
import com.dre.carbook.Expense
import com.dre.carbook.dateText
import com.dre.carbook.km
import com.dre.carbook.money
import com.dre.carbook.currencySymbol
import com.dre.carbook.moneyInput
import com.dre.carbook.monthTitle
import com.dre.carbook.num2
import com.dre.carbook.parseDecimal
import com.dre.carbook.parseInt
import com.dre.carbook.parseMoney
import com.dre.carbook.repo
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun JournalScreen(data: AppData, car: Car, modifier: Modifier) {
    val repo = LocalContext.current.repo
    var filter by rememberSaveable { mutableStateOf<Category?>(null) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var creating by remember { mutableStateOf(false) }

    val all = data.expensesOf(car.id)
    val list = all.filter { filter == null || it.category == filter }
        .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.mileage ?: 0 })
    val byMonth = list.groupBy { YearMonth.from(it.date) }
    val currentKm = data.currentKm(car)
    val thisMonth = all.filter { YearMonth.from(it.date) == YearMonth.now() }.sumOf { it.amount }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(car.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (car.plate.isNotBlank()) Text(car.plate, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(8.dp))
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text("Пробег", style = MaterialTheme.typography.labelMedium)
                                Text(km(currentKm), style = MaterialTheme.typography.titleMedium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("В этом месяце", style = MaterialTheme.typography.labelMedium)
                                Text(money(thisMonth), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все") })
                    Category.entries.filter { c -> all.any { it.category == c } }.forEach { c ->
                        FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c },
                            label = { Text("${c.emoji} ${c.title}") })
                    }
                }
            }
            if (list.isEmpty()) item {
                Text("Записей пока нет. Нажмите «+», чтобы добавить заправку, ТО или любой другой расход.",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            }
            byMonth.forEach { (ym, items) ->
                item(key = "h$ym") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                        Text(monthTitle(ym), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(money(items.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(items, key = { it.id }) { e ->
                    ExpenseRow(e) { editing = e }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
            item { Spacer(Modifier.size(88.dp)) }
        }
        FloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, "Добавить")
        }
    }

    if (creating || editing != null) {
        ExpenseEditor(
            car = car, initial = editing, currentKm = currentKm,
            lastCategory = all.maxByOrNull { it.date }?.category ?: Category.FUEL,
            onDismiss = { creating = false; editing = null },
            onSave = { repo.saveExpense(it); creating = false; editing = null },
            onDelete = { repo.deleteExpense(it.id); editing = null },
        )
    }
}

@Composable
private fun ExpenseRow(e: Expense, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(e.category.emoji, Modifier.width(40.dp), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text((if (e.photos.isNotEmpty()) "🧾 " else "") + e.note.ifBlank { e.category.title }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            val details = buildList {
                add(dateText(e.date, withYear = false))
                e.mileage?.let { add(km(it)) }
                e.liters?.let { add("${num2(it)} л" + if (!e.fullTank) " (не полный)" else "") }
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(money(e.amount), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditor(
    car: Car,
    initial: Expense?,
    currentKm: Int,
    lastCategory: Category,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
) {
    var category by remember { mutableStateOf(initial?.category ?: lastCategory) }
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    var amount by remember { mutableStateOf(initial?.amount?.let(::moneyInput) ?: "") }
    var mileage by remember { mutableStateOf(initial?.mileage?.toString() ?: "") }
    var liters by remember { mutableStateOf(initial?.liters?.let(::num2) ?: "") }
    var price by remember {
        mutableStateOf(initial?.let { e -> e.liters?.takeIf { it > 0 }?.let { num2(e.amount / 100.0 / it) } } ?: "")
    }
    var full by remember { mutableStateOf(initial?.fullTank ?: true) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var photos by remember { mutableStateOf(initial?.photos ?: emptyList()) }
    val repo = LocalContext.current.repo
    // закрытие без сохранения: удаляем только что добавленные фото
    val cancel = {
        repo.deletePhotos(photos - (initial?.photos ?: emptyList()).toSet())
        onDismiss()
    }
    var tried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amountVal = parseMoney(amount)
    val mileageVal = parseInt(mileage)
    val amountErr = tried && (amountVal == null || amountVal == 0L)
    val mileageErr = mileage.isNotBlank() && mileageVal == null

    // для топлива: из двух известных полей (сумма, литры, цена) считаем третье
    fun onAmount(v: String) {
        amount = v
        if (category != Category.FUEL) return
        val a = parseMoney(v) ?: return
        val p = parseDecimal(price); val l = parseDecimal(liters)
        if (p != null && p > 0) liters = num2(a / 100.0 / p)
        else if (l != null && l > 0) price = num2(a / 100.0 / l)
    }
    fun onLiters(v: String) {
        liters = v
        val l = parseDecimal(v) ?: return
        val p = parseDecimal(price); val a = parseMoney(amount)
        if (p != null && p > 0) amount = num2(l * p)
        else if (a != null && l > 0) price = num2(a / 100.0 / l)
    }
    fun onPrice(v: String) {
        price = v
        val p = parseDecimal(v) ?: return
        val l = parseDecimal(liters); val a = parseMoney(amount)
        if (l != null && l > 0) amount = num2(l * p)
        else if (a != null && p > 0) liters = num2(a / 100.0 / p)
    }

    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (initial == null) "Новый расход" else "Расход") },
                        navigationIcon = { IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                        actions = {
                            if (initial != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить") }
                            TextButton(onClick = {
                                tried = true
                                if (amountVal != null && amountVal > 0 && !mileageErr) {
                                    val isFuel = category == Category.FUEL
                                    onSave(
                                        Expense(
                                            id = initial?.id ?: com.dre.carbook.newId(), carId = car.id, date = date,
                                            category = category, amount = amountVal, mileage = mileageVal,
                                            liters = if (isFuel) parseDecimal(liters)?.takeIf { it > 0 } else null,
                                            fullTank = if (isFuel) full else true, note = note.trim(),
                                            serviceId = initial?.serviceId, photos = photos,
                                        )
                                    )
                                }
                            }) { Text("Сохранить") }
                        },
                    )
                },
            ) { pad ->
                Column(
                    Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Категория", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Category.entries.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c },
                                label = { Text("${c.emoji} ${c.title}") })
                        }
                    }
                    DateField(date, { date = it })
                    Field(amount, ::onAmount, "Сумма",
                        keyboard = KeyboardType.Decimal, suffix = currencySymbol, error = amountErr)
                    if (category == Category.FUEL) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(liters, ::onLiters, "Литры", Modifier.weight(1f),
                                keyboard = KeyboardType.Decimal, suffix = "л")
                            Field(price, ::onPrice, "Цена за литр", Modifier.weight(1f),
                                keyboard = KeyboardType.Decimal, suffix = currencySymbol)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { full = !full }) {
                            Checkbox(full, { full = it })
                            Text("Заправка до полного бака")
                        }
                        Text("Для расчёта расхода топлива указывайте пробег и отмечайте заправки до полного.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Field(mileage, { mileage = it }, "Пробег (сейчас ${km(currentKm)})",
                        keyboard = KeyboardType.Number, suffix = "км", error = mileageErr)
                    if (mileageVal != null && initial == null && mileageVal < currentKm)
                        Text("Пробег меньше текущего — проверьте значение.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Field(note, { note = it }, "Комментарий (что, где)", singleLine = false)
                    ReceiptPhotos(photos) { photos = it }
                }
            }
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = { Text("${initial.category.title}, ${money(initial.amount)}, ${dateText(initial.date)}") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = false
                repo.deletePhotos(photos - initial.photos.toSet())
                onDelete(initial)
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
