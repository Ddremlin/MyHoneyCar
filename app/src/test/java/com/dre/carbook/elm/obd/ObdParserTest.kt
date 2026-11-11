package com.dre.carbook.elm.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {
    @Test
    fun rpm() {
        val data = ObdParser.mode01(listOf("410C1AF8"), 0x0C)!!
        assertEquals(1726.0, Pids.byCode.getValue(0x0C).decode(data)!!, 0.01)
    }

    @Test
    fun supportedPidsMultiEcu() {
        val s = ObdParser.supportedPids(listOf("4100BE3EB811", "410098180001"), 0)
        assertTrue(0x01 in s && 0x0C in s && 0x0D in s && 0x20 in s)
    }

    @Test
    fun dtcCanSingleFrame() {
        assertEquals(listOf("P0133", "P0420"), ObdParser.parseDtcs(listOf("430201330420"), 0x43, true))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs(listOf("4300"), 0x43, true))
    }

    @Test
    fun dtcCanMultiFrame() {
        val lines = listOf("00A", "0:430401330420", "1:C1230171AAAAAA")
        assertEquals(listOf("P0133", "P0420", "U0123", "P0171"), ObdParser.parseDtcs(lines, 0x43, true))
    }

    @Test
    fun dtcKLine() {
        assertEquals(listOf("P0300", "B1234"), ObdParser.parseDtcs(listOf("43030092340000"), 0x43, false))
    }

    @Test
    fun vinCan() {
        val lines = listOf("014", "0:490201314434", "1:47503030523535", "2:42313233343536")
        assertEquals("1D4GP00R55B123456", ObdParser.parseVin(lines))
    }
}

class MultiPidTest {
    private val req = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F).map { Pids.byCode.getValue(it) }

    @Test
    fun singleFrame() {
        val r = ObdParser.parseMulti(listOf("410C1AF80D3C"), req.take(2))!!
        assertEquals(1726.0, req[0].decode(r.getValue(0x0C))!!, 0.01)
        assertEquals(60.0, req[1].decode(r.getValue(0x0D))!!, 0.01)
    }

    @Test
    fun multiFrame() {
        // 41 0C 1AF8 0D 3C 05 5A 04 80 11 33 0F 46 = 14 байт (0x00E)
        val lines = listOf("00E", "0:410C1AF80D3C", "1:055A0480113300", "2:0F46AAAAAAAAAA")
        val r = ObdParser.parseMulti(lines, req)
        // в кадре 1 «113300» → 11=33, затем 00 — не PID: ответ некорректен
        org.junit.Assert.assertNull(r)
        val good = listOf("00E", "0:410C1AF80D3C", "1:055A048011330F", "2:46AAAAAAAAAAAA")
        val r2 = ObdParser.parseMulti(good, req)!!
        assertEquals(6, r2.size)
        assertEquals(50.0, req[2].decode(r2.getValue(0x05))!!, 0.01)
        assertEquals(30.0, req[5].decode(r2.getValue(0x0F))!!, 0.01)
    }

    @Test
    fun twoEcus() {
        // двигатель ответил на всё, КПП — только скорость
        val lines = listOf("410C1AF80D3C", "410D3B")
        val r = ObdParser.parseMulti(lines, req.take(2))!!
        assertEquals(60.0, req[1].decode(r.getValue(0x0D))!!, 0.01)
    }

    @Test
    fun garbageRejected() {
        org.junit.Assert.assertNull(ObdParser.parseMulti(listOf("NO DATA"), req))
        org.junit.Assert.assertNull(ObdParser.parseMulti(listOf("410C1A"), req))
    }
}
