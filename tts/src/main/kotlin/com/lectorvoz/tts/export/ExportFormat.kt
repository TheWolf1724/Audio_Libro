package com.lectorvoz.tts.export

/** Formato del audio exportado. */
enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    /** PCM sin comprimir: sencillo y robusto, pero ~5 MB por minuto y limite de 4 GB por fichero. */
    WAV("wav", "audio/wav", "WAV (sin comprimir, unos 5 MB por minuto)"),

    /** AAC en contenedor MP4: unos 10 veces mas pequeno (~0,5 MB por minuto); reproducible en cualquier movil. */
    M4A("m4a", "audio/mp4", "M4A (comprimido, unos 0,5 MB por minuto)"),
}
