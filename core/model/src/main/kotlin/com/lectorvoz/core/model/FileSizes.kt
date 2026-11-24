package com.lectorvoz.core.model

import java.io.File

/**
 * Bytes que ocupa [this] (un fichero) o todo lo que cuelga de ella (una carpeta). Recorrido manual y
 * tolerante: si una carpeta desaparece o cambia a mitad (otra parte de la app borra, el sistema vacia
 * la cache) se salta lo que falle en vez de lanzar. `File.walkTopDown()` NO vale: comprueba que la raiz
 * es carpeta y luego lo afirma con un `assert`, y una carrera con un borrado lo tumba.
 */
fun File.treeSizeBytes(): Long {
    if (isFile) return length()
    val children = listFiles() ?: return 0L
    var total = 0L
    for (c in children) total += c.treeSizeBytes()
    return total
}
