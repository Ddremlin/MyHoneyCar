package com.dre.carbook

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Напоминания о ТО: раз в день (около 10:00) проверяем регламент всех автомобилей
 * и показываем уведомление, если есть работы «скоро» или «просрочено».
 * Без лишнего шума: повторяем, только если список изменился или прошло [REPEAT_DAYS] дня.
 */
object Reminders {
    private const val CHANNEL = "service_due"
    private const val PREFS = "reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SIG = "lastSignature"
    private const val KEY_DAY = "lastNotifiedDay"
    private const val REPEAT_DAYS = 3
    private const val HOUR = 10
    const val EXTRA_TAB = "tab"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).remove(KEY_SIG).apply()
        if (on) { schedule(ctx); check(ctx, force = true) } else {
            cancelAlarm(ctx)
            NotificationManagerCompat.from(ctx).cancelAll()
        }
    }

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Ежедневная неточная будильник-проверка (точные будильники не нужны и требуют разрешения). */
    fun schedule(ctx: Context) {
        if (!isEnabled(ctx)) return
        val now = LocalDateTime.now()
        var first = now.toLocalDate().atTime(HOUR, 0)
        if (!first.isAfter(now)) first = first.plusDays(1)
        val ms = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ctx.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, ms, AlarmManager.INTERVAL_DAY, alarmIntent(ctx))
    }

    private fun cancelAlarm(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))

    data class DueItem(val car: Car, val status: ServiceStatus)

    fun dueItems(data: AppData, today: LocalDate = LocalDate.now()): List<DueItem> =
        data.cars.flatMap { car ->
            val kmNow = data.currentKm(car)
            sortedStatuses(data.servicesOf(car.id), kmNow, today)
                .filter { it.state == DueState.OVERDUE || it.state == DueState.SOON }
                .map { DueItem(car, it) }
        }

    fun describe(s: ServiceStatus): String {
        val parts = buildList {
            s.leftKm?.let { add(if (it < 0) "просрочено на ${km(-it)}" else "через ${km(it)}") }
            s.leftDays?.let { add(if (it < 0) "срок прошёл ${-it} дн. назад" else if (it == 0L) "срок сегодня" else "через $it дн.") }
        }
        return "${s.item.name}: ${parts.joinToString(", ")}"
    }

    /** Проверить и при необходимости показать уведомление. */
    fun check(ctx: Context, force: Boolean = false) {
        if (!isEnabled(ctx) || !hasPermission(ctx)) return
        val items = dueItems(ctx.repo.data.value)
        val nm = NotificationManagerCompat.from(ctx)
        val p = prefs(ctx)
        if (items.isEmpty()) {
            nm.cancel(1)
            p.edit().remove(KEY_SIG).apply()
            return
        }
        val sig = items.joinToString("|") { "${it.status.item.id}:${it.status.state}" }
        val today = LocalDate.now().toEpochDay()
        val lastDay = p.getLong(KEY_DAY, Long.MIN_VALUE / 2)
        if (!force && sig == p.getString(KEY_SIG, null) && today - lastDay < REPEAT_DAYS) return

        ensureChannel(ctx)
        val overdue = items.count { it.status.state == DueState.OVERDUE }
        val title = when {
            overdue > 0 && items.size > overdue -> "ТО: просрочено $overdue, скоро ${items.size - overdue}"
            overdue > 0 -> "ТО просрочено: $overdue"
            else -> "Скоро ТО: ${items.size}"
        }
        val multiCar = items.map { it.car.id }.distinct().size > 1
        val lines = items.map { (if (multiCar) "${it.car.name} — " else "") + describe(it.status) }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle().also { st -> lines.take(7).forEach(st::addLine) }
        if (lines.size > 7) style.setSummaryText("и ещё ${lines.size - 7}")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_service)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            nm.notify(1, n)
            p.edit().putString(KEY_SIG, sig).putLong(KEY_DAY, today).apply()
        } catch (_: SecurityException) { }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания о ТО", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Работы по регламенту, которые скоро нужно выполнить или уже просрочены" })
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> Reminders.check(context)
            // после перезагрузки, обновления приложения или смены времени будильник нужно поставить заново
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.schedule(context); Reminders.check(context)
            }
        }
    }

    companion object { const val ACTION_CHECK = "com.dre.carbook.CHECK_SERVICE" }
}
