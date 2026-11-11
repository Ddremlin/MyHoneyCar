package com.dre.carbook.elm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.dre.carbook.elm.CarBookSync
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.PollScope
import com.dre.carbook.elm.UiState

/** Все параметры, которые отдаёт машина, с живыми значениями и выбором для панели. */
@Composable
fun SensorsScreen(state: UiState, vm: ObdSession, onOpenDashboard: () -> Unit, modifier: Modifier = Modifier) {
    if (state.conn != ConnState.CONNECTED) {
        NotConnected(modifier)
        return
    }
    // пока список на экране — опрашиваем все датчики; в фоне и на других вкладках — только выбранные
    LifecycleStartEffect(Unit) {
        vm.setPollScope(PollScope.ALL)
        onStopOrDispose { vm.setPollScope(PollScope.SELECTED) }
    }
    val available = vm.availablePids(state)
    val selectedCount = available.count { it.code in state.selectedPids }
    val ctx = LocalContext.current
    val canSendToCarBook = remember { CarBookSync.isAvailable(ctx) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Параметров: ${available.size} · выбрано: $selectedCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            PollInfo(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenDashboard, enabled = selectedCount > 0) { Text("На панель ($selectedCount)") }
                OutlinedButton(onClick = { vm.togglePolling() }) { Text(if (state.polling) "Пауза" else "Продолжить") }
                TextButton(onClick = { vm.setSelectedPids(emptySet()) }, enabled = selectedCount > 0) { Text("Снять все") }
            }
            if (canSendToCarBook) {
                OutlinedButton(onClick = { vm.sendToCarBook() }, enabled = state.history.isNotEmpty() || state.dtc != null) {
                    Text("Сохранить для анализа нейросетью")
                }
            }
        }
        HorizontalDivider()
        if (!state.ecuResponding) {
            Text(
                "ЭБУ не отвечает — список датчиков недоступен. Включите зажигание и нажмите «Повторить связь с ЭБУ».",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(available, key = { it.code }) { p ->
                val checked = p.code in state.selectedPids
                val toggle = {
                    vm.setSelectedPids(if (checked) state.selectedPids - p.code else state.selectedPids + p.code)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, onCheckedChange = { toggle() })
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "PID %02X".format(p.code),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (p.code in state.liveValues) "${p.format(state.liveValues[p.code])} ${p.unit}".trim() else "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(start = 52.dp))
            }
        }
    }
}
