package com.lectorvoz.app.books

import android.net.Uri
import android.util.Log
import com.lectorvoz.app.ui.library.LibraryViewModel
import com.lectorvoz.core.data.library.ImportError
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val _message = MutableStateFlow<String?>(null)

    /** Resumen de la ultima importacion de la biblioteca (o aviso de cancelacion) hasta que la pantalla lo consuma. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    private val jobs = HashSet<Job>()

    // Una importacion a la vez: dos lotes con el mismo fichero no pueden crear dos filas, y la copia es E/S pesada.
    private val gate = Mutex()

    /**
     * Importa [uris] desde la biblioteca en el ambito de la aplicacion: salir de la pantalla no la corta, el progreso
     * ([running]) y el resumen ([message]) esperan a la siguiente pantalla. Cada fichero va por separado (uno que falla
     * no aborta el lote) y [cancel] la detiene sin dejar copias a medias (el repositorio borra su temporal).
     */
    fun importLibrary(uris: List<Uri>, copy: Boolean) {
        if (uris.isEmpty()) return
        _running.update { it + uris.size }
        Log.i(TAG, "Importacion de ${uris.size} ficheros (copia=$copy)")
        val job = scope.launch {
            var ok = 0
            var dup = 0
            var left = uris.size
            val failed = ArrayList<String>()
            try {
                for (u in uris) {
                    try {
                        when (val r = gate.withLock { library.import(u, copy) }) {
                            is ImportResult.Imported -> ok++
                            is ImportResult.Duplicate -> dup++
                            is ImportResult.Failed -> {
                                Log.w(TAG, "Fallo al importar ${r.name}: ${r.error}")
                                failed += "${r.name}: ${LibraryViewModel.errorText(r.error)}"
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Importacion fallida", e)
                        failed += "${u.lastPathSegment ?: u}: ${LibraryViewModel.errorText(ImportError.UNREADABLE)}"
                    } finally {
                        left--
                        _running.update { (it - 1).coerceAtLeast(0) }
                    }
                }
                _message.value = LibraryViewModel.importSummary(ok, dup, failed)
                Log.i(TAG, "Importacion terminada: $ok nuevos, $dup repetidos, ${failed.size} fallidos")
            } finally {
                // Cancelado a mitad: los que no llegaron a procesarse tambien dejan de contar.
                if (left > 0) _running.update { (it - left).coerceAtLeast(0) }
            }
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
    }

    /** Cancela las importaciones en curso (los libros ya importados se quedan). */
    fun cancel() {
        val active = synchronized(jobs) { jobs.toList() }
        active.forEach { it.cancel() }
        if (active.isNotEmpty()) {
            Log.i(TAG, "Importacion cancelada por el usuario")
            _message.value = "Importacion cancelada"
        }
    }

    private val channel = Channel<ExternalImport>(Channel.BUFFERED)

    /** Resultados (un unico recolector: la actividad visible). */
    val results: Flow<ExternalImport> = channel.receiveAsFlow()

    /** Importa [uris]; si es uno solo, el resultado pide abrirlo. */
    fun importExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _running.update { it + uris.size }
        val job = scope.launch {
            var last: Long? = null
            var failed = 0
            var left = uris.size
            try {
            for (u in uris) {
                try {
                    when (val r = gate.withLock { library.import(u, copy = true) }) {
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
                    left--
                    _running.update { (it - 1).coerceAtLeast(0) }
                }
            }
            } finally {
                // Cancelado a mitad: los que no llegaron a procesarse tambien dejan de contar.
                if (left > 0) _running.update { (it - left).coerceAtLeast(0) }
            }
            channel.send(ExternalImport(if (uris.size == 1) last else null, failed))
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
    }

    private companion object {
        const val TAG = "ImportCoordinator"
    }
}
