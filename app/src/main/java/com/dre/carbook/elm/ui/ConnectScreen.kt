package com.dre.carbook.elm.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.DeviceItem
import com.dre.carbook.elm.LinkType
import com.dre.carbook.elm.ObdSession
import com.dre.carbook.elm.UiState

@Composable
fun ConnectScreen(state: UiState, vm: ObdSession, modifier: Modifier = Modifier) {
    var type by rememberSaveable { mutableStateOf(LinkType.BLUETOOTH) }
    val ctx = LocalContext.current
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshBluetooth()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state, vm) }

        if (state.conn != ConnState.DISCONNECTED) return@LazyColumn

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == LinkType.BLUETOOTH, { type = LinkType.BLUETOOTH }, { Text("Bluetooth") })
                FilterChip(type == LinkType.BLE, { type = LinkType.BLE }, { Text("Bluetooth LE") })
                FilterChip(type == LinkType.WIFI, { type = LinkType.WIFI }, { Text("Wi-Fi") })
            }
        }

        if (type != LinkType.WIFI && !vm.hasBluetoothPermissions()) {
            item { Text("Нет разрешения на Bluetooth. Выдайте его в настройках приложения.") }
            return@LazyColumn
        }
        if (type != LinkType.WIFI && !state.bluetoothEnabled) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bluetooth выключен")
                    Button(onClick = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("Включить Bluetooth")
                    }
                }
            }
            return@LazyColumn
        }

        when (type) {
            LinkType.BLUETOOTH -> {
                item {
                    Text(
                        "Сначала выполните сопряжение с адаптером в настройках Android " +
                            "(PIN обычно 1234 или 0000), затем выберите его из списка.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.refreshBluetooth() }) { Text("Обновить") }
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Text("Настройки Bluetooth")
                        }
                    }
                }
                if (state.bondedDevices.isEmpty()) item { Text("Нет сопряжённых устройств") }
                items(state.bondedDevices, key = { it.address }) { d ->
                    DeviceRow(d, d.address == vm.lastAddress) { vm.connect(LinkType.BLUETOOTH, d.address) }
                }
            }

            LinkType.BLE -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.scanning) {
                            OutlinedButton(onClick = { vm.stopBleScan() }) { Text("Остановить") }
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(onClick = { vm.startBleScan() }) { Text("Искать устройства") }
                        }
                    }
                }
                items(state.bleDevices, key = { it.address }) { d ->
                    DeviceRow(d, d.address == vm.lastAddress) { vm.connect(LinkType.BLE, d.address) }
                }
            }

            LinkType.WIFI -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Подключите телефон к Wi-Fi сети адаптера (обычно «WiFi_OBDII» или «V-LINK»).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = state.wifiHost,
                            onValueChange = { vm.setWifi(it, state.wifiPort) },
                            label = { Text("IP-адрес") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.wifiPort,
                            onValueChange = { vm.setWifi(state.wifiHost, it.filter(Char::isDigit)) },
                            label = { Text("Порт") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { vm.connect(LinkType.WIFI) }) { Text("Подключиться") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState, vm: ObdSession) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.conn == ConnState.CONNECTING || state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(state.status, style = MaterialTheme.typography.titleMedium)
            }
            state.adapterVersion?.let { Info("Адаптер", it) }
            state.protocol?.takeIf { it.isNotBlank() }?.let { Info("Протокол", it) }
            state.voltage?.let { Info("Напряжение", it) }
            if (state.conn == ConnState.CONNECTED) {
                Info("Поддерживаемых PID", state.supportedPids.size.toString())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ускоренный опрос", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            !state.fastAllowed -> "выключен — по одному PID"
                            state.conn != ConnState.CONNECTED -> "до 6 PID за запрос (только CAN)"
                            state.fastActive -> "работает: до 6 PID за запрос"
                            else -> "недоступен (не CAN или откат) — по одному PID"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.fastAllowed, onCheckedChange = { vm.setFastAllowed(it) })
            }
            if (state.conn != ConnState.DISCONNECTED) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Отключиться") }
                    if (state.conn == ConnState.CONNECTED && !state.ecuResponding) {
                        Button(onClick = { vm.retryEcu() }, enabled = !state.busy) { Text("Повторить связь с ЭБУ") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeviceRow(d: DeviceItem, last: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(d.name) },
            supportingContent = { Text(if (last) "${d.address} · последний" else d.address) },
        )
    }
}
