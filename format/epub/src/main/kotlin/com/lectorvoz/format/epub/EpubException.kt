package com.lectorvoz.format.epub

import java.io.IOException

/** Errores al abrir o leer un EPUB. Todos son [IOException] para tratarlos como E/S. */
sealed class EpubException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** El libro tiene recursos cifrados con DRM (de tienda, LCP...): no se puede leer. */
    class Drm(message: String) : EpubException(message)

    /** Contenedor o paquete mal formado (falta el OPF, zip corrupto...). */
    class Invalid(message: String, cause: Throwable? = null) : EpubException(message, cause)

    /** Ruta que sale del contenedor ("../") o absoluta: rechazada por seguridad. */
    class UnsafePath(val path: String) : EpubException("Ruta fuera del contenedor: $path")

    /** Recurso descomprimido mayor que el limite permitido. */
    class TooLarge(val path: String, val limit: Long) :
        EpubException("Recurso demasiado grande (> $limit bytes): $path")

    /** Ratio de compresion sospechoso (posible bomba zip). */
    class ZipBomb(val path: String) : EpubException("Ratio de compresion sospechoso: $path")
}

/**
 * Limites de seguridad al leer el zip.
 *
 * @param maxResourceBytes tamano maximo descomprimido de un recurso (imagenes, fuentes...).
 * @param maxXhtmlBytes tamano maximo descomprimido de un documento de texto (XHTML, OPF,
 *   NCX): se decodifica a String y jsoup crea el DOM, que ocupa varias veces mas en memoria.
 * @param maxCompressionRatio ratio descomprimido/comprimido maximo...
 * @param ratioCheckMinBytes ...que solo se comprueba a partir de este tamano descomprimido.
 * @param maxEntries numero maximo de entradas del zip.
 */
data class EpubLimits(
    val maxResourceBytes: Long = 64L * 1024 * 1024,
    val maxXhtmlBytes: Long = 8L * 1024 * 1024,
    val maxCompressionRatio: Long = 100,
    val ratioCheckMinBytes: Long = 1L * 1024 * 1024,
    val maxEntries: Int = 20_000,
)
