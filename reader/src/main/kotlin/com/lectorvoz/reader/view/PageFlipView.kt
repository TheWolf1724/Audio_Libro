package com.lectorvoz.reader.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.withMatrix
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AnimationUtils
import com.lectorvoz.core.model.PageAnimation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Vista de paginas con animaciones de paso: CURL (drawBitmapMesh), SLIDE, COVER, FADE y
 * NONE. El arrastre sigue al dedo y al soltar se completa o se cancela segun distancia y
 * velocidad ([TurnMath]). Toques: izquierda = anterior, derecha = siguiente, centro = menu.
 * Pulsacion larga = "leer desde aqui".
 *
 * Rendimiento: `onDraw` no crea objetos; Paint, Matrix, shaders y arrays de malla se
 * reutilizan. Los bitmaps los da un [PageProvider] (cache del controlador).
 */
class PageFlipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), PageHost {

    var provider: PageProvider? = null
        set(value) { field = value; invalidate() }

    var listener: PageViewListener? = null

    /** Animacion de paso de pagina. */
    var animation: PageAnimation = PageAnimation.CURL

    /** Duracion de una animacion completa en ms. */
    var animationDurationMs: Int = 350

    /** Tocar los bordes pasa pagina (si no, todo toque es "centro"). */
    var tapZonesTurnPages: Boolean = true

    /** Teclas de volumen pasan pagina. */
    var volumeKeysTurnPages: Boolean = false

    override var currentKey: PageKey? = null
        private set

    // Estado de la animacion.
    private enum class State { IDLE, DRAGGING, SETTLING }
    private var state = State.IDLE
    private var forward = true
    private var targetKey: PageKey? = null
    /** Fraccion del paso 0..1 (1 = pagina pasada). */
    private var turn = 0f
    private val settle = Settle()

    // Gesto.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var dragging = false
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var blockedDrag = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        val key = currentKey ?: return@Runnable
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onLongPress(key, downX, downY)
    }

    // Curl.
    private val mesh = CurlMesh()
    private val touchPt = FloatArray(2)
    private var cornerY = 0f
    private var releaseDy = 0f
    private val density = resources.displayMetrics.density

    // Dibujo.
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowMatrix = Matrix()
    private var shadowColor = 0x33000000
    private var pageBackColor = -0x1

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setColors(pageBackColor, shadowColor)
    }

    /**
     * Colores del reverso de la hoja (curl) y de la sombra del borde. El reverso se pinta
     * con la propia pagina muy desvaida hacia [pageBack] (se adivina el texto en espejo).
     */
    fun setColors(pageBack: Int, shadow: Int) {
        pageBackColor = pageBack
        shadowColor = shadow
        val r = (pageBack shr 16 and 0xFF) * 0.85f
        val g = (pageBack shr 8 and 0xFF) * 0.85f
        val b = (pageBack and 0xFF) * 0.85f
        backPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.15f, 0f, 0f, 0f, r,
                    0f, 0.15f, 0f, 0f, g,
                    0f, 0f, 0.15f, 0f, b,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        // Degradado unitario (0..100 en x); se escala y orienta con shadowMatrix.
        shadowPaint.shader = LinearGradient(0f, 0f, 100f, 0f, shadow, shadow and 0x00FFFFFF, Shader.TileMode.CLAMP)
        invalidate()
    }

    // ---------------------------------------------------------------- PageHost

    /**
     * Muestra [key] por orden del controlador (que ya la considera la pagina actual: aqui no
     * se avisa con `onPageShown`). Si ya se esta yendo a [key] no se hace nada, para no
     * reiniciar ni encadenar dos pasos cuando la voz pide la misma pagina varias veces
     * durante una animacion.
     */
    override fun showPage(key: PageKey, animated: Boolean) {
        if (key == destinationKey()) return
        finishAnimationNow(notify = false)
        val p = provider
        val cur = currentKey
        if (animated && p != null && cur != null && animationsOn()) {
            if (p.neighbor(cur, 1) == key) { startTurn(true, key); return }
            if (p.neighbor(cur, -1) == key) { startTurn(false, key); return }
        }
        currentKey = key
        invalidate()
    }

    override fun resetPages(key: PageKey) {
        finishAnimationNow(notify = false)
        currentKey = key
        invalidate()
    }

    override fun invalidatePages() { invalidate() }

    /** Pagina en la que acabara la vista: el destino de un asentamiento hacia "pasada" o la actual. */
    private fun destinationKey(): PageKey? =
        if (state == State.SETTLING && settle.to >= 1f) targetKey ?: currentKey else currentKey

    override fun onPageReady(key: PageKey) {
        if (key == currentKey || key == targetKey) invalidate()
    }

    override fun onPagesChanged() { invalidate() }

    override fun setPageDescription(text: CharSequence, announce: Boolean) {
        contentDescription = text
        if (announce) {
            @Suppress("DEPRECATION")
            announceForAccessibility(text)
        }
    }

    override fun nextPage(animated: Boolean): Boolean = turn(true, animated)

    override fun prevPage(animated: Boolean): Boolean = turn(false, animated)

    private fun turn(fwd: Boolean, animated: Boolean): Boolean {
        finishAnimationNow(notify = true)
        val p = provider ?: return false
        val cur = currentKey ?: return false
        val target = p.neighbor(cur, if (fwd) 1 else -1) ?: return false
        if (!animated || !animationsOn()) {
            switchTo(target)
            return true
        }
        startTurn(fwd, target)
        return true
    }

    /** Animacion programada (sin dedo) de la pagina actual a [target]. */
    private fun startTurn(fwd: Boolean, target: PageKey) {
        forward = fwd
        targetKey = target
        cornerY = height.toFloat()
        releaseDy = 0f
        turn = 0f
        settle.start(0f, 1f, animationDurationMs.toLong(), now())
        state = State.SETTLING
        postInvalidateOnAnimation()
    }

    private fun animationsOn(): Boolean =
        animation != PageAnimation.NONE && animationDurationMs > 0 && ValueAnimator.areAnimatorsEnabled()

    private fun switchTo(key: PageKey, fromDraw: Boolean = false) {
        currentKey = key
        targetKey = null
        state = State.IDLE
        turn = 0f
        if (fromDraw) {
            // No avisar dentro de onDraw: el controlador pide renders y crea bitmaps.
            post { if (currentKey == key) listener?.onPageShown(key) }
        } else {
            invalidate()
            listener?.onPageShown(key)
        }
    }

    private fun finishAnimationNow(notify: Boolean) {
        if (state == State.IDLE) return
        // Un asentamiento hacia "pasada" se da por terminado; un arrastre se cancela.
        val complete = state == State.SETTLING && settle.to >= 1f
        val target = targetKey
        settle.stop()
        state = State.IDLE
        dragging = false
        targetKey = null
        turn = 0f
        if (complete && target != null) {
            currentKey = target
            if (notify) listener?.onPageShown(target)
        }
        invalidate()
    }

    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    // ---------------------------------------------------------------- gestos

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (provider == null || currentKey == null) return super.onTouchEvent(event)
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == State.SETTLING) finishAnimationNow(notify = true)
                vt.clear(); vt.addMovement(event)
                activePointer = event.getPointerId(0)
                downX = event.x; downY = event.y
                fingerX = downX; fingerY = downY
                dragging = false; blockedDrag = false; longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Tras la pulsacion larga ("leer desde aqui") el dedo ya no pasa pagina.
                if (longPressed) return true
                // Solo cuenta el primer dedo: un segundo dedo no hace saltar el pliegue.
                val idx = event.findPointerIndex(activePointer)
                if (idx < 0) return true
                val ex = event.getX(idx)
                val ey = event.getY(idx)
                fingerX = ex; fingerY = ey
                val dx = ex - downX
                val dy = ey - downY
                if (!dragging && !blockedDrag && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    removeCallbacks(longPress)
                    val fwd = dx < 0
                    val target = provider?.neighbor(currentKey!!, if (fwd) 1 else -1)
                    if (target == null) {
                        blockedDrag = true
                    } else {
                        dragging = true
                        forward = fwd
                        targetKey = target
                        cornerY = if (downY < height / 2f) 0f else height.toFloat()
                        state = State.DRAGGING
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                } else if (!dragging && (abs(dy) > touchSlop || abs(dx) > touchSlop)) {
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    turn = dragTurn()
                    if (animation != PageAnimation.NONE) invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Si se levanta el dedo que manda, se suelta la hoja como en ACTION_UP.
                if (event.getPointerId(event.actionIndex) == activePointer) {
                    removeCallbacks(longPress)
                    if (dragging) release(vt, event.getX(event.actionIndex))
                    dragging = false
                    blockedDrag = true // el resto del gesto no es un toque
                    activePointer = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val idx = event.findPointerIndex(activePointer)
                val ux = if (idx >= 0) event.getX(idx) else fingerX
                if (dragging) {
                    release(vt, ux)
                } else if (!longPressed && !blockedDrag && idx >= 0) {
                    onTap(ux)
                    performClick()
                }
                dragging = false
                activePointer = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) startSettle(0f)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** Suelta la hoja en [x]: completa o cancela segun distancia y velocidad. */
    private fun release(vt: VelocityTracker, x: Float) {
        vt.computeCurrentVelocity(1000)
        val progress = TurnMath.dragProgress(downX, x, width.toFloat(), forward)
        val complete = TurnMath.shouldComplete(progress, vt.getXVelocity(activePointer), forward, minFling)
        startSettle(if (complete) 1f else 0f)
    }

    private fun dragTurn(): Float {
        val w = width.toFloat()
        return if (animation == PageAnimation.CURL && forward) {
            CurlMesh.progressForTouchX(fingerX + (w - downX), w, maxRadius())
        } else {
            TurnMath.dragProgress(downX, fingerX, w, forward)
        }
    }

    private fun startSettle(to: Float) {
        if (!animationsOn()) {
            val target = targetKey
            state = State.IDLE
            targetKey = null
            turn = 0f
            if (to >= 1f && target != null) switchTo(target) else invalidate()
            return
        }
        if (animation == PageAnimation.CURL) {
            // Diferencia entre el dedo y la trayectoria programada; se desvanece al asentar.
            val q = if (forward) turn else 1f - turn
            CurlMesh.touchForProgress(q, width.toFloat(), height.toFloat(), cornerY, maxRadius(), lift(), touchPt)
            releaseDy = if (forward) clampFingerY() - touchPt[1] else 0f
        }
        settle.start(turn, to, TurnMath.settleDuration(turn, to, animationDurationMs), now())
        state = State.SETTLING
        postInvalidateOnAnimation()
    }

    private fun onTap(x: Float) {
        if (!tapZonesTurnPages) { listener?.onCenterTap(); return }
        when (TurnMath.tapZone(x, width.toFloat())) {
            TapZone.LEFT -> prevPage(true)
            TapZone.RIGHT -> nextPage(true)
            TapZone.CENTER -> listener?.onCenterTap()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysTurnPages) { nextPage(true); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysTurnPages) { prevPage(true); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> { nextPage(true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { prevPage(true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- accesibilidad

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        // ACTION_PAGE_* son de API 29; en Android 8-9 (minSdk 26) accederlas crashearia.
        // SCROLL_FORWARD/BACKWARD ya permiten pasar pagina con TalkBack en esas versiones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        android.R.id.accessibilityActionPageRight -> nextPage(false)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        android.R.id.accessibilityActionPageLeft -> prevPage(false)
        else -> super.performAccessibilityAction(action, arguments)
    }

    // ---------------------------------------------------------------- dibujo

    private fun maxRadius(): Float = width / 9f
    private fun lift(): Float = height * 0.06f

    private fun clampFingerY(): Float = fingerY.coerceIn(0f, height.toFloat())

    override fun onDraw(canvas: Canvas) {
        drawContent(canvas)
        listener?.onFrameDrawn()
    }

    private fun drawContent(canvas: Canvas) {
        val p = provider
        val cur = currentKey
        if (p == null || cur == null) {
            canvas.drawColor(p?.placeholderColor ?: pageBackColor)
            return
        }
        if (state == State.SETTLING) {
            val t = now()
            turn = settle.value(t)
            if (settle.isFinished(t)) {
                val complete = settle.to >= 1f
                val target = targetKey
                settle.stop()
                if (complete && target != null) {
                    switchTo(target, fromDraw = true)
                } else {
                    state = State.IDLE; targetKey = null; turn = 0f
                }
                drawFull(canvas, p, currentKey!!)
                return
            }
            postInvalidateOnAnimation()
        }
        val target = targetKey
        if (state == State.IDLE || target == null || animation == PageAnimation.NONE) {
            drawFull(canvas, p, cur)
            return
        }
        when (animation) {
            PageAnimation.SLIDE -> drawSlide(canvas, p, cur, target)
            PageAnimation.COVER -> drawCover(canvas, p, cur, target)
            PageAnimation.FADE -> drawFade(canvas, p, cur, target)
            PageAnimation.CURL -> drawCurl(canvas, p, cur, target)
            PageAnimation.NONE -> drawFull(canvas, p, cur)
        }
    }

    private fun drawFull(canvas: Canvas, p: PageProvider, key: PageKey) {
        drawPage(canvas, p, key, 0f, 0f, bitmapPaint)
    }

    private fun drawPage(canvas: Canvas, p: PageProvider, key: PageKey, left: Float, top: Float, paint: Paint) {
        val bmp: Bitmap? = p.bitmap(key)
        val hasBitmap = bmp != null && !bmp.isRecycled
        if (!hasBitmap || bmp!!.width < width || bmp.height < height) {
            // Fondo con la misma opacidad que la pagina (en FADE no tapa la de debajo).
            placeholderPaint.color = p.placeholderColor
            placeholderPaint.alpha = paint.alpha
            canvas.drawRect(left, top, left + width, top + height, placeholderPaint)
        }
        if (hasBitmap) {
            canvas.drawBitmap(bmp!!, left, top, paint)
            // Resaltado de la voz encima (no durante un fundido: iria sin la transparencia).
            if (paint.alpha == 255) p.overlay(key)?.draw(canvas, left, top)
        }
    }

    private fun drawSlide(canvas: Canvas, p: PageProvider, cur: PageKey, target: PageKey) {
        val w = width.toFloat()
        if (forward) {
            drawPage(canvas, p, cur, -turn * w, 0f, bitmapPaint)
            drawPage(canvas, p, target, w - turn * w, 0f, bitmapPaint)
        } else {
            drawPage(canvas, p, target, -w + turn * w, 0f, bitmapPaint)
            drawPage(canvas, p, cur, turn * w, 0f, bitmapPaint)
        }
    }

    private fun drawCover(canvas: Canvas, p: PageProvider, cur: PageKey, target: PageKey) {
        val w = width.toFloat()
        // La hoja de arriba se desliza y descubre (o tapa) la de abajo.
        val topKey = if (forward) cur else target
        val bottomKey = if (forward) target else cur
        val x = if (forward) -turn * w else -w + turn * w
        drawPage(canvas, p, bottomKey, 0f, 0f, bitmapPaint)
        drawPage(canvas, p, topKey, x, 0f, bitmapPaint)
        drawEdgeShadow(canvas, x + w, 0f, 1f, 0f, 16f * density)
    }

    private fun drawFade(canvas: Canvas, p: PageProvider, cur: PageKey, target: PageKey) {
        drawPage(canvas, p, cur, 0f, 0f, bitmapPaint)
        fadePaint.alpha = (turn * 255f).toInt().coerceIn(0, 255)
        drawPage(canvas, p, target, 0f, 0f, fadePaint)
    }

    private fun drawCurl(canvas: Canvas, p: PageProvider, cur: PageKey, target: PageKey) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sheetKey = if (forward) cur else target
        val underKey = if (forward) target else cur
        val q = if (forward) turn else 1f - turn
        val r = maxRadius()
        if (state == State.DRAGGING && forward) {
            // La esquina sigue al dedo (manteniendo la distancia inicial al borde).
            touchPt[0] = fingerX + (w - downX)
            touchPt[1] = clampFingerY()
        } else {
            CurlMesh.touchForProgress(q, w, h, cornerY, r, lift(), touchPt)
            if (state == State.SETTLING && releaseDy != 0f) {
                touchPt[1] += releaseDy * (1f - settle.fraction(now()))
            }
        }
        // El papel no se estira: el punto no puede alejarse mas de w del lomo.
        val sx = touchPt[0]
        val sy = touchPt[1] - cornerY
        val dist = sqrt(sx * sx + sy * sy)
        if (dist > w && dist > 0f) {
            touchPt[0] = sx * w / dist
            touchPt[1] = cornerY + sy * w / dist
        }

        drawPage(canvas, p, underKey, 0f, 0f, bitmapPaint)
        val sheet = p.bitmap(sheetKey)
        if (sheet == null || sheet.isRecycled) return
        if (!mesh.compute(w, h, touchPt[0], touchPt[1], w, cornerY, r)) {
            canvas.drawBitmap(sheet, 0f, 0f, bitmapPaint)
            return
        }
        // Sombra que el pliegue proyecta sobre la pagina de debajo.
        drawEdgeShadow(canvas, mesh.edgeX, mesh.edgeY, mesh.dirX, mesh.dirY, mesh.radius * 1.5f + 8f * density)
        canvas.drawBitmapMesh(sheet, mesh.meshWidth, mesh.meshHeight, mesh.front, 0, mesh.frontColors, 0, bitmapPaint)
        canvas.drawBitmapMesh(sheet, mesh.meshWidth, mesh.meshHeight, mesh.back, 0, mesh.backColors, 0, backPaint)
    }

    /** Banda de sombra que nace en (x, y) y se desvanece en la direccion (dx, dy). */
    private fun drawEdgeShadow(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
        if (size <= 0f) return
        val diag = (width + height).toFloat()
        val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        shadowMatrix.setScale(size / 100f, 1f)
        shadowMatrix.postRotate(deg)
        shadowMatrix.postTranslate(x, y)
        canvas.withMatrix(shadowMatrix) {
            drawRect(0f, -diag, 100f, diag, shadowPaint)
        }
    }
}
