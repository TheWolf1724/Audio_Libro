package com.lectorvoz.format.pdf

/**
 * Canal de diagnostico opcional de :format:pdf (el modulo no depende de :tts). La app conecta
 * [sink] al registro de lectura cuando el diagnostico esta activo y lo suelta al desactivarlo;
 * apagado no cuesta nada (quien registra comprueba [on] antes de construir el mensaje).
 *
 * Sirve para ver, en el modo MIXTO, que texto sirve [PdfBook.pageText] en cada peticion (de la
 * cache o recomputado, nativo/mixto/OCR, con que instancia y longitud): la clave para entender
 * por que a veces conviven dos versiones del texto de una misma pagina.
 */
object PdfDiag {
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** true si hay registro conectado (comprobar antes de construir un mensaje caro). */
    val on: Boolean get() = sink != null

    fun log(msg: String) {
        sink?.invoke(msg)
    }
}
