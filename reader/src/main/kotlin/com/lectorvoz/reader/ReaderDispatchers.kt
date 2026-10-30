package com.lectorvoz.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Despachadores propios del lector. */
internal object ReaderDispatchers {
    /**
     * Hilo unico demonio de prioridad de fondo para trabajo prescindible (contar las paginas
     * de todo el libro): no compite con la maquetacion visible ni con el render.
     */
    val background: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "lector-fondo").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
