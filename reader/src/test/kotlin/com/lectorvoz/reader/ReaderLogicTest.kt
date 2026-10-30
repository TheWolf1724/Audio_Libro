package com.lectorvoz.reader

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.core.model.ReaderSettings
import com.lectorvoz.reader.layout.ImageFit
import com.lectorvoz.reader.layout.ImageSize
import com.lectorvoz.reader.layout.LayoutSettings
import com.lectorvoz.reader.layout.PageChrome
import com.lectorvoz.reader.layout.PageGeometry
import com.lectorvoz.reader.layout.Viewport
import org.junit.Test

/** Logica pura del lector (sin Android). */
class ReaderLogicTest {

    // ------------------------------------------------------------ progreso por caracteres

    @Test
    fun caracteres_restantes_exactos() {
        val counts = intArrayOf(1000, 500, 2000)
        val p = TextProgress.compute(Locator(1, 100), 3) { counts[it] }
        assertThat(p.exact).isTrue()
        assertThat(p.chapterRemaining).isEqualTo(400)
        assertThat(p.bookRemaining).isEqualTo(2400)
        assertThat(p.chapterFraction).isWithin(1e-4f).of(0.2f)
        assertThat(p.bookFraction).isWithin(1e-4f).of(1100f / 3500f)
    }

    @Test
    fun caracteres_restantes_estimados_y_limites() {
        val counts = arrayOf<Int?>(1000, null, null)
        val p = TextProgress.compute(Locator(0, 5000), 3) { counts[it] }
        assertThat(p.exact).isFalse()
        assertThat(p.chapterRemaining).isEqualTo(0) // offset mas alla del final
        assertThat(p.bookRemaining).isEqualTo(2000) // dos capitulos estimados con la media
        assertThat(TextProgress.compute(Locator(0, 0), 2) { null }.exact).isFalse()
        assertThat(TextProgress.compute(Locator(7, 0), 0) { 1 }.bookRemaining).isEqualTo(0)
        // Capitulo fuera de rango: se ajusta al ultimo.
        assertThat(TextProgress.compute(Locator(9, 0), 2) { 10 }.chapterRemaining).isEqualTo(10)
    }

    // ------------------------------------------------------------ seguir la voz

    private fun r(c: Int, s: Int, e: Int) = LocatorRange(Locator(c, s), Locator(c, e))

    @Test
    fun seguir_la_voz_casos() {
        val start = Locator(0, 100)
        val end = Locator(0, 200)
        // Dentro: no se mueve.
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 150), null)).isFalse()
        // Fin exclusivo: la siguiente pagina.
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 200), null)).isTrue()
        // Hacia atras sin frase activa: se mueve.
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 50), null)).isTrue()
        // Inicio de una frase que sigue en esta pagina: no retrocede (no oscila).
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 90), r(0, 90, 120))).isFalse()
        // Frase que acaba antes de la pagina: si retrocede.
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 60), r(0, 60, 99))).isTrue()
        // Otra posicion fuera de la frase activa: se mueve.
        assertThat(FollowPolicy.shouldMove(start, end, Locator(0, 10), r(0, 90, 120))).isTrue()
        // Frase que cruza de capitulo: tampoco retrocede.
        val cross = LocatorRange(Locator(0, 950), Locator(1, 20))
        assertThat(FollowPolicy.shouldMove(Locator(1, 0), Locator(1, 300), Locator(0, 950), cross)).isFalse()
    }

    // ------------------------------------------------------------ geometria

    @Test
    fun geometria_de_pagina() {
        val v = Viewport(1080, 2400, 3f)
        val s = LayoutSettings.from(ReaderSettings(fontSizeSp = 18f, marginHorizontalDp = 24, marginVerticalDp = 20))
        val g = PageGeometry.compute(v, s)
        assertThat(g.textSizePx).isEqualTo(54f)
        assertThat(g.columnLeft + g.columnWidth).isAtMost(1080f)
        assertThat(g.columnLeft).isAtLeast(72f - 0.5f)
        assertThat(g.contentTop).isEqualTo(60f + g.headerHeight)
        assertThat(g.contentHeight.toFloat()).isWithin(1f).of(2400f - 120f - g.headerHeight - g.footerHeight)
        // Sin cabecera ni pie se gana alto.
        val bare = PageGeometry.compute(v, s.copy(chrome = PageChrome(showHeader = false, showFooter = false)))
        assertThat(bare.contentHeight).isGreaterThan(g.contentHeight)
        // Linea maxima: con 30 caracteres la columna se estrecha y se centra.
        val narrow = PageGeometry.compute(v, s.copy(maxLineChars = 30))
        assertThat(narrow.columnWidth).isLessThan(g.columnWidth)
        assertThat(narrow.columnLeft * 2 + narrow.columnWidth).isWithin(1f).of(1080f)
    }

    @Test
    fun geometria_en_vista_minima_no_es_negativa() {
        val g = PageGeometry.compute(Viewport(50, 60, 3f), LayoutSettings.from(ReaderSettings(fontSizeSp = 40f)))
        assertThat(g.columnWidth).isAtLeast(1)
        assertThat(g.columnWidth).isAtMost(50)
        assertThat(g.contentHeight).isAtLeast(1)
        assertThat(g.columnLeft).isAtLeast(0f)
    }

    // ------------------------------------------------------------ imagenes

    @Test
    fun tamano_de_imagen() {
        // Natural 400x300 a 2x: 800x600, cabe.
        assertThat(ImageFit.fit(ImageSize(400, 300), null, null, 2f, 1000, 1000)).isEqualTo(ImageSize(800, 600))
        // Demasiado grande: se reduce manteniendo proporcion.
        assertThat(ImageFit.fit(ImageSize(4000, 2000), null, null, 1f, 1000, 1000)).isEqualTo(ImageSize(1000, 500))
        // Solo ancho declarado: alto por proporcion natural.
        assertThat(ImageFit.fit(ImageSize(200, 100), 100, null, 1f, 1000, 1000)).isEqualTo(ImageSize(100, 50))
        // Declarado completo sin tamano natural.
        assertThat(ImageFit.fit(null, 50, 80, 2f, 1000, 1000)).isEqualTo(ImageSize(100, 160))
        // Sin nada: no hay hueco.
        assertThat(ImageFit.fit(null, null, null, 2f, 1000, 1000)).isNull()
        assertThat(ImageFit.fit(ImageSize(0, 10), null, null, 2f, 1000, 1000)).isNull()
    }
}
