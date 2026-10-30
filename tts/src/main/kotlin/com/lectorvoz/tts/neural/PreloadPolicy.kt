package com.lectorvoz.tts.neural

/**
 * Cuanto audio prepara el motor por delante de lo que suena (ver `NeuralTtsEngine`).
 *
 * Precargar mas NO gasta mas bateria (es el mismo trabajo, hecho antes); lo que cuesta es
 * memoria y el trabajo que se tira si el usuario salta o cambia de capitulo. Por eso se adapta
 * al terminal ([PreloadTiers]): mucho en un movil sobrado, poco en uno justo de memoria.
 */
data class PreloadPolicy(
    /** Margen de audio listo hasta el que se rellena (ms). 0 = solo [NeuralTtsEngine] lookahead. */
    val bufferAheadMs: Long,
    /** Por debajo de este margen (ms) se vuelve a rellenar (histeresis). */
    val refillBelowMs: Long,
    /** Tope de audio listo por delante (ms): acota la memoria. */
    val maxAheadMs: Long,
    /** Parrafos ENTEROS por delante del que suena antes de descansar (0 = sin esta regla). */
    val paragraphsAhead: Int,
    /** Nombre del nivel (para el estado y los registros). */
    val level: String = "",
)

/**
 * Niveles de precarga segun la capacidad del terminal. Funcion pura (probada sin Android):
 * los datos los lee `DevicePreload` del sistema y el motor mide su propia velocidad.
 */
object PreloadTiers {
    /** Frases siguientes (~15 s): moviles con poca memoria, ahorro de bateria o calientes. */
    val LOW = PreloadPolicy(bufferAheadMs = 15_000, refillBelowMs = 8_000, maxAheadMs = 30_000, paragraphsAhead = 0, level = "baja")

    /** Parrafo siguiente entero + 30 s: lo normal. */
    val MEDIUM = PreloadPolicy(bufferAheadMs = 30_000, refillBelowMs = 15_000, maxAheadMs = 90_000, paragraphsAhead = 1, level = "media")

    /** Dos parrafos enteros + 60 s (aprox. la pagina siguiente), tope 3 min: moviles sobrados. */
    val HIGH = PreloadPolicy(bufferAheadMs = 60_000, refillBelowMs = 30_000, maxAheadMs = 180_000, paragraphsAhead = 2, level = "alta")

    private const val GB = 1024L * 1024 * 1024

    /**
     * Nivel para un terminal con [totalMemBytes] de RAM ([lowRamDevice] = Android lo marca como
     * de poca memoria), [powerSave] activo, estado termico [thermal] (0 = normal ... 6 =
     * apagado, como `PowerManager.THERMAL_STATUS_*`; -1 = desconocido) y velocidad de sintesis
     * medida [rtf] (segundos de calculo por segundo de audio; nulo = aun sin medir).
     */
    fun choose(totalMemBytes: Long, lowRamDevice: Boolean, powerSave: Boolean, thermal: Int, rtf: Float?): PreloadPolicy {
        val hot = thermal >= THERMAL_SEVERE
        val slow = rtf != null && rtf > SLOW_RTF
        if (lowRamDevice || totalMemBytes in 1 until 3 * GB || powerSave || hot || slow) return LOW
        val warm = thermal >= THERMAL_MODERATE
        val fast = rtf == null || rtf < FAST_RTF
        if (totalMemBytes >= 6 * GB && !warm && fast) return HIGH
        return MEDIUM
    }

    /** Sintesis mas lenta que esto: el movil va justo (la precarga casi no se llena). */
    const val SLOW_RTF = 0.85f

    /** Sintesis mas rapida que esto: sobra margen para precargar mucho. */
    const val FAST_RTF = 0.5f

    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
}

/**
 * Nivel de precarga y por que (para mostrarlo en Ajustes). [live] = lo esta usando el motor
 * ahora (con la velocidad medida); si no, es una estimacion con los datos del terminal.
 */
data class PreloadStatus(
    val policy: PreloadPolicy,
    val totalMemBytes: Long,
    val lowRamDevice: Boolean,
    val powerSave: Boolean,
    /** Como `PowerManager.THERMAL_STATUS_*` (0 normal ... 6); -1 desconocido. */
    val thermal: Int,
    /** Segundos de calculo por segundo de audio medidos en este terminal; nulo si aun no. */
    val rtf: Float?,
    val live: Boolean,
)
