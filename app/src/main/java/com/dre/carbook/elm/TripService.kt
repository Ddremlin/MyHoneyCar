package com.dre.carbook.elm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground-служба на время записи поездки: держит процесс живым,
 * не даёт процессору и Wi-Fi уснуть при выключенном экране и показывает уведомление.
 * Сама работа идёт в [ObdSession]; служба завершается, когда запись остановлена.
 */
class TripService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = (application as com.dre.carbook.CarApp).obd
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (session.state.value.recording != null) session.toggleRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            Toast.makeText(this, "Фоновая запись недоступна: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()

        // запись закончилась (кнопкой, отключением или потерей связи) — служба больше не нужна
        scope.launch {
            session.state.map { it.recording != null }.distinctUntilChanged().collect { recording ->
                if (!recording) stopSelf()
            }
        }
        // обновляем пробег и число строк в уведомлении; таймер система ведёт сама
        scope.launch {
            while (true) {
                delay(5_000)
                if (session.state.value.recording == null) break
                notify(buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ElmObd:trip")
            ?.apply { acquire(12 * 60 * 60 * 1000L) } // страховка: не дольше 12 часов
        val wifiMode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(wifiMode, "ElmObd:trip")
            ?.apply { acquire() }
    }

    private fun createChannel() {
        val nm = NotificationManagerCompat.from(this)
        nm.deleteNotificationChannel("trip") // старый «тихий» канал: Pixel прятал его с экрана блокировки
        // обычная важность (видно в строке состояния и на экране блокировки), но без звука и вибрации
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Запись поездки")
                .setDescription("Уведомление во время записи поездки в фоне")
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val rec = session.state.value.recording
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.dre.carbook.MainActivity::class.java).putExtra(com.dre.carbook.Reminders.EXTRA_TAB, com.dre.carbook.OBD_TAB).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.dre.carbook.R.drawable.ic_stat_obd)
            .setContentTitle("Запись поездки")
            .setContentText(
                if (rec == null) "Запуск…"
                else "%.2f км · макс %.0f км/ч · %d строк".format(rec.distanceKm, rec.maxSpeed, rec.rows)
            )
            .setWhen(rec?.startedAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // на экране блокировки — только факт записи, без пробега и скорости
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(com.dre.carbook.R.drawable.ic_stat_obd)
                    .setContentTitle("Запись поездки")
                    .setWhen(rec?.startedAt ?: System.currentTimeMillis())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setSilent(true)
                    .build()
            )
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(n: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "TripService"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.dre.carbook.elm.STOP_TRIP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
            } catch (e: Exception) {
                // например, ForegroundServiceStartNotAllowedException — запись продолжится, пока приложение открыто
                Log.w(TAG, "cannot start service", e)
            }
        }
    }
}
