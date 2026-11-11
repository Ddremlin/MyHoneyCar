package com.dre.carbook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dre.carbook.AppData
import com.dre.carbook.Car
import com.dre.carbook.Category
import com.dre.carbook.Expense
import com.dre.carbook.fuelStats
import com.dre.carbook.km
import com.dre.carbook.money
import com.dre.carbook.currencySymbol
import com.dre.carbook.monthShort
import com.dre.carbook.monthTitle
import com.dre.carbook.monthlyTotals
import com.dre.carbook.num1
import com.dre.carbook.num2
import com.dre.carbook.summary
import java.time.YearMonth

private enum class Period(val title: String) { MONTH("Месяц"), YEAR("Год"), ALL("Всё время") }

@Composable
fun StatsScreen(data: AppData, car: Car, modifier: Modifier) {
    var period by rememberSaveable { mutableStateOf(Period.YEAR) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var year by rememberSaveable { mutableIntStateOf(YearMonth.now().year) }
    val ym = YearMonth.parse(month)

    val all = data.expensesOf(car.id)
    val list: List<Expense> = when (period) {
        Period.MONTH -> all.filter { YearMonth.from(it.date) == ym }
        Period.YEAR -> all.filter { it.date.year == year }
        Period.ALL -> all
    }
    val sum = summary(list)
    val fuel = fuelStats(list)
    val fuelAll = fuelStats(all)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = period == p, onClick = { period = p },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.title) }
            }
        }
        if (period != Period.ALL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (period == Period.MONTH) month = ym.minusMonths(1).toString() else year-- }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад")
                }
                Text(if (period == Period.MONTH) monthTitle(ym) else "$year год", Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (period == Period.MONTH) month = ym.plusMonths(1).toString() else year++ }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вперёд")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Потрачено", style = MaterialTheme.typography.labelMedium)
                Text(money(sum.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Записей: ${sum.count}", style = MaterialTheme.typography.bodySmall)
                sum.distance?.let { Kv("Пройдено (по записям)", km(it)) }
                sum.perKm?.let { Kv("Стоимость 1 км", num2(it) + " $currencySymbol") }
                if (period == Period.YEAR && sum.total > 0) {
                    val months = if (year == YearMonth.now().year) YearMonth.now().monthValue else 12
                    Kv("В среднем за месяц", money(sum.total / months))
                }
                if (period == Period.ALL && list.isNotEmpty()) {
                    val first = YearMonth.from(list.minOf { it.date })
                    val months = (YearMonth.now().year - first.year) * 12 + YearMonth.now().monthValue - first.monthValue + 1
                    Kv("В среднем за месяц", money(sum.total / months.coerceAtLeast(1)))
                }
            }
        }

        if (period == Period.YEAR) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("По месяцам", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.size(8.dp))
                    MonthBars(monthlyTotals(all, year))
                }
            }
        }

        if (sum.byCategory.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("По категориям", style = MaterialTheme.typography.titleSmall)
                    val max = sum.byCategory.first().second
                    sum.byCategory.forEach { (c, v) -> CategoryBar(c, v, max, sum.total) }
                }
            }
        }

        if (fuel.totalLiters > 0 || fuelAll.avgPer100 != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Топливо", style = MaterialTheme.typography.titleSmall)
                    if (fuel.totalLiters > 0) Kv("Заправлено", num1(fuel.totalLiters) + " л")
                    fuel.avgPricePerLiter?.let { Kv("Средняя цена литра", num2(it) + " $currencySymbol") }
                    fuel.avgPer100?.let { Kv("Средний расход за период", num1(it) + " л/100 км") }
                    fuelAll.avgPer100?.let { Kv("Средний расход за всё время", num1(it) + " л/100 км") }
                    fuelAll.lastPer100?.let { Kv("Последний замер", num1(it) + " л/100 км") }
                    if (fuelAll.avgPer100 == null)
                        Text("Расход появится после двух заправок до полного бака с указанным пробегом.",
                            style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (list.isEmpty()) Text("За этот период расходов нет.", Modifier.padding(8.dp))
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CategoryBar(c: Category, value: Long, max: Long, total: Long) {
    val color = chartColors[c.ordinal % chartColors.size]
    Column {
        Row {
            Text("${c.emoji} ${c.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${money(value)}  ${(value * 100 / total.coerceAtLeast(1))}%", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.size(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(value.toFloat() / max.coerceAtLeast(1)).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun MonthBars(values: LongArray) {
    val max = values.max().coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / 12
            val w = slot * 0.6f
            values.forEachIndexed { i, v ->
                val h = if (v == 0L) 2.dp.toPx() else (size.height * v / max).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (v == 0L) empty else barColor,
                    topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                    size = Size(w, h), cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (m in 1..12) Text(monthShort(m), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall)
        }
        val best = values.indices.maxByOrNull { values[it] }
        if (best != null && values[best] > 0)
            Text("Больше всего: ${monthShort(best + 1)} — ${money(values[best])}",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}
