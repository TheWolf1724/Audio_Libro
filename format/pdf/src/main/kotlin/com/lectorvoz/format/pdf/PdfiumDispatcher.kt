package com.lectorvoz.format.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Hilo unico para todas las llamadas a pdfium (no es thread-safe, ni siquiera entre
 * documentos distintos). Hilo demonio de prioridad normal: dibuja la pagina visible y
 * extrae el texto que la voz necesita ya, asi que no debe quedar relegado como uno de
 * fondo. Los renders obsoletos se descartan en [PdfBook.renderPage] antes de la llamada
 * nativa.
 */
object PdfiumDispatcher {
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                r.run()
            }, "pdfium").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
