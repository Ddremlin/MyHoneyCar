package com.dre.carbook.elm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dre.carbook.elm.ConnState
import com.dre.carbook.elm.ObdSession

private val tabs = listOf("Подключение", "Датчики", "Панель", "Ошибки", "Поездки", "Терминал")

/** Вкладка диагностики ELM327 (бывшее приложение ELM OBD). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ObdTab(vm: ObdSession, tab: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshBluetooth()
    }
    // разрешения Bluetooth спрашиваем, только когда пользователь впервые открыл диагностику
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // уведомление о фоновой записи поездки
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        vm.refreshBluetooth()
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Диагностика ELM327", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val (label, color) = when (state.conn) {
                ConnState.CONNECTED -> "● онлайн" to Color(0xFF2E7D32)
                ConnState.CONNECTING -> "● подключение" to Color(0xFFF9A825)
                ConnState.DISCONNECTED -> "● офлайн" to MaterialTheme.colorScheme.outline
            }
            Text(label, color = color)
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(title) })
            }
        }
        val m = Modifier.weight(1f)
        when (tab) {
            0 -> ConnectScreen(state, vm, m)
            1 -> SensorsScreen(state, vm, onOpenDashboard = { onTab(2) }, modifier = m)
            2 -> DashboardScreen(state, vm, onPickSensors = { onTab(1) }, modifier = m)
            3 -> ErrorsScreen(state, vm, m)
            4 -> TripsScreen(state, vm, m)
            5 -> TerminalScreen(state, vm, m)
        }
    }
}
