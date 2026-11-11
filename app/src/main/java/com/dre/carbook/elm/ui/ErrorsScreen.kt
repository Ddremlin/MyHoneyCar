package com.dre.carbook.elm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.UiState
import com.dre.carbook.elm.obd.DtcInfo

@Composable
fun ErrorsScreen(state: UiState, vm: ObdSession, modifier: Modifier = Modifier) {
    if (state.conn != ConnState.CONNECTED) {
        NotConnected(modifier)
        return
    }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.readDtcs() }, enabled = !state.busy) { Text("Прочитать") }
                Button(
                    onClick = { confirmClear = true },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Сбросить") }
                OutlinedButton(onClick = { vm.readVin() }, enabled = !state.busy) { Text("VIN") }
            }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.vin?.let { vin ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(vin, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        overlineContent = { Text("VIN") },
                    )
                }
            }
        }
        val report = state.dtc
        if (report == null) {
            item { Text("Нажмите «Прочитать», чтобы получить коды неисправностей.") }
            return@LazyColumn
        }
        item {
            val milText = when (report.mil) {
                true -> "Check Engine горит"
                false -> "Check Engine не горит"
                null -> "Check Engine: нет данных"
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = if (report.mil == true) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(milText, style = MaterialTheme.typography.titleMedium)
                    report.count?.let { Text("Подтверждённых кодов по данным ЭБУ: $it") }
                }
            }
        }
        item { Text("Сохранённые (режим 03)", style = MaterialTheme.typography.titleSmall) }
        if (report.stored.isEmpty()) item { Text("Нет") }
        items(report.stored) { DtcRow(it) }
        item { Text("Ожидающие (режим 07)", style = MaterialTheme.typography.titleSmall) }
        if (report.pending.isEmpty()) item { Text("Нет") }
        items(report.pending) { DtcRow(it) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    "Будут стёрты коды неисправностей, стоп-кадры и результаты готовности систем (readiness). " +
                        "Зажигание должно быть включено, двигатель заглушен."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearDtcs()
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DtcRow(code: String) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            supportingContent = { Text(DtcInfo.describe(code)) },
        )
    }
}
