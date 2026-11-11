package com.dre.carbook

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dre.carbook.ui.CarEditor
import com.dre.carbook.ui.CarScreen
import com.dre.carbook.ui.JournalScreen
import com.dre.carbook.ui.ServiceScreen
import com.dre.carbook.ui.StatsScreen

class MainActivity : ComponentActivity() {
    /** Вкладка, на которую нужно переключиться (из уведомления). */
    private val requestedTab = mutableIntStateOf(-1)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Reminders.check(this, force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedTab.intValue = intent.getIntExtra(Reminders.EXTRA_TAB, -1)
        Reminders.schedule(this)
        // спрашиваем разрешение один раз, когда в регламенте уже что-то есть
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && Reminders.isEnabled(this) && !Reminders.hasPermission(this) &&
            !prefs.getBoolean("askedNotif", false) && repo.data.value.services.isNotEmpty()) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { Root(requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(Reminders.EXTRA_TAB, -1).takeIf { it >= 0 }?.let { requestedTab.intValue = it }
    }

    override fun onStop() {
        super.onStop()
        // пробег и отметки могли измениться — сразу пересчитываем напоминания
        Reminders.check(this)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF2E7D32))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: String, val icon: ImageVector) {
    JOURNAL("Расходы", Icons.Filled.ShoppingCart),
    SERVICE("ТО", Icons.Filled.Build),
    STATS("Итоги", Icons.AutoMirrored.Filled.List),
    CAR("Авто", Icons.Filled.Settings),
    OBD("OBD", Icons.Filled.Info),
}

/** Индекс вкладки диагностики — для уведомления о записи поездки. */
const val OBD_TAB = 4

@Composable
private fun Root(requestedTab: MutableIntState) {
    val repo = LocalContext.current.repo
    val data by repo.data.collectAsStateWithLifecycle()
    val car = data.car

    if (car == null) {
        // первый запуск — добавляем автомобиль
        CarEditor(initial = null, first = true, onDismiss = {}, onSave = { c, defaults -> repo.saveCar(c, defaults) })
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // подвкладка диагностики переживает переход на другие вкладки
    var obdTab by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val obd = (ctx.applicationContext as CarApp).obd
    val obdState by obd.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        obd.messages.collect { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    // экран не гаснет, пока идёт опрос датчиков
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(obdState.polling) {
        view.keepScreenOn = obdState.polling
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(requestedTab.intValue) {
        if (requestedTab.intValue in Tab.entries.indices) { tab = requestedTab.intValue; requestedTab.intValue = -1 }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, null) }, label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        // форматирование сумм читает глобальный знак валюты — при смене пересоздаём экраны
        androidx.compose.runtime.key(data.currency) {
            when (Tab.entries[tab]) {
                Tab.JOURNAL -> JournalScreen(data, car, m)
                Tab.SERVICE -> ServiceScreen(data, car, m)
                Tab.STATS -> StatsScreen(data, car, m)
                Tab.CAR -> CarScreen(data, car, m)
                Tab.OBD -> com.dre.carbook.elm.ui.ObdTab(obd, obdTab, { obdTab = it }, m)
            }
        }
    }
}
