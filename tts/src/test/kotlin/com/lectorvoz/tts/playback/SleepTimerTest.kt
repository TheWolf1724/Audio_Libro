package com.lectorvoz.tts.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Temporizador con tiempo virtual de corrutinas. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    @Test
    fun cuentaAtrasFundidoYVencimiento() = runTest {
        val ticks = ArrayList<Long>()
        val fades = ArrayList<Float>()
        var expiredAt = -1L
        val timer = SleepTimer(
            scope = backgroundScope,
            onTick = { ticks += it },
            onFade = { fades += it },
            onExpire = { expiredAt = testScheduler.currentTime },
        )
        timer.start(60_000)
        runCurrent()
        assertThat(ticks.first()).isEqualTo(60_000)

        advanceTimeBy(49_000)
        runCurrent()
        // Antes de los ultimos 10 s no hay fundido.
        assertThat(fades).isEmpty()
        assertThat(ticks.last()).isEqualTo(11_000)

        advanceTimeBy(6_000)
        runCurrent()
        // A mitad del fundido el volumen ronda 0,5 y baja de forma monotona.
        assertThat(fades.last()).isWithin(0.03f).of(0.5f)
        assertThat(fades).isInOrder(Comparator<Float> { a, b -> b.compareTo(a) })

        advanceTimeBy(5_000)
        runCurrent()
        assertThat(expiredAt).isEqualTo(60_000)
        // Al vencer se restaura el volumen para la proxima vez.
        assertThat(fades.last()).isEqualTo(1f)
        assertThat(timer.isRunning).isFalse()
    }

    @Test
    fun cancelarRestauraElVolumenYNoVence() = runTest {
        val fades = ArrayList<Float>()
        var expired = false
        val timer = SleepTimer(backgroundScope, {}, { fades += it }, { expired = true })
        timer.start(20_000)
        advanceTimeBy(15_000)
        runCurrent()
        assertThat(fades.last()).isLessThan(1f)
        timer.cancel()
        assertThat(fades.last()).isEqualTo(1f)
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(expired).isFalse()
    }

    @Test
    fun cicloDelBotonDeNotificacion() {
        var m: com.lectorvoz.tts.SleepTimerMode? = null
        val seen = ArrayList<String>()
        repeat(5) {
            m = SleepTimer.nextInCycle(m)
            seen += m.toString()
        }
        assertThat(seen).containsExactly(
            "Minutes(minutes=15)", "Minutes(minutes=30)", "Minutes(minutes=60)", "EndOfChapter", "null",
        ).inOrder()
    }

    @Test
    fun ventanaDeAgitarYAlargar() = runTest {
        val windows = ArrayList<Boolean>()
        val fades = ArrayList<Float>()
        var expiredAt = -1L
        val timer = SleepTimer(
            scope = backgroundScope,
            onTick = {},
            onFade = { fades += it },
            onExpire = { expiredAt = testScheduler.currentTime },
            onWindow = { windows += it },
        )
        timer.start(60_000)
        advanceTimeBy(29_500)
        runCurrent()
        assertThat(windows).isEmpty()
        advanceTimeBy(1_000)
        runCurrent()
        // Ultimos 30 s: se escucha el gesto.
        assertThat(windows).containsExactly(true)
        advanceTimeBy(25_000) // En pleno fundido.
        runCurrent()
        assertThat(fades.last()).isLessThan(1f)
        timer.extend(SleepTimer.SHAKE_EXTENSION_MS)
        assertThat(fades.last()).isEqualTo(1f)
        assertThat(windows).containsExactly(true, false).inOrder()
        assertThat(timer.remainingMs).isGreaterThan(10 * 60_000L)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertThat(expiredAt).isEqualTo(-1L)
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(expiredAt).isEqualTo(660_000L)
        assertThat(timer.isRunning).isFalse()
        assertThat(windows.last()).isFalse()
    }
}
