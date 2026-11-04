# Reglas R8 que este modulo exporta a quien lo use.

# OCR (Tesseract4Android): el codigo nativo accede por nombre a campos y metodos Java
# (nativeData, onProgressValues, constructores de Pix/ResultIterator...). La libreria no
# trae reglas propias: se conservan enteras.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
