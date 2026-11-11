package com.dre.carbook.elm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.RecordingInfo
import com.dre.carbook.elm.Sample
import com.dre.carbook.elm.UiState
import com.dre.carbook.elm.VOLTAGE_KEY

/** Что показываем в карточке/графике — PID или напряжение адаптера. */
private class Metric(val key: Int, val name: String, val unit: String, val decimals: Int, val value: String)

private val windows = listOf(60_000L to "1 мин", 5 * 60_000L to "5 мин", 15 * 60_000L to "15 мин")

@Composable
fun DashboardScreen(state: UiState, vm: ObdSession, onPickSensors: () -> Unit, modifier: Modifier = Modifier) {
    if (state.conn != ConnState.CONNECTED) {
        NotConnected(modifier)
        return
    }
    var charts by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var windowMs by rememberSaveable { mutableLongStateOf(windows[0].first) }

    val shown = vm.availablePids(state).filter { it.code in state.selectedPids }
    val metrics = listOf(
        Metric(VOLTAGE_KEY, "Напряжение (адаптер)", "В", 1, state.voltage?.removeSuffix("V") ?: "—")
    ) + shown.map { p ->
        Metric(p.code, p.name, p.unit, p.decimals, if (p.code in state.liveValues) p.format(state.liveValues[p.code]) else "—")
    }
    val byKey = metrics.associateBy { it.key }
    val toggleChart = { key: Int -> charts = if (key in charts) charts - key else charts + key }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.togglePolling() }) { Text(if (state.polling) "Пауза" else "Продолжить") }
                if (state.recording == null) {
                    OutlinedButton(onClick = { vm.toggleRecording() }) { Text("● Запись") }
                } else {
                    Button(
                        onClick = { vm.toggleRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("■ Стоп записи") }
                }
                OutlinedButton(onClick = onPickSensors) { Text("Датчики (${shown.size})") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { PollInfo(state) }
        state.recording?.let { rec ->
            item(span = { GridItemSpan(maxLineSpan) }) { RecordingBar(rec) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                windows.forEach { (ms, label) ->
                    FilterChip(windowMs == ms, { windowMs = ms }, { Text(label) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.clearHistory() }) { Text("Очистить") }
            }
        }
        val openCharts = charts.mapNotNull { byKey[it] }
        items(openCharts, key = { "chart_${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { m ->
            ChartCard(m, state.history[m.key].orEmpty(), windowMs) { toggleChart(m.key) }
        }
        if (openCharts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Нажмите на карточку, чтобы открыть график",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(metrics, key = { it.key }) { m ->
            ValueCard(m, state.history[m.key].orEmpty(), windowMs, selected = m.key in charts) { toggleChart(m.key) }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Ничего не выбрано. Отметьте нужные параметры на вкладке «Датчики».")
            }
        }
        if (!state.ecuResponding) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("ЭБУ не отвечает — данные датчиков недоступны.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun PollInfo(state: UiState) {
    if (!state.polling) return
    Text(
        (if (state.fastActive) "Ускоренный опрос" else "Опрос по одному PID") +
            " · %.1f знач./с".format(state.pollRate),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordingBar(rec: RecordingInfo) {
    val sec = (System.currentTimeMillis() - rec.startedAt) / 1000
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "● Запись %d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${rec.rows} строк · %.2f км · макс %.0f км/ч".format(rec.distanceKm, rec.maxSpeed),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(rec.fileName, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ValueCard(m: Metric, history: List<Sample>, windowMs: Long, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(m.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(" ${m.unit}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            Sparkline(history, windowMs, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ChartCard(m: Metric, history: List<Sample>, windowMs: Long, onClose: () -> Unit) {
    val visible = history.filter { it.t >= System.currentTimeMillis() - windowMs }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text("${m.name}, ${m.unit}", style = MaterialTheme.typography.titleSmall)
                    if (visible.isNotEmpty()) {
                        val f = { v: Double -> "%.${m.decimals}f".format(v) }
                        Text(
                            "мин ${f(visible.minOf { it.v })} · ср ${f(visible.map { it.v }.average())} · макс ${f(visible.maxOf { it.v })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Закрыть график") }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Нет данных — нажмите «Старт»", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LineChart(history, windowMs, m.decimals, Modifier.padding(end = 8.dp))
            }
        }
    }
}

@Composable
fun NotConnected(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Сначала подключитесь к адаптеру на вкладке «Подключение»")
    }
}
