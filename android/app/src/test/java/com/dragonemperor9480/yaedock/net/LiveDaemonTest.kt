package com.dragonemperor9480.yaedock.net

import com.dragonemperor9480.yaedock.protocol.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a real daemon. Skipped unless YAEDOCK_TEST_HOST, _PORT, _FP,
 * _DEVICE and _TOKEN describe a phone already paired with it.
 */
class LiveDaemonTest {
    private fun env(name: String): String? = System.getenv("YAEDOCK_TEST_$name")

    @Test
    fun staysConnectedAndReceivesStats() = runBlocking {
        val host = env("HOST")
        assumeTrue("no live daemon configured", host != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connection = DaemonConnection(
            Target(host!!, env("PORT")!!.toInt(), env("FP")!!),
            scope,
            hello = { Hello(deviceId = env("DEVICE")!!, deviceName = "LiveDaemonTest", token = env("TOKEN")) },
            onPaired = { _, _ -> },
        )
        try {
            connection.start()
            withTimeout(10_000) {
                connection.status.first { it is DaemonConnection.Status.Connected }
                connection.stats.filterNotNull().first()
            }
            // Longer than the daemon's 30 s silence limit, so keepalive pings must work.
            delay(35_000)
            val status = connection.status.value
            assertTrue("status after 35 s: $status", status is DaemonConnection.Status.Connected)
            val stats = connection.stats.value!!
            assertTrue("stats are stale", System.currentTimeMillis() - stats.ts < 5_000)
        } finally {
            connection.stop()
            scope.cancel()
        }
    }
}
