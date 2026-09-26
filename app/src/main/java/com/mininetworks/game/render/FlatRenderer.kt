package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Shape
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World

/** Style A from design/style-explorations.html: Mini-Metro-like, flat vector, 45° cables. */
class FlatRenderer : Renderer {
    override val name = "Flat"

    private val land = 0xFFF3F1EC.toInt()
    private val waterColor = 0xFFC3DCE8.toInt()
    private val cableColor = 0xFF39424E.toInt()
    private val ink = 0xFF262B33.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private var cell = 1f
    private var ox = 0f
    private var oy = 0f
    override val unitPx get() = cell

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    private val path = Path()
    private val arcRect = RectF()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    override fun layout(width: Int, height: Int, world: World) {
        cell = minOf(width / (world.cols + 0.6f), height / (world.rows + 2.2f))
        ox = (width - world.cols * cell) / 2f
        oy = (height - world.rows * cell) / 2f + cell * 0.2f
    }

    override fun toScreen(p: Vec2) = Vec2(ox + p.x * cell, oy + p.y * cell)
    override fun toWorld(sx: Float, sy: Float) = Vec2((sx - ox) / cell, (sy - oy) / cell)

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        canvas.drawColor(land)
        drawRiver(canvas, world)

        for (c in world.cables) {
            polyline(cablePath(c.a, c.b))
            strokeP.color = land; strokeP.strokeWidth = cell * 0.3f; canvas.drawPath(path, strokeP)
            val load = world.cableLoad(c)
            strokeP.color = if (load >= c.capacity) cableColor.shade(0.25f) else cableColor
            strokeP.strokeWidth = cell * 0.15f
            canvas.drawPath(path, strokeP)
        }

        drag?.let { d ->
            val end = d.target?.center ?: d.end
            polyline(Geometry.octo(d.from.center, end))
            strokeP.color = if (d.error == null) cableColor.shade(0.35f) else alarm
            strokeP.strokeWidth = cell * 0.15f
            canvas.drawPath(path, strokeP)
            val s = toScreen(end)
            labelP.textSize = cell * 0.36f
            labelP.color = strokeP.color
            canvas.drawText(d.error ?: "${d.cost} Kabel", s.x, s.y - cell * 0.6f, labelP)
        }

        for (p in world.packets) {
            val s = toScreen(packetPosition(p))
            fillP.color = TypeColors.flat(p.type)
            Shapes.draw(canvas, p.type.shape, s.x, s.y, cell * 0.13f, fillP)
        }

        for (n in world.nodes) {
            val s = toScreen(n.center)
            when (n.kind) {
                NodeKind.ROUTER -> {
                    fillP.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(s.x, s.y, cell * 0.22f, fillP)
                    strokeP.color = ink; strokeP.strokeWidth = cell * 0.08f; canvas.drawCircle(s.x, s.y, cell * 0.22f, strokeP)
                }
                NodeKind.SERVER -> {
                    val t = n.type!!
                    fillP.color = TypeColors.flat(t); Shapes.draw(canvas, t.shape, s.x, s.y, cell * 0.42f, fillP)
                    fillP.color = 0xFFFFFFFF.toInt()
                    Shapes.draw(canvas, t.shape, s.x, s.y + if (t.shape == Shape.TRIANGLE) cell * 0.05f else 0f, cell * 0.16f, fillP)
                }
                NodeKind.CLIENT -> {
                    val t = n.type!!
                    fillP.color = 0xFFFFFFFF.toInt(); Shapes.draw(canvas, t.shape, s.x, s.y, cell * 0.26f, fillP)
                    strokeP.color = ink; strokeP.strokeWidth = cell * 0.08f; Shapes.draw(canvas, t.shape, s.x, s.y, cell * 0.26f, strokeP)
                    fillP.color = TypeColors.flat(t)
                    for (i in 0 until minOf(n.pending, 8)) {
                        Shapes.draw(canvas, t.shape, s.x + cell * (0.5f + (i % 4) * 0.22f), s.y - cell * 0.12f + (i / 4) * cell * 0.24f, cell * 0.08f, fillP)
                    }
                    if (n.overload > 0f) {
                        arcRect.set(s.x - cell * 0.46f, s.y - cell * 0.46f, s.x + cell * 0.46f, s.y + cell * 0.46f)
                        strokeP.color = alarm; strokeP.strokeWidth = cell * 0.07f
                        canvas.drawArc(arcRect, -90f, 360f * n.overload, false, strokeP)
                    }
                }
            }
        }

        world.failedNode?.let {
            val s = toScreen(it.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.05f
            canvas.drawCircle(s.x, s.y, cell * (0.7f + 0.15f * kotlin.math.sin(time * 6f)), strokeP)
        }
    }

    private fun drawRiver(canvas: Canvas, world: World) {
        val centers = (0 until world.rows).mapNotNull { y ->
            (0 until world.cols).firstOrNull { world.water[y][it] }?.let { Vec2(it + 0.5f, y + 0.5f) }
        }
        if (centers.isEmpty()) return
        val pts = listOf(Vec2(centers.first().x, -0.6f)) + centers + Vec2(centers.last().x, world.rows + 0.6f)
        path.reset()
        val first = toScreen(pts[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until pts.size - 1) {
            val c = toScreen(pts[i])
            val m = toScreen(Vec2((pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2))
            path.quadTo(c.x, c.y, m.x, m.y)
        }
        val last = toScreen(pts.last()); path.lineTo(last.x, last.y)
        strokeP.color = waterColor; strokeP.strokeWidth = cell * 1.0f
        canvas.drawPath(path, strokeP)
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> val s = toScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
    }
}
