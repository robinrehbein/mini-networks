package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.DataType
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Packet
import com.mininetworks.game.game.Shape
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World

/** A cable the player is currently dragging. `end` is in world space. */
class DragPreview(val from: Node, val end: Vec2, val target: Node?, val error: String?, val cost: Int?)

/**
 * A visual style. Renderers only read the [World]; they never change it.
 * Every style owns its own projection so input (touch -> world) always matches what is on screen.
 */
interface Renderer {
    val name: String

    fun layout(width: Int, height: Int, world: World)

    /** World units -> screen pixels, on the ground plane. */
    fun toScreen(p: Vec2): Vec2

    /** Screen pixels -> world units, on the ground plane. */
    fun toWorld(sx: Float, sy: Float): Vec2

    /** How a cable is laid out in this style (world space). */
    fun cablePath(a: Node, b: Node): List<Vec2> = Geometry.octo(a.center, b.center)

    fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float)

    /** Size of one world unit on screen, used by the HUD to scale touch targets. */
    val unitPx: Float

    fun packetPosition(p: Packet): Vec2 {
        val pts = cablePath(p.from, p.to)
        return if (p.progress < 0f) p.from.center else Geometry.pointAlong(pts, p.progress)
    }

    fun cableNear(world: World, p: Vec2, radius: Float = 0.35f): Cable? =
        world.cables.minByOrNull { Geometry.distToPolyline(p, cablePath(it.a, it.b)) }
            ?.takeIf { Geometry.distToPolyline(p, cablePath(it.a, it.b)) <= radius }
}

/** Shape helpers shared by all styles. */
object Shapes {
    private val path = Path()

    fun path(shape: Shape, x: Float, y: Float, r: Float): Path {
        path.reset()
        when (shape) {
            Shape.CIRCLE -> path.addCircle(x, y, r, Path.Direction.CW)
            Shape.SQUARE -> { val q = r * 0.88f; path.addRect(x - q, y - q, x + q, y + q, Path.Direction.CW) }
            Shape.TRIANGLE -> {
                val h = r * 1.15f
                path.moveTo(x, y - h)
                path.lineTo(x + h * 0.95f, y + h * 0.7f)
                path.lineTo(x - h * 0.95f, y + h * 0.7f)
                path.close()
            }
        }
        return path
    }

    fun draw(c: Canvas, shape: Shape, x: Float, y: Float, r: Float, paint: Paint) = c.drawPath(path(shape, x, y, r), paint)
}

fun Int.shade(f: Float): Int {
    val r = (this shr 16) and 0xFF
    val g = (this shr 8) and 0xFF
    val b = this and 0xFF
    fun m(v: Int) = if (f < 0) (v * (1 + f)).toInt() else (v + (255 - v) * f).toInt()
    return (0xFF shl 24) or (m(r) shl 16) or (m(g) shl 8) or m(b)
}

fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

fun stroke(color: Int, width: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Fixed game palette per data type. Kept in one place so a colorblind mode can swap it later. */
object TypeColors {
    fun flat(t: DataType) = when (t) {
        DataType.VIDEO -> 0xFFE4572E.toInt()
        DataType.MAIL -> 0xFF2E86AB.toInt()
        DataType.GAME -> 0xFFE9A92B.toInt()
    }

    fun iso(t: DataType) = when (t) {
        DataType.VIDEO -> 0xFFEF6F4A.toInt()
        DataType.MAIL -> 0xFF3E97C4.toInt()
        DataType.GAME -> 0xFFF0B640.toInt()
    }
}
