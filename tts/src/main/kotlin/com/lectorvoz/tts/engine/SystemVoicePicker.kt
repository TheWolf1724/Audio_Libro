package com.lectorvoz.tts.engine

import java.util.Locale

/** Voz del sistema para mostrar en ajustes y para elegir por idioma. */
data class SystemVoice(
    val name: String,
    val locale: Locale,
    /** `Voice.QUALITY_*` (100..500). */
    val quality: Int,
    val requiresNetwork: Boolean,
    /** `Voice.LATENCY_*` (100..500, menor es mejor). */
    val latency: Int = 300,
    /** La voz anuncia que sus datos no estan instalados. */
    val notInstalled: Boolean = false,
)

/**
 * Elige la mejor voz del sistema para un idioma ("es" o "en"): instalada y sin red,
 * del pais preferido (es-ES; en-US y luego en-GB), con la mayor calidad y la menor
 * latencia. Si el usuario eligio una voz y es de ese idioma, se respeta.
 */
object SystemVoicePicker {
    /** Paises preferidos por idioma, en orden. */
    private val preferredCountries = mapOf(
        "es" to listOf("ES"),
        "en" to listOf("US", "GB"),
    )

    /** Mejor voz para [lang] o nulo si no hay ninguna de ese idioma. */
    fun best(voices: List<SystemVoice>, lang: String, userVoice: String? = null): SystemVoice? {
        val candidates = voices.filter { it.locale.language == Locale.forLanguageTag(lang).language && !it.notInstalled }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.name == userVoice }?.let { return it }
        val countries = preferredCountries[lang].orEmpty()
        return candidates.sortedWith(
            compareBy<SystemVoice> { it.requiresNetwork }
                .thenBy { countries.indexOf(it.locale.country).let { i -> if (i < 0) countries.size else i } }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name },
        ).first()
    }

    /** Locale por defecto para [lang] si no hay voces que elegir. */
    fun defaultLocale(lang: String): Locale =
        if (lang == "en") Locale.forLanguageTag("en-US") else Locale.forLanguageTag("es-ES")
}
