package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.transport.fake.ElmEmulator
import com.bruni.carscan.core.transport.fake.ElmEmulatorConfig
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one test this whole module exists to pass.
 *
 * An ELM327 delimits responses with a `>` prompt and nothing else. Write a second command
 * before the first answer has been read and the stream does not interleave — it *shifts*.
 * Every response from that moment on belongs to the request before it, forever, and the
 * failure never surfaces as an error. It surfaces as a coolant temperature displayed on
 * the tachometer, which the user reports as "the app is wrong" and which no stack trace
 * will ever explain.
 *
 * So the test does not check that concurrent calls "work". It checks that every single
 * answer carries back the PID that was asked for. **Delete the mutex from [ElmSession] and
 * this test fails** — that is the entire point of it, and it is the thing to re-verify
 * before trusting a single number this app displays.
 */
class ElmSessionHalfDuplexTest {

    private val pids = listOf("00", "05", "0C", "0D", "11", "2F")

    @Test
    fun `200 exchanges from 20 coroutines never cross their answers`() = runTest {
        val emulator = ElmEmulator(
            clock = elmClock(),
            config = ElmEmulatorConfig(commandLatency = 5.milliseconds),
        )
        val session = ElmSession(emulator, backgroundScope, sessionConfig())
        session.connect()

        val results = (0 until 20).map { worker ->
            async {
                (0 until 10).map { round ->
                    val pid = pids[(worker + round) % pids.size]
                    pid to session.exchange(ElmRequest("01$pid", expectedFrames = 1))
                }
            }
        }.awaitAll().flatten()

        results.size shouldBe 200

        // The adapter answers `010C` with `7E8 04 41 0C ...` — three header nibbles, one
        // PCI byte, then the service echo `41` and the PID it is answering about. That
        // echo is the only thing that ties a response back to its request, and checking
        // it is the only way to catch a stream that has shifted by one.
        val crossed = results.filterNot { (pid, response) ->
            val line = (response as? ElmResponse.Ok)?.lines?.singleOrNull()
            line != null && line.drop(5).startsWith("41$pid")
        }
        assertTrue(
            crossed.isEmpty(),
            "${crossed.size}/200 answers were attributed to the wrong request: ${crossed.take(5)}",
        )

        val desyncs = results.count { (_, response) ->
            response is ElmResponse.Err && response.kind == ElmErrorKind.DESYNC
        }
        desyncs shouldBe 0
    }

    /**
     * The adapter is the scarce resource, so the session must not let a burst of callers
     * pile writes onto it — it must queue them. The emulator refuses to answer two
     * commands at once, so a second write landing mid-answer is what produces `STOPPED`.
     */
    @Test
    fun `concurrent callers never provoke STOPPED`() = runTest {
        val stopped = mutableListOf<String>()
        val emulator = ElmEmulator(
            clock = elmClock(),
            config = ElmEmulatorConfig(commandLatency = 5.milliseconds),
        )
        val session = ElmSession(emulator, backgroundScope, sessionConfig { stopped += it })
        session.connect()

        (0 until 12).map { worker ->
            async { repeat(5) { session.exchange(ElmRequest("01${pids[worker % pids.size]}")) } }
        }.awaitAll()

        stopped shouldBe emptyList()
    }
}
