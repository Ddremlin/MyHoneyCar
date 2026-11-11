package com.dre.carbook.elm.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGuardTest {

    @Test
    fun readCommandsAreSafe() {
        listOf("ATI", "ATRV", "ATDP", "ATZ", "ATSH7E0", "0100", "010C", "01 0D", "03", "07", "0902", "1001", "22F190", "19 02 FF")
            .forEach { assertNull(it, CommandGuard.risk(it)) }
    }

    @Test
    fun writingCommandsAreFlagged() {
        listOf("04", "1101", "11 01", "1002", "1003", "14FFFFFF", "2701", "2EF19001", "3101FF00", "3401", "ATPP 0C SV 23", "atbrd 23", "STSBR 115200")
            .forEach { assertNotNull(it, CommandGuard.risk(it)) }
    }

    @Test
    fun normalizeStripsControlCharsAndLimitsLength() {
        assertEquals("0100", CommandGuard.normalize(" 0100\r\n"))
        // перевод строки внутри не превращается во вторую команду «04»
        assertEquals("010004", CommandGuard.normalize("0100\r04"))
        assertEquals("ATZ", CommandGuard.normalize("atz"))
        assertNull(CommandGuard.normalize("\r\n "))
        assertEquals(CommandGuard.MAX_LENGTH, CommandGuard.normalize("0".repeat(500))!!.length)
    }
}
