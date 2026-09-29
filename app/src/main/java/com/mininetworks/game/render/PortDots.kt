package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.World
import kotlin.math.hypot

/**
 * A node's cable ports as a small row of dots on a frosted pill, upright below the node in both styles: one dot per
 * port ([Node.maxPorts]), filled ink while a cable uses it, a hollow ring while free, so the map says that a PC takes
 * only 2 cables and a router 6. Calm by default and hidden below [MIN_UNIT_DP] of zoom; while a cable is dragged
 * ([emphasis]) the dragged node and the one under the finger show theirs larger with the free ports in blue, and every
 * node with no free port gets red dots at any zoom, with a red ring only where it matters: on the node under the
 * finger and on those within [RING_RADIUS_CELLS] of it, so a busy map stays calm.
 */
object PortDots {
    /**
     * How loud a row is: [CALM] on the map, [OPEN] at the ends of a dragged cable, [FULL] for a node a drag cannot use
     * near the finger (red dots and ring), [FULL_QUIET] for one farther off (red dots only).
     */
    enum class Emphasis { CALM, OPEN, FULL, FULL_QUIET }

    /** Distance (cells) from the finger within which a full node also gets its red ring during a drag. */
    const val RING_RADIUS_CELLS = 3f

    private const val INK = 0xFF3A4350.toInt()
    private const val FREE = 0xFFFFFFFF.toInt()
    /** The tutorial's highlight blue: free ports read the same with the colourblind palette. */
    private const val OPEN_RING = 0xFF2F7BF6.toInt()
    private const val FULL_RED = 0xFFD7263D.toInt()

    /** [Renderer.unitPx] in dp below which the calm rows hide, so a zoomed-out map stays quiet. */
    const val MIN_UNIT_DP = 24f

    private val fillP = fill(0)
    private val ringP = stroke(0)
    private val pill = RectF()

    /** True if calm rows show at a zoom of [unitPx] ([Renderer.unitPx]). */
    fun visible(unitPx: Float, density: Float) = unitPx >= MIN_UNIT_DP * density

    /**
     * How [n] shows its ports right now, or null for no row: with no [drag], [CALM] when [visible]; during a drag,
     * a node without a free port is [FULL] if it is the target, the start or within [RING_RADIUS_CELLS] of the finger
     * and [FULL_QUIET] otherwise, [OPEN] for the drag's start and target, [CALM] for the rest.
     */
    fun emphasis(world: World, n: Node, drag: DragPreview?, show: Boolean): Emphasis? {
        if (drag != null) {
            if (world.ports(n) >= n.maxPorts) {
                val c = n.footprintCenter
                val near = n === drag.target || n === drag.from || hypot(c.x - drag.end.x, c.y - drag.end.y) <= RING_RADIUS_CELLS
                return if (near) Emphasis.FULL else Emphasis.FULL_QUIET
            }
            if (n === drag.from || n === drag.target) return Emphasis.OPEN
        }
        return if (show) Emphasis.CALM else null
    }

    /**
     * The row of [n]'s ports centred at ([cx], [cy]) at a zoom of [unitPx] ([Renderer.unitPx]); with [Emphasis.FULL] also a red
     * ring of radii [ringRx], [ringRy] around ([nodeX], [nodeY]) (an ellipse on the iso ground).
     */
    fun draw(
        canvas: Canvas, world: World, n: Node, cx: Float, cy: Float, unitPx: Float, density: Float, emphasis: Emphasis,
        nodeX: Float = cx, nodeY: Float = cy, ringRx: Float = 0f, ringRy: Float = 0f,
    ) {
        val max = n.maxPorts
        val used = world.ports(n).coerceAtMost(max)
        val base = (unitPx * 0.045f).coerceIn(1.8f * density, 3.2f * density)
        val r = if (emphasis == Emphasis.CALM || emphasis == Emphasis.FULL_QUIET) base else maxOf(base * 1.35f, 3f * density)
        val step = r * 2.75f
        val pad = r * 0.95f
        val w = (max - 1) * step + 2 * r + 2 * pad
        val h = 2 * r + 2 * pad
        if (emphasis == Emphasis.FULL && ringRx > 0f) {
            pill.set(nodeX - ringRx, nodeY - ringRy, nodeX + ringRx, nodeY + ringRy)
            ringP.color = 0xB3FFFFFF.toInt(); ringP.strokeWidth = 5f * density
            canvas.drawOval(pill, ringP)
            ringP.color = FULL_RED; ringP.strokeWidth = 2.5f * density
            canvas.drawOval(pill, ringP)
        }
        pill.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fillP.color = if (emphasis == Emphasis.CALM || emphasis == Emphasis.FULL_QUIET) 0xB3FFFFFF.toInt() else 0xF2FFFFFF.toInt()
        canvas.drawRoundRect(pill, h / 2f, h / 2f, fillP)
        if (emphasis != Emphasis.CALM && emphasis != Emphasis.FULL_QUIET) {
            ringP.color = if (emphasis == Emphasis.FULL) FULL_RED else OPEN_RING
            ringP.strokeWidth = maxOf(1f * density, r * 0.3f)
            canvas.drawRoundRect(pill, h / 2f, h / 2f, ringP)
        }
        val ring = maxOf(0.9f * density, r * 0.42f)
        var x = cx - (max - 1) * step / 2f
        for (i in 0 until max) {
            if (i < used) {
                fillP.color = if (emphasis == Emphasis.FULL || emphasis == Emphasis.FULL_QUIET) FULL_RED else INK
                canvas.drawCircle(x, cy, r, fillP)
            } else {
                fillP.color = FREE
                canvas.drawCircle(x, cy, r, fillP)
                ringP.color = if (emphasis == Emphasis.OPEN) OPEN_RING else INK
                ringP.strokeWidth = if (emphasis == Emphasis.OPEN) ring * 1.3f else ring
                canvas.drawCircle(x, cy, r - ringP.strokeWidth / 2f, ringP)
            }
            x += step
        }
    }
}
