package fr.babouch.echecs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.hypot

/** Calque transparent plein écran qui dessine le cadre de l'échiquier détecté et les flèches des coups. */
class ArrowView(context: Context) : View(context) {
    class Arrow(
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float,
        val color: Int, val width: Float,
    )

    var arrows: List<Arrow> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var board: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    private val loc = IntArray(2)
    private val path = Path()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0x9900B0FF.toInt()
    }
    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Les coordonnées reçues sont celles de l'écran : on compense la position de la fenêtre.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        board?.let { canvas.drawRect(it, framePaint) }
        // Dessin de la moins bonne à la meilleure, pour que la meilleure soit au-dessus.
        for (a in arrows.asReversed()) drawArrow(canvas, a)
        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, a: Arrow) {
        val dx = a.toX - a.fromX
        val dy = a.toY - a.fromY
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val head = (a.width * 2.6f).coerceAtMost(len * 0.6f)
        val baseX = a.toX - ux * head
        val baseY = a.toY - uy * head
        shaftPaint.color = a.color
        shaftPaint.strokeWidth = a.width
        canvas.drawLine(a.fromX, a.fromY, baseX, baseY, shaftPaint)
        val hw = a.width * 1.6f
        path.reset()
        path.moveTo(a.toX, a.toY)
        path.lineTo(baseX - uy * hw, baseY + ux * hw)
        path.lineTo(baseX + uy * hw, baseY - ux * hw)
        path.close()
        headPaint.color = a.color
        canvas.drawPath(path, headPaint)
    }
}
