package com.lectorvoz.reader

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.reader.render.PdfRecolor
import com.lectorvoz.reader.view.CurlMesh
import com.lectorvoz.reader.view.Settle
import com.lectorvoz.reader.view.TapZone
import com.lectorvoz.reader.view.TurnMath
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Logica pura de gestos, animacion, curl, seguimiento y recoloreado (JVM, sin Android). */
class TurnLogicTest {

    @Test
    fun progreso_del_arrastre() {
        assertThat(TurnMath.dragProgress(1000f, 500f, 1000f, forward = true)).isEqualTo(0.5f)
        assertThat(TurnMath.dragProgress(1000f, 1200f, 1000f, forward = true)).isEqualTo(0f)
        assertThat(TurnMath.dragProgress(100f, 600f, 1000f, forward = false)).isEqualTo(0.5f)
        assertThat(TurnMath.dragProgress(0f, -5000f, 1000f, forward = true)).isEqualTo(1f)
        assertThat(TurnMath.dragProgress(0f, 10f, 0f, forward = true)).isEqualTo(0f)
    }

    @Test
    fun completar_o_cancelar() {
        val fling = 400f
        // Sin velocidad decide la distancia.
        assertThat(TurnMath.shouldComplete(0.5f, 0f, true, fling)).isTrue()
        assertThat(TurnMath.shouldComplete(0.1f, 0f, true, fling)).isFalse()
        // Lanzamiento hacia la izquierda completa aunque se haya movido poco.
        assertThat(TurnMath.shouldComplete(0.05f, -2000f, true, fling)).isTrue()
        // Lanzamiento en contra cancela aunque se haya movido mucho.
        assertThat(TurnMath.shouldComplete(0.8f, 2000f, true, fling)).isFalse()
        // Hacia atras, el sentido se invierte.
        assertThat(TurnMath.shouldComplete(0.05f, 2000f, false, fling)).isTrue()
        assertThat(TurnMath.shouldComplete(0.8f, -2000f, false, fling)).isFalse()
    }

    @Test
    fun zonas_de_toque_y_duracion() {
        assertThat(TurnMath.tapZone(10f, 1000f)).isEqualTo(TapZone.LEFT)
        assertThat(TurnMath.tapZone(500f, 1000f)).isEqualTo(TapZone.CENTER)
        assertThat(TurnMath.tapZone(990f, 1000f)).isEqualTo(TapZone.RIGHT)
        assertThat(TurnMath.settleDuration(0f, 1f, 350)).isEqualTo(350L)
        assertThat(TurnMath.settleDuration(0.99f, 1f, 350)).isEqualTo(60L)
        assertThat(TurnMath.settleDuration(0f, 1f, 0)).isEqualTo(0L)
    }

    @Test
    fun asentamiento_por_tiempo() {
        val s = Settle()
        s.start(0.2f, 1f, 100L, nowMs = 1000L)
        assertThat(s.value(1000L)).isEqualTo(0.2f)
        assertThat(s.value(1050L)).isGreaterThan(0.6f) // desacelera: mas de la mitad a mitad de tiempo
        assertThat(s.value(1100L)).isEqualTo(1f)
        assertThat(s.isFinished(1100L)).isTrue()
        assertThat(s.isFinished(1099L)).isFalse()
        s.start(0.5f, 0f, 0L, 0L)
        assertThat(s.value(0L)).isEqualTo(0f)
    }

    private fun assertFinite(a: FloatArray) {
        for (v in a) assertThat(v.isFinite()).isTrue()
    }

    @Test
    fun malla_sin_nan_en_casos_limite() {
        val m = CurlMesh(10, 14)
        val w = 1080f; val h = 1920f
        val touches = listOf(
            w to h, // dedo en la esquina: plano
            w - 0.1f to h,
            w - 1f to h - 1f,
            0f to h, -w to h, -3 * w to h,
            w / 2 to 0f, w / 2 to h * 3, Float.MAX_VALUE to 0f, -Float.MAX_VALUE to h,
            Float.NaN to 10f,
        )
        for ((x, y) in touches) {
            m.compute(w, h, x, y, w, h, w / 9f)
            assertFinite(m.front); assertFinite(m.back)
        }
        // Tamano cero.
        m.compute(0f, 0f, 1f, 1f, 0f, 0f, 10f)
        assertFinite(m.front)
    }

    @Test
    fun malla_plana_si_el_dedo_esta_en_la_esquina() {
        val m = CurlMesh(4, 4)
        assertThat(m.compute(100f, 200f, 100f, 200f, 100f, 200f, 10f)).isFalse()
        // Vertice (i=4, j=4) = esquina inferior derecha intacta.
        val k = 4 * 5 + 4
        assertThat(m.front[k * 2]).isEqualTo(100f)
        assertThat(m.front[k * 2 + 1]).isEqualTo(200f)
    }

    @Test
    fun malla_bordes_lomo_fijo_y_esquina_bajo_el_dedo() {
        val w = 1000f; val h = 1600f
        val m = CurlMesh(20, 20)
        val tx = 300f; val ty = 1500f
        assertThat(m.compute(w, h, tx, ty, w, h, w / 9f)).isTrue()
        // El lomo (x = 0) no se mueve.
        for (j in 0..20) {
            val k = j * 21
            assertThat(m.front[k * 2]).isWithin(1e-3f).of(0f)
            assertThat(m.front[k * 2 + 1]).isWithin(1e-3f).of(h * j / 20)
        }
        // La esquina (ultimo vertice) del reverso cae bajo el dedo.
        val last = m.vertexCount - 1
        val d = hypot(m.back[last * 2] - tx, m.back[last * 2 + 1] - ty)
        assertThat(d).isLessThan(1f)
        // Colores opacos.
        for (c in m.frontColors) assertThat(c ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun progreso_programado_completo_deja_la_hoja_fuera() {
        val w = 1000f; val h = 1600f; val r = w / 9f
        val out = FloatArray(2)
        CurlMesh.touchForProgress(1f, w, h, h, r, 50f, out)
        val m = CurlMesh(12, 12)
        m.compute(w, h, out[0], out[1], w, h, r)
        // Todo el reverso queda a la izquierda de la pantalla.
        for (i in 0 until m.vertexCount) assertThat(m.back[i * 2]).isAtMost(0.5f)
        CurlMesh.touchForProgress(0f, w, h, h, r, 50f, out)
        assertThat(out[0]).isEqualTo(w)
        assertThat(CurlMesh.progressForTouchX(w, w, r)).isEqualTo(0f)
        assertThat(abs(CurlMesh.progressForTouchX(-w - Math.PI.toFloat() * r, w, r) - 1f)).isLessThan(1e-4f)
    }

    @Test
    fun seguir_lectura_no_oscila() {
        val pageStart = Locator(0, 1000)
        val pageEnd = Locator(0, 2000)
        val sentence = LocatorRange(Locator(0, 950), Locator(0, 1100))
        // Dentro de la pagina: no mover.
        assertThat(FollowPolicy.shouldMove(pageStart, pageEnd, Locator(0, 1500), sentence)).isFalse()
        // Inicio de la frase activa en la pagina anterior pero la frase sigue aqui: no volver.
        assertThat(FollowPolicy.shouldMove(pageStart, pageEnd, Locator(0, 960), sentence)).isFalse()
        // Otra frase anterior (p. ej. "frase anterior"): si volver.
        assertThat(FollowPolicy.shouldMove(pageStart, pageEnd, Locator(0, 500), sentence)).isTrue()
        // Pasada la pagina: avanzar.
        assertThat(FollowPolicy.shouldMove(pageStart, pageEnd, Locator(0, 2000), sentence)).isTrue()
        assertThat(FollowPolicy.shouldMove(pageStart, pageEnd, Locator(1, 0), null)).isTrue()
    }

    @Test
    fun recoloreado_por_luminancia() {
        val bg = 0xFF1B140F.toInt()
        val text = 0xFFE4BA84.toInt()
        val m = PdfRecolor.matrixValues(bg, text)
        assertThat(PdfRecolor.apply(m, 0xFFFFFFFF.toInt())).isEqualTo(bg)
        assertThat(PdfRecolor.apply(m, 0xFF000000.toInt())).isEqualTo(text)
        // Un gris medio queda entre ambos y conserva el alfa.
        val mid = PdfRecolor.apply(m, 0x80808080.toInt())
        assertThat(mid ushr 24).isEqualTo(0x80)
        val r = (mid shr 16) and 0xFF
        assertThat(r).isIn(0x1B..0xE4)
    }
}
