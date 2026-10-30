package com.lectorvoz.reader.layout

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.lectorvoz.core.model.DocumentSource
import kotlin.math.roundToInt

/** Tamano en pixeles. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Imagenes del libro. La maquetacion solo necesita el tamano ([size], sin decodificar);
 * el bitmap se decodifica al dibujar la pagina ([load]) y al tamano exacto de destino.
 */
interface ImageLoader {
    /** Tamano natural de la imagen [href] (solo cabecera), o null si no existe o esta danada. */
    suspend fun size(href: String): ImageSize?

    /** Bitmap de [href] escalado a [width] x [height] pixeles (o null). */
    suspend fun load(href: String, width: Int, height: Int): Bitmap?
}

/**
 * [ImageLoader] sobre [DocumentSource.resource]: lee la cabecera para el tamano y decodifica
 * con `inSampleSize` + escalado del decodificador para obtener exactamente el tamano
 * mostrado (no hasta 4x pixeles de mas). Las JPEG (sin alfa) se decodifican en RGB_565.
 */
class DocumentImageLoader(private val source: DocumentSource) : ImageLoader {
    override suspend fun size(href: String): ImageSize? {
        val bytes = source.resource(href) ?: return null
        return decodeBounds(bytes)
    }

    override suspend fun load(href: String, width: Int, height: Int): Bitmap? {
        val bytes = source.resource(href) ?: return null
        return decodeScaled(bytes, width, height)
    }

    companion object {
        /** Tamano de la imagen sin decodificar pixeles. */
        fun decodeBounds(bytes: ByteArray): ImageSize? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return ImageSize(bounds.outWidth, bounds.outHeight)
        }

        /** Decodifica [bytes] a [width] x [height] (aprox. +-1 px); null si esta danada. */
        fun decodeScaled(bytes: ByteArray, width: Int, height: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || width <= 0 || height <= 0) return null
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
            val opaque = bounds.outMimeType == "image/jpeg"
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                val sampledW = bounds.outWidth / sample
                if (sampledW > width) {
                    // El decodificador escala de inDensity a inTargetDensity sin bitmap intermedio.
                    inScaled = true
                    inDensity = sampledW
                    inTargetDensity = width
                }
            }
            val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
                ?: return null
            // Ajuste fino si el decodificador no escalo (formatos sin soporte) o quedo grande.
            if (decoded.width <= width + 1 && decoded.height <= height + 1) return decoded
            val scaled = runCatching { decoded.scale(width, height) }.getOrNull()
            if (scaled != null && scaled !== decoded) decoded.recycle() // intermedio, nunca mostrado
            return scaled ?: decoded
        }

        /**
         * Mayor potencia de 2 que no reduce la imagen por debajo del tamano con el que se
         * mostrara (ajustada dentro de [maxWidth] x [maxHeight]).
         */
        fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
            if (maxWidth <= 0 || maxHeight <= 0 || width <= 0 || height <= 0) return 1
            val fit = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
            if (fit >= 1f) return 1
            var sample = 1
            while (sample * 2 <= 1f / fit) sample *= 2
            return sample
        }
    }
}

/** Calculo puro del tamano con el que se muestra una imagen en la columna. */
object ImageFit {
    /**
     * Tamano en pixeles de pantalla de una imagen de tamano natural [natural] (pixeles CSS),
     * con ancho/alto declarados opcionales ([declaredW], [declaredH], en pixeles CSS), a
     * [density] px por dp, sin superar [maxW] x [maxH] y sin ampliar. Conserva la proporcion.
     */
    fun fit(natural: ImageSize?, declaredW: Int?, declaredH: Int?, density: Float, maxW: Int, maxH: Int): ImageSize? {
        val w0: Float
        val h0: Float
        when {
            declaredW != null && declaredH != null && declaredW > 0 && declaredH > 0 -> { w0 = declaredW.toFloat(); h0 = declaredH.toFloat() }
            natural == null -> return null
            declaredW != null && declaredW > 0 -> { w0 = declaredW.toFloat(); h0 = declaredW * natural.height.toFloat() / natural.width }
            declaredH != null && declaredH > 0 -> { h0 = declaredH.toFloat(); w0 = declaredH * natural.width.toFloat() / natural.height }
            else -> { w0 = natural.width.toFloat(); h0 = natural.height.toFloat() }
        }
        val natW = w0 * density
        val natH = h0 * density
        if (!(natW > 0f) || !(natH > 0f) || maxW <= 0 || maxH <= 0) return null
        val f = minOf(maxW / natW, maxH / natH, 1f)
        return ImageSize((natW * f).roundToInt().coerceIn(1, maxW), (natH * f).roundToInt().coerceIn(1, maxH))
    }
}
