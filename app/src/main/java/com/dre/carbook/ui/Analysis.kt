package com.dre.carbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dre.carbook.Ai
import com.dre.carbook.AppData
import com.dre.carbook.Car

/** Анализ данных ELM327 нейросетью: что отправим, кнопка запуска, последний ответ. */
@Composable
fun AiCard(data: AppData, car: Car) {
    val ctx = LocalContext.current
    val running by Ai.running.collectAsState()
    val error by Ai.error.collectAsState()
    var hasKey by remember { mutableStateOf(Ai.apiKey(ctx) != null) }
    var editKey by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val busy = running != null

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Анализ нейросетью", style = MaterialTheme.typography.titleSmall)
            val parts = buildList {
                car.sensorsAt?.let { add("датчики: ${car.sensors.size} (${timeText(it)})") }
                car.dtcAt?.let { add("ошибки: ${if (car.dtc.isEmpty()) "нет" else car.dtc.size} (${timeText(it)})") }
            }
            Text(
                if (parts.isEmpty())
                    "На вкладке «OBD» подключитесь к машине, откройте «Датчики» (лучше на прогретом двигателе) и нажмите " +
                        "«Сохранить для анализа нейросетью» или прочитайте ошибки. Нейросеть Claude разберёт коды ошибок " +
                        "и показания датчиков с учётом модели, пробега и регламента ТО."
                else "Данные OBD — " + parts.joinToString(", ") + ".",
                style = MaterialTheme.typography.bodySmall,
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Нейросеть разбирает данные… это может занять минуту", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Button(
                    onClick = { if (hasKey) Ai.start(ctx, car.id) else editKey = true },
                    enabled = Ai.hasObdData(car),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (car.aiReportAt == null) "Проанализировать" else "Проанализировать заново") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (car.aiReport.isNotBlank() && car.aiReportAt != null) {
                Text("Разбор от ${timeText(car.aiReportAt)}", style = MaterialTheme.typography.labelMedium)
                Text(
                    car.aiReport.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 6,
                )
                OutlinedButton(onClick = { showReport = true }, Modifier.fillMaxWidth()) { Text("Открыть разбор полностью") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showPrompt = true }, enabled = Ai.hasObdData(car)) { Text("Что отправляется") }
                TextButton(onClick = { editKey = true }) { Text(if (hasKey) "Ключ API" else "Указать ключ API") }
            }
        }
    }

    if (editKey) {
        var key by remember { mutableStateOf(Ai.apiKey(ctx).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editKey = false },
            title = { Text("Ключ Claude API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Для анализа нужен ключ API Anthropic (platform.claude.com → API Keys). Запросы оплачиваются " +
                            "по тарифу API с вашего аккаунта. Ключ хранится только на этом телефоне и не попадает в резервную копию.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it.trim() }, singleLine = true,
                        label = { Text("sk-ant-…") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Ai.setApiKey(ctx, key); hasKey = key.isNotBlank(); editKey = false
                    if (hasKey) Ai.error.value = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    if (hasKey) TextButton(onClick = { Ai.setApiKey(ctx, null); hasKey = false; editKey = false }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editKey = false }) { Text("Отмена") }
                }
            },
        )
    }

    if (showPrompt) {
        val prompt = remember(data, car) { Ai.buildPrompt(data, car) }
        TextDialog("Будет отправлено в Claude", prompt, mono = true,
            note = "VIN и госномер не отправляются.") { showPrompt = false }
    }

    if (showReport && car.aiReportAt != null) {
        TextDialog("Разбор от ${timeText(car.aiReportAt)}", car.aiReport) { showReport = false }
    }
}

@Composable
private fun TextDialog(title: String, text: String, mono: Boolean = false, note: String? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer {
                    Text(
                        text,
                        style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            else MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
