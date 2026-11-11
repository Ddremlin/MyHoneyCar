package com.dre.carbook.elm.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TripRecorderTest {
    @Test
    fun writesCsvAndDistance() {
        val dir = Files.createTempDirectory("trips").toFile()
        val rec = TripRecorder(dir, listOf(TripColumn(-1, "Напряжение, В", 1), TripColumn(0x0D, "Скорость, км/ч", 1)), 0x0D)
        rec.writeRow(mapOf(-1 to 12.46, 0x0D to 60.0))
        Thread.sleep(200)
        rec.writeRow(mapOf(-1 to null, 0x0D to 60.0))
        rec.close()

        val lines = rec.file.readLines()
        assertEquals("﻿Время;Секунды;Напряжение, В;Скорость, км/ч", lines[0])
        assertTrue(lines[1], lines[1].endsWith(";12,5;60,0"))
        assertTrue(lines[2], lines[2].endsWith(";;60,0"))
        // 60 км/ч × ~0.2 с ≈ 3.3 м
        assertTrue(rec.distanceKm in 0.003..0.005)
    }
}
