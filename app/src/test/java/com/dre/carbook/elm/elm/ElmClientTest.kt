package com.dre.carbook.elm.elm

import com.dre.carbook.elm.transport.ElmTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class ElmClientTest {
    /** Адаптер, ответы которого подаёт сам тест. */
    private class FakeAdapter : ElmTransport {
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun connect() {}
        override fun close() {}
        override suspend fun write(data: ByteArray) { written += String(data).trim() }
        fun answer(text: String) { incoming.trySend("$text\r\r>".toByteArray()) }
    }

    @Test fun lateAnswerDoesNotShiftNextResponses() = runBlocking {
        val a = FakeAdapter()
        val client = ElmClient(a) {}
        try {
            client.send("0142", timeoutMs = 100)
            fail("ожидался таймаут")
        } catch (_: ElmTimeoutException) {
        }
        val atrv = async { client.send("ATRV", timeoutMs = 3000) }
        // ответ на 0142 приходит с опозданием — уже после таймаута
        delay(50)
        a.answer("414236B0")
        withTimeout(3000) { while ("ATRV" !in a.written) delay(5) }
        a.answer("12.6V")
        assertEquals(listOf("12.6V"), atrv.await())

        val next = async { client.send("010D", timeoutMs = 3000) }
        withTimeout(3000) { while ("010D" !in a.written) delay(5) }
        a.answer("410D0A")
        assertEquals(listOf("410D0A"), next.await())
    }

    @Test fun normalExchange() = runBlocking {
        val a = FakeAdapter()
        val client = ElmClient(a) {}
        val r = async { client.send("0105", timeoutMs = 3000) }
        withTimeout(3000) { while ("0105" !in a.written) delay(5) }
        a.answer("410580")
        assertEquals(listOf("410580"), r.await())
    }
}

class StaleReplyTest {
    private fun stale(cmd: String, vararg lines: String) = ElmClient.isStale(cmd, lines.toList())

    @Test fun ownRepliesAccepted() {
        org.junit.Assert.assertFalse(stale("010C", "410C1AF8"))
        org.junit.Assert.assertFalse(stale("010C0D05", "410D0A0C1AF80580"))      // другой порядок PID
        org.junit.Assert.assertFalse(stale("010C0D0504110F", "00E", "0:410C35200D0C", "1:0580046311360F", "2:41AAAAAAAAAAAA"))
        org.junit.Assert.assertFalse(stale("03", "430201330420"))
        org.junit.Assert.assertFalse(stale("07", "4700"))
        org.junit.Assert.assertFalse(stale("0902", "014", "0:490201585441", "1:32313039393031", "2:32333435363738"))
        org.junit.Assert.assertFalse(stale("0142", "NO DATA"))
        org.junit.Assert.assertFalse(stale("0101", "7F0112"))
        org.junit.Assert.assertFalse(stale("ATRV", "12.6V"))
        org.junit.Assert.assertFalse(stale("ATDPN", "A6"))
        org.junit.Assert.assertFalse(stale("ATI", "ELM327 v1.5"))
        org.junit.Assert.assertFalse(stale("ATE0", "OK"))
    }

    @Test fun foreignRepliesRejected() {
        org.junit.Assert.assertTrue(stale("014246012F", "410F41"))
        org.junit.Assert.assertTrue(stale("03", "411127"))
        org.junit.Assert.assertTrue(stale("0142", "12.6V"))
        org.junit.Assert.assertTrue(stale("0105", "OK"))
        org.junit.Assert.assertTrue(stale("ATRV", "414236B0"))
        org.junit.Assert.assertTrue(stale("ATDP", "12.6V"))
    }
}
