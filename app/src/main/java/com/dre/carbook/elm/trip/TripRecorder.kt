package com.dre.carbook.elm.trip

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Колонка CSV: ключ значения (PID или VOLTAGE_KEY) и заголовок с единицей. */
data class TripColumn(val key: Int, val title: String, val decimals: Int)

/**
 * Пишет поездку в CSV: «;» как разделитель, десятичная запятая и BOM —
 * чтобы файл сразу открывался в русском Excel / LibreOffice.
 */
class TripRecorder(dir: File, private val columns: List<TripColumn>, private val speedKey: Int?) {
    val startedAt = System.currentTimeMillis()
    val file: File = File(dir.apply { mkdirs() }, "trip_${FILE_TIME.format(Date(startedAt))}.csv")
    private val writer: BufferedWriter = file.bufferedWriter()

    var rows = 0
        private set
    /** Пробег по интегралу скорости, км (если скорость пишется). */
    var distanceKm = 0.0
        private set
    var maxSpeed = 0.0
        private set
    private var lastSpeedAt: Long? = null
    private var lastSpeed = 0.0

    init {
        writer.write("﻿")
        writer.write((listOf("Время", "Секунды") + columns.map { it.title }).joinToString(";"))
        writer.newLine()
    }

    fun writeRow(values: Map<Int, Double?>) {
        val now = System.currentTimeMillis()
        val cells = listOf(ROW_TIME.format(Date(now)), "%.1f".format(Locale.ROOT, (now - startedAt) / 1000.0)) +
            columns.map { c -> values[c.key]?.let { "%.${c.decimals}f".format(Locale.ROOT, it) }.orEmpty() }
        writer.write(cells.joinToString(";") { it.replace('.', ',') })
        writer.newLine()
        rows++
        if (rows % 10 == 0) writer.flush()

        val speed = speedKey?.let { values[it] } ?: return
        lastSpeedAt?.let { distanceKm += (lastSpeed + speed) / 2 * (now - it) / 3_600_000.0 }
        lastSpeedAt = now
        lastSpeed = speed
        if (speed > maxSpeed) maxSpeed = speed
    }

    fun close() {
        runCatching { writer.close() }
    }

    private companion object {
        val FILE_TIME = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
        val ROW_TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    }
}
