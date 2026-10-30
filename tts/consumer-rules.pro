# Reglas R8 que este modulo exporta a quien lo use.

# sherpa-onnx: el codigo nativo (JNI) lee los campos de las clases de configuracion por
# nombre (GetFieldID) y crea GeneratedAudio; el AAR 2.0.1 no trae reglas propias.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# El callback de generateWithConfigAndCallback se invoca por JNI con la firma
# invoke([F)Ljava/lang/Integer; (ver SherpaSynthesizer.CancelCallback).
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class com.lectorvoz.tts.neural.SherpaSynthesizer$CancelCallback { *; }

# commons-compress: dependencias opcionales de formatos que no usamos (solo bzip2 + tar).
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn io.airlift.compress.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.framework.**
-dontwarn javax.inject.**
