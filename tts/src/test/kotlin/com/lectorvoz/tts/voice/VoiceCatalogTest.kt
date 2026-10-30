package com.lectorvoz.tts.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceCatalogTest {

    @Test
    fun soloSupertonicIncluidaYSinPiper() {
        assertThat(VoiceCatalog.all.map { it.id }).containsExactly(VoiceCatalog.SUPERTONIC_ID)
        assertThat(VoiceCatalog.all.map { it.kind }.toSet()).containsExactly(NeuralModelKind.SUPERTONIC)
        assertThat(VoiceCatalog.downloadable).isEmpty()
        val def = VoiceCatalog.find(VoiceCatalog.DEFAULT_VOICE_ID)!!
        assertThat(def.source).isEqualTo(VoiceSource.Bundled)
        assertThat(def.files).isInstanceOf(ModelFiles.Supertonic::class.java)
        assertThat(def.languages).containsAtLeast("es", "en")
        assertThat(def.languages.first()).isEqualTo("es")
    }

    @Test
    fun diezLocutoresConNombreF5PrimeroF2Segundo() {
        val v = VoiceCatalog.find(VoiceCatalog.SUPERTONIC_ID)!!
        assertThat(v.speakers).hasSize(10)
        assertThat(v.speakers.map { it.sid }.toSet()).isEqualTo((0..9).toSet())
        assertThat(v.speakers[0]).isEqualTo(VoiceSpeaker(4, "F5", "Mujer 5", VoiceGender.FEMALE))
        assertThat(v.speakers[1]).isEqualTo(VoiceSpeaker(1, "F2", "Mujer 2", VoiceGender.FEMALE))
        assertThat(v.speaker(5)!!.name).isEqualTo("Hombre 1")
        assertThat(v.speaker(9)!!.code).isEqualTo("M5")
        assertThat(v.defaultSid).isEqualTo(VoiceCatalog.DEFAULT_SID)
        assertThat(v.baseSpeed).isEqualTo(1.4375f)
    }

    @Test
    fun referenciaDeVozIdaYVuelta() {
        // Por defecto: Supertonic, F5 (sid 4).
        assertThat(VoiceRef.parse(null)).isEqualTo(VoiceRef(VoiceCatalog.SUPERTONIC_ID, 4))
        assertThat(VoiceRef.parse(VoiceCatalog.SUPERTONIC_ID)).isEqualTo(VoiceRef(VoiceCatalog.SUPERTONIC_ID, 4))
        assertThat(VoiceRef.parse("supertonic-3-int8#1")).isEqualTo(VoiceRef("supertonic-3-int8", 1))
        assertThat(VoiceRef("supertonic-3-int8", 0).encode()).isEqualTo("supertonic-3-int8#0")
        assertThat(VoiceRef.parse(VoiceRef("supertonic-3-int8", 0).encode()).sid).isEqualTo(0)
        assertThat(VoiceRef.parse("supertonic-3-int8#nada")).isEqualTo(VoiceRef("supertonic-3-int8", 4))
        assertThat(VoiceRef.parse("desconocida")).isEqualTo(VoiceRef("desconocida", 0))
    }

    @Test
    fun sha256Conocido() {
        assertThat(Sha256.of("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
