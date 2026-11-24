package com.lectorvoz.tts.neural

/**
 * Hilos de inferencia de la voz natural segun el equipo. En moviles modestos (poca RAM o pocos
 * nucleos) 2 es lo razonable: mas hilos compiten con el audio y con los nucleos pequenos y no
 * aceleran. En equipos con mas memoria (que casi siempre llevan mas nucleos rapidos) se usan mas
 * para sintetizar antes y dar mejor voz con mas holgura. Sin medir el SoC (no hay forma fiable de
 * saber cuantos nucleos son "grandes"), la RAM es un buen indicador de la gama.
 */
object NeuralThreads {
    /** Hilos para [cores] nucleos logicos y [totalMemBytes] de RAM. */
    fun choose(cores: Int, totalMemBytes: Long): Int {
        val gb = totalMemBytes / 1_000_000_000.0
        return when {
            cores < 6 || gb < 5.0 -> 2
            gb < 8.0 -> 3
            cores >= 8 -> 4
            else -> 3
        }
    }
}
