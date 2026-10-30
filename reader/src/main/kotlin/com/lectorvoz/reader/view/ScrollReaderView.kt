package com.lectorvoz.reader.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Modo scroll: `RecyclerView` vertical de paginas ya renderizadas (las mismas que en modo
 * paginas; en PDF cada una con su alto). La lista crece por los extremos segun se acerca
 * el usuario. Toque = menu; pulsacion larga = leer desde aqui.
 */
@SuppressLint("ViewConstructor")
class ScrollReaderView(context: Context) : RecyclerView(context), PageHost {

    var provider: PageProvider? = null
        // Refresco completo intencionado: cambiar de proveedor renueva todas las paginas.
        @Suppress("NotifyDataSetChanged")
        set(value) { field = value; pageAdapter.notifyDataSetChanged() }

    var listener: PageViewListener? = null

    private val keys = ArrayList<PageKey>()
    private val pageAdapter = PagesAdapter()
    private val lm = LinearLayoutManager(context)
    private var reported: PageKey? = null

    /** Desplazamiento pedido por el controlador en curso: no se informan paginas intermedias. */
    private var programmatic = false
    private var extendPosted = false
    private val extendRunnable = Runnable {
        extendPosted = false
        extendNow()
    }

    override val currentKey: PageKey?
        get() {
            val pos = lm.findFirstVisibleItemPosition()
            return if (pos in keys.indices) keys[pos] else keys.firstOrNull()
        }

    init {
        layoutManager = lm
        adapter = pageAdapter
        setHasFixedSize(false)
        itemAnimator = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                extendIfNeeded()
                if (!programmatic) report()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) programmatic = false
                if (newState == SCROLL_STATE_IDLE) {
                    programmatic = false
                    report()
                }
            }
        })
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { listener?.onCenterTap(); return true }
            override fun onLongPress(e: MotionEvent) {
                val child = findChildViewUnder(e.x, e.y) as? PageItemView ?: return
                val key = child.key ?: return
                listener?.onLongPress(key, e.x - child.left, e.y - child.top)
            }
        })
        addOnItemTouchListener(object : SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun report() {
        val k = currentKey
        if (k != null && k != reported) {
            reported = k
            listener?.onPageShown(k)
        }
    }

    override fun showPage(key: PageKey, animated: Boolean) {
        val idx = keys.indexOf(key)
        reported = key
        if (idx >= 0) {
            if (animated) {
                programmatic = true
                smoothScrollToPosition(idx)
            } else {
                lm.scrollToPositionWithOffset(idx, 0)
            }
        } else {
            resetPages(key)
        }
    }

    @Suppress("NotifyDataSetChanged") // reinicio total: la lista de paginas se rehace desde [key].
    override fun resetPages(key: PageKey) {
        stopScroll()
        programmatic = false
        reported = key
        keys.clear()
        keys += key
        pageAdapter.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(0, 0)
        extendIfNeeded()
    }

    override fun invalidatePages() {
        invalidate()
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    override fun draw(c: Canvas) {
        super.draw(c)
        listener?.onFrameDrawn()
    }

    /**
     * Amplia la lista por los extremos. Se aplaza siempre a un mensaje: la lista y su aviso
     * al adaptador cambian juntos y nunca durante un desplazamiento o una maquetacion (si no,
     * RecyclerView detecta la incoherencia y se cierra la app).
     */
    private fun extendIfNeeded() {
        if (extendPosted) return
        extendPosted = true
        post(extendRunnable)
    }

    private fun extendNow() {
        val p = provider ?: return
        if (keys.isEmpty()) return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(0)
        if (last >= keys.size - 2) {
            val next = p.neighbor(keys.last(), 1)
            if (next != null && next !in keys) {
                keys += next
                pageAdapter.notifyItemInserted(keys.size - 1)
            }
        }
        if (first <= 1) {
            val prev = p.neighbor(keys.first(), -1)
            if (prev != null && prev !in keys) {
                keys.add(0, prev)
                pageAdapter.notifyItemInserted(0)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(extendRunnable)
        extendPosted = false
        super.onDetachedFromWindow()
    }

    override fun onPageReady(key: PageKey) {
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? PageItemView ?: continue
            if (c.key == key) { c.requestLayout(); c.invalidate() }
        }
    }

    /** Solo repintar las paginas visibles: ni pedir bitmaps ni volver a medir. */
    override fun onOverlayChanged() {
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    override fun onPagesChanged() {
        extendIfNeeded()
        for (i in 0 until childCount) getChildAt(i).requestLayout()
    }

    override fun setPageDescription(text: CharSequence, announce: Boolean) {
        contentDescription = text
    }

    override fun nextPage(animated: Boolean): Boolean {
        val pos = lm.findFirstVisibleItemPosition()
        if (pos + 1 !in keys.indices) return false
        if (animated) smoothScrollToPosition(pos + 1) else lm.scrollToPositionWithOffset(pos + 1, 0)
        return true
    }

    override fun prevPage(animated: Boolean): Boolean {
        val pos = lm.findFirstVisibleItemPosition()
        if (pos - 1 !in keys.indices) return false
        if (animated) smoothScrollToPosition(pos - 1) else lm.scrollToPositionWithOffset(pos - 1, 0)
        return true
    }

    /** Claves visibles (para fijarlas en la cache). */
    fun visibleKeys(): List<PageKey> {
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < first) return emptyList()
        return (first..last).mapNotNull { keys.getOrNull(it) }
    }

    private inner class PagesAdapter : Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            object : ViewHolder(PageItemView(parent.context, this@ScrollReaderView)) {}

        override fun getItemCount(): Int = keys.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            (holder.itemView as PageItemView).key = keys[position]
            holder.itemView.requestLayout()
            holder.itemView.invalidate()
        }
    }

    /** Elemento de la lista: dibuja el bitmap de su pagina escalado al ancho. */
    private class PageItemView(context: Context, private val host: ScrollReaderView) : View(context) {
        var key: PageKey? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val placeholder = Paint()
        private val dst = Rect()

        init {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val k = key
            val p = host.provider
            val viewH = host.height.takeIf { it > 0 } ?: w
            val h = if (k != null && p != null) p.pageHeight(k, w, viewH) else viewH
            setMeasuredDimension(w, h.coerceAtLeast(1))
        }

        override fun onDraw(canvas: Canvas) {
            val k = key ?: return
            val p = host.provider ?: return
            val bmp = p.bitmap(k)
            if (bmp == null || bmp.isRecycled) {
                placeholder.color = p.placeholderColor
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholder)
                return
            }
            dst.set(0, 0, width, height)
            canvas.drawBitmap(bmp, null, dst, paint)
            p.overlay(k)?.draw(canvas, 0f, 0f, width / bmp.width.toFloat(), height / bmp.height.toFloat())
        }
    }
}
