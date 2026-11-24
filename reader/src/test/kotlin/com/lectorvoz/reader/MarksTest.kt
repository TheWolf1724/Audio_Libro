package com.lectorvoz.reader

import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.LocatorRange
import com.lectorvoz.reader.render.Highlights
import com.lectorvoz.reader.render.MarkColors
import com.lectorvoz.reader.render.MarkRange
import org.junit.Test

/** Subrayados del usuario dentro del modelo de resaltados. */
class MarksTest {
    private fun range(ch: Int, s: Int, e: Int) = LocatorRange(Locator(ch, s), Locator(ch, e))

    @Test
    fun losSubrayadosCuentanParaSaberQuePaginasRedibujar() {
        val h = Highlights(marks = listOf(MarkRange(range(1, 5, 9), 2)))
        assertThat(h.all()).containsExactly(range(1, 5, 9))
        assertThat(h).isNotEqualTo(Highlights.NONE)
        assertThat(Highlights.clip(h.marks[0].range, 1, 100)).isEqualTo(5 until 9)
        assertThat(Highlights.clip(h.marks[0].range, 2, 100)).isNull()
    }

    @Test
    fun elIndiceDeColorSeAcotaYTodosSonDistintos() {
        assertThat(MarkColors.argb(-3)).isEqualTo(MarkColors.argb(0))
        assertThat(MarkColors.argb(99)).isEqualTo(MarkColors.argb(3))
        assertThat((0..3).map { MarkColors.argb(it) }.toSet()).hasSize(4)
    }
}
