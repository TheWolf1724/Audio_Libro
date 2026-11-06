# Reglas R8 de LectorVoz (release).
# Las librerias (Media3, Room, pdfium...) traen sus propias reglas de consumo.

# Conservar numeros de linea para trazas legibles; ocultar el nombre del fichero fuente.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Elimina las llamadas a android.util.Log en release: son trazas de desarrollo y algun
# Log.w(..., e) podria arrastrar rutas/URIs del usuario (p.ej. e.message de un IOException al
# importar) a logcat. El diagnostico de la app NO usa android.util.Log (tiene su propio sumidero
# via TtsConfig.diag), asi que el modo diagnostico no se ve afectado.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
