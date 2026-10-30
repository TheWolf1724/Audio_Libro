package com.lectorvoz.app.books

import android.net.Uri
import android.util.Log
import com.lectorvoz.core.data.library.ImportResult
import com.lectorvoz.core.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resultado de importar lo recibido por "Abrir con" o "Compartir". */
data class ExternalImport(
    /** Libro que abrir (si se recibio uno solo y se importo o ya estaba). */
    val openBookId: Long?,
    val failed: Int,
)

/**
 * Importa lo que llega por "Abrir con" o "Compartir" en el ambito de la aplicacion
 * (revision 3, A6): girar la pantalla o recrear la actividad no cancela la copia, y el
 * resultado espera en [results] hasta que una actividad lo recoja.
 *
 * Esos Uri solo traen permiso temporal, asi que siempre se copian a la memoria privada (si
 * no, el libro dejaria de abrirse al reiniciar).
 */
class ImportCoordinator(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val _running = MutableStateFlow(0)

    /** Ficheros pendientes de importar. */
    val running: StateFlow<Int> = _running.asStateFlow()

    private val channel = Channel<ExternalImport>(Channel.BUFFERED)

    /** Resultados (un unico recolector: la actividad visible). */
    val results: Flow<ExternalImport> = channel.receiveAsFlow()

    /** Importa [uris]; si es uno solo, el resultado pide abrirlo. */
    fun importExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _running.update { it + uris.size }
        scope.launch {
            var last: Long? = null
            var failed = 0
            for (u in uris) {
                try {
                    when (val r = library.import(u, copy = true)) {
                        is ImportResult.Imported -> last = r.book.id
                        is ImportResult.Duplicate -> last = r.book.id
                        is ImportResult.Failed -> failed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Importacion fallida", e)
                    failed++
                } finally {
                    _running.update { (it - 1).coerceAtLeast(0) }
                }
            }
            channel.send(ExternalImport(if (uris.size == 1) last else null, failed))
        }
    }

    private companion object {
        const val TAG = "ImportCoordinator"
    }
}
