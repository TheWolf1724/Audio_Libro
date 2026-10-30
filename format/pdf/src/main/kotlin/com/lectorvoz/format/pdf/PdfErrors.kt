package com.lectorvoz.format.pdf

/** Errores al abrir o leer un PDF, con mensaje claro para mostrar al usuario. */
sealed class PdfException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** El PDF esta protegido y no se ha dado contrasena. */
    class PasswordRequired(cause: Throwable? = null) :
        PdfException("El PDF esta protegido con contrasena.", cause)

    /** La contrasena dada no es correcta. */
    class WrongPassword(cause: Throwable? = null) :
        PdfException("La contrasena del PDF no es correcta.", cause)

    /** El fichero no es un PDF valido o esta danado. */
    class Corrupt(detail: String?, cause: Throwable? = null) :
        PdfException("El PDF esta danado o no es un PDF valido" + (detail?.let { ": $it" } ?: "."), cause)

    /** No se pudo leer el fichero (permiso, no existe...). */
    class Unreadable(detail: String?, cause: Throwable? = null) :
        PdfException("No se pudo abrir el fichero" + (detail?.let { ": $it" } ?: "."), cause)

    /** No se pudo cargar la libreria nativa pdfium. */
    class NativeUnavailable(cause: Throwable? = null) :
        PdfException("No se pudo cargar el motor PDF (pdfium).", cause)

    /** El documento ya esta cerrado. */
    class Closed : PdfException("El PDF ya esta cerrado.")
}
