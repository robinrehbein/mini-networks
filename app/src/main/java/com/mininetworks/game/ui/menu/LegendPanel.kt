package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Service
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.DeviceIcons
import com.mininetworks.game.render.ProblemBadges
import com.mininetworks.game.render.ServiceGlyphs
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.stroke
import com.mininetworks.game.ui.TextScale
import com.mininetworks.game.ui.TextWrap
import com.mininetworks.game.ui.UiNode

/** What a legend entry shows at its left: the same drawing the map uses for it. */
sealed interface LegendIcon {
    data class Request(val service: Service) : LegendIcon
    data class Response(val service: Service) : LegendIcon
    data class Server(val service: Service) : LegendIcon
    data class OfDevice(val device: Device) : LegendIcon
    data class Cable(val type: CableType) : LegendIcon
    data object Router : LegendIcon
    data object AccessPoint : LegendIcon
    data object CellTower : LegendIcon
    data class Problem(val problem: RouteProblem) : LegendIcon
    data object Overload : LegendIcon
    /** A PC with its row of port dots, one in use and one free, as under every node on the map. */
    data object Ports : LegendIcon
}

/** One entry of the [LegendPanel]; texts are ready to show. [services] are drawn as tokens under the title. */
data class LegendEntry(
    val id: String,
    val icon: LegendIcon,
    val title: String,
    val description: String,
    val services: List<Service> = emptyList(),
    /** Read by screen readers instead of the tokens, e.g. "wants Mail, Gaming". */
    val servicesLabel: String? = null,
)

/** A heading and its entries. */
data class LegendSection(val id: String, val title: String, val entries: List<LegendEntry>)

/**
 * "What's what?": every symbol of the map explained with the very drawing the map uses, grouped into services,
 * devices, the network's parts and the warning signs. Drawn on the game canvas in the look of the achievements screen:
 * a back pill, the title, then per section a heading and a grid of tiles; it scrolls when it does not fit. Only the
 * back pill is tappable; [hit] is valid for the last drawn frame.
 */
class LegendPanel(context: Context) {
    private val scale = TextScale.of(context)
    private val density = scale.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val dim = fill(0xF2F3F1EC.toInt())
    private val fillP = fill(0)
    private val lineP = stroke(0).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val icons = DeviceIcons()
    private val r = RectF()
    private val arc = RectF()
    private val targets = ArrayList<Pair<RectF, String>>()
    private val drawnNodes = ArrayList<UiNode>()
    private val tileBounds = HashMap<String, RectF>()

    private var scroll = 0f
    private var maxScroll = 0f
    private var viewTop = 0f
    private var viewBottom = 0f

    fun hit(x: Float, y: Float): String? = targets.firstOrNull { it.first.contains(x, y) }?.second

    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    /** Where the tile of entry [id] was drawn last (maybe scrolled out of view), for tests. */
    fun tileOf(id: String): RectF? = tileBounds[id]?.let(::RectF)

    val nodes: List<UiNode> get() = drawnNodes

    val scrollable: Boolean get() = maxScroll > 0f

    fun scrollBy(dy: Float) {
        scroll = (scroll + dy).coerceIn(0f, maxScroll)
    }

    fun resetScroll() {
        scroll = 0f
    }

    fun reveal(id: String) {
        val t = tileBounds[id] ?: return
        if (t.top < viewTop) scrollBy(t.top - viewTop) else if (t.bottom > viewBottom) scrollBy(t.bottom - viewBottom)
    }

    fun draw(
        canvas: Canvas, title: String, back: String, sections: List<LegendSection>, width: Int, height: Int,
        pressed: String?, time: Float, safe: ViewInsets = ViewInsets.NONE,
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        targets.clear()
        drawnNodes.clear()
        tileBounds.clear()
        val u = density
        val left = safe.left + MARGIN_DP * u
        val right = width - safe.right - MARGIN_DP * u
        val top = safe.top + MARGIN_DP * u
        val bottom = height - safe.bottom - MARGIN_DP * u
        val areaW = right - left

        // Header: the back pill, the title beside it or under it when there is no room.
        val pillH = maxOf(TOUCH_DP * u, scale.px(15f) + 20f * u)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = scale.px(15f)
        val backW = maxOf(TOUCH_DP * u, text.measureText(back) + 40f * u)
        r.set(left, top, left + backW, top + pillH)
        pill(canvas, back, pressed == BACK, u)
        targets += RectF(r) to BACK
        drawnNodes += UiNode("legend:$BACK", RectF(r), back, UiNode.Kind.BUTTON)
        text.textSize = scale.px(22f)
        text.color = ink
        text.textAlign = Paint.Align.LEFT
        val titleW = text.measureText(title)
        var y: Float
        if (backW + titleW + 24f * u <= areaW) {
            val tx = left + backW + 16f * u
            canvas.drawText(fit(title, right - tx), tx, top + pillH / 2f + text.textSize * 0.35f, text)
            drawnNodes += UiNode("legend:title", RectF(tx, top, tx + titleW, top + pillH), title, UiNode.Kind.HEADING)
            y = top + pillH + 12f * u
        } else {
            y = top + pillH + 8f * u
            canvas.drawText(fit(title, areaW), left, y + text.textSize, text)
            drawnNodes += UiNode("legend:title", RectF(left, y, right, y + text.textSize * 1.3f), title, UiNode.Kind.HEADING)
            y += text.textSize * 1.3f + 8f * u
        }

        // Grid metrics: as many columns of at least MIN_TILE_DP (grown with the text) as fit.
        val k = scale.factor(15f).coerceAtMost(2f)
        val cols = ((areaW + GAP_DP * u) / (MIN_TILE_DP * u * k + GAP_DP * u)).toInt().coerceIn(1, MAX_COLS)
        val tileW = (areaW - (cols - 1) * GAP_DP * u) / cols
        val titleSize = scale.px(15f)
        val descSize = scale.px(12.5f)
        val headSize = scale.px(13f)
        val pad = 10f * u
        val iconBox = maxOf(ICON_DP * u, titleSize * 2.6f)
        val textLeft = pad + iconBox + 10f * u
        val inner = tileW - textLeft - pad
        val tokenR = maxOf(9f * u, descSize * 0.72f)
        fun heightOf(e: LegendEntry): Float {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = titleSize
            val titleLines = wrap(e.title, inner, TITLE_LINES).size
            text.typeface = Typeface.DEFAULT
            text.textSize = descSize
            val body = titleSize * 1.3f * titleLines + (if (e.services.isNotEmpty()) tokenR * 2.6f else 0f) +
                (if (e.description.isEmpty()) 0f else wrap(e.description, inner, MAX_LINES).size * descSize * 1.3f)
            return pad + maxOf(body, iconBox) + pad
        }

        // Lay out everything first (content coordinates from 0), then draw what is in view.
        class Placed(val section: LegendSection, val headTop: Float, val tiles: List<Pair<LegendEntry, RectF>>)
        val placed = ArrayList<Placed>()
        var cy = 0f
        for (s in sections) {
            val headTop = cy
            cy += headSize * 1.9f
            val rows = (s.entries.size + cols - 1) / cols
            val tiles = ArrayList<Pair<LegendEntry, RectF>>()
            for (row in 0 until rows) {
                val slice = s.entries.subList(row * cols, minOf(s.entries.size, row * cols + cols))
                val h = slice.maxOf(::heightOf)
                for ((c, e) in slice.withIndex()) {
                    val x = left + c * (tileW + GAP_DP * u)
                    tiles += e to RectF(x, cy, x + tileW, cy + h)
                }
                cy += h + ROW_GAP_DP * u
            }
            placed += Placed(s, headTop, tiles)
            cy += SECTION_GAP_DP * u
        }
        viewTop = y
        viewBottom = bottom
        maxScroll = (cy - (viewBottom - viewTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
        val dy = viewTop - scroll

        canvas.save()
        canvas.clipRect(safe.left, viewTop - 4f * u, width - safe.right, viewBottom + 4f * u)
        for (p in placed) {
            val hy = p.headTop + dy
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = headSize
            text.color = muted
            text.textAlign = Paint.Align.LEFT
            val head = p.section.title.uppercase()
            canvas.drawText(fit(head, areaW), left + 2f * u, hy + headSize * 1.25f, text)
            drawnNodes += UiNode("legend:section:${p.section.id}", RectF(left, hy, right, hy + headSize * 1.9f), p.section.title, UiNode.Kind.HEADING)
            for ((e, box) in p.tiles) {
                r.set(box.left, box.top + dy, box.right, box.bottom + dy)
                tileBounds[e.id] = RectF(r)
                if (r.bottom >= viewTop - r.height() && r.top <= viewBottom + r.height()) {
                    drawTile(canvas, e, u, pad, iconBox, textLeft, inner, titleSize, descSize, tokenR, time)
                }
                drawnNodes += UiNode(
                    "legend:${e.id}", RectF(r),
                    listOfNotNull(e.title, e.servicesLabel, e.description.ifEmpty { null }).joinToString(", "), UiNode.Kind.TEXT,
                    shortened = shortened(e, inner, titleSize, descSize),
                )
            }
        }
        canvas.restore()
        if (maxScroll > 0f) {
            val trackH = viewBottom - viewTop
            val barH = maxOf(24f * u, trackH * trackH / (trackH + maxScroll))
            val barTop = viewTop + (trackH - barH) * (scroll / maxScroll)
            fillP.color = 0x55262B33
            canvas.drawRoundRect(right + 5f * u, barTop, right + 9f * u, barTop + barH, 2f * u, 2f * u, fillP)
        }
    }

    private fun drawTile(
        canvas: Canvas, e: LegendEntry, u: Float, pad: Float, iconBox: Float, textLeft: Float, inner: Float,
        titleSize: Float, descSize: Float, tokenR: Float, time: Float,
    ) {
        val radius = 14f * u
        fillP.color = 0x1A000000
        canvas.drawRoundRect(r.left + 2f * u, r.top + 3f * u, r.right + 2f * u, r.bottom + 3f * u, radius, radius, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(r, radius, radius, fillP)
        // The icon sits on a soft green patch of "ground", as on the map.
        val ix = r.left + pad
        val iy = r.top + pad
        fillP.color = 0xFFE3EEDB.toInt()
        canvas.drawRoundRect(ix, iy, ix + iconBox, iy + iconBox, 10f * u, 10f * u, fillP)
        drawIcon(canvas, e.icon, ix + iconBox / 2f, iy + iconBox / 2f, iconBox / 2f, time)

        val x = r.left + textLeft
        var y = r.top + pad
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = titleSize
        text.color = ink
        for (line in wrap(e.title, inner, TITLE_LINES)) {
            canvas.drawText(line, x, y + titleSize, text)
            y += titleSize * 1.3f
        }
        if (e.services.isNotEmpty()) {
            var tx = x + tokenR * 1.2f
            for (s in e.services) {
                if (tx + tokenR > r.right - pad) break
                ServiceGlyphs.token(canvas, s, tx, y + tokenR * 1.3f, tokenR)
                tx += tokenR * 2.7f
            }
            y += tokenR * 2.6f
        }
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        text.color = muted
        for (line in wrap(e.description, inner, MAX_LINES)) {
            canvas.drawText(line, x, y + descSize, text)
            y += descSize * 1.3f
        }
    }

    /** The map's own drawing of [icon], fitted into a box of half size [h] around ([cx], [cy]). */
    private fun drawIcon(canvas: Canvas, icon: LegendIcon, cx: Float, cy: Float, h: Float, time: Float) {
        when (icon) {
            is LegendIcon.Request -> ServiceGlyphs.token(canvas, icon.service, cx, cy, h * 0.58f)
            is LegendIcon.Response -> ServiceGlyphs.response(canvas, icon.service, cx, cy, h * 0.55f)
            is LegendIcon.Server -> icons.server(canvas, icon.service, 2, false, cx - h * 0.12f, cy + h * 0.12f, h * 0.55f, time)
            is LegendIcon.OfDevice -> icons.device(canvas, icon.device, cx, cy, h * 0.5f)
            is LegendIcon.Cable -> {
                val st = CableStyles.of(icon.type)
                val w = h * 2f * st.width * 2.2f
                lineP.color = 0xFF1E252D.toInt(); lineP.strokeWidth = w + h * 0.16f
                canvas.drawLine(cx - h * 0.7f, cy + h * 0.35f, cx + h * 0.7f, cy - h * 0.35f, lineP)
                lineP.color = st.color; lineP.strokeWidth = w
                canvas.drawLine(cx - h * 0.7f, cy + h * 0.35f, cx + h * 0.7f, cy - h * 0.35f, lineP)
                st.core?.let {
                    lineP.color = it; lineP.strokeWidth = h * 2f * st.coreWidth * 2.2f
                    canvas.drawLine(cx - h * 0.7f, cy + h * 0.35f, cx + h * 0.7f, cy - h * 0.35f, lineP)
                }
            }
            LegendIcon.Router -> icons.router(canvas, cx, cy, h * 0.5f, time)
            LegendIcon.AccessPoint -> icons.accessPoint(canvas, cx, cy + h * 0.15f, h * 0.45f, 0xFF3BA55C.toInt(), time)
            LegendIcon.CellTower -> icons.cellTower(canvas, cx, cy + h * 0.1f, h * 0.45f, time)
            is LegendIcon.Problem -> ProblemBadges.draw(canvas, icon.problem, cx, cy, h * 0.5f)
            LegendIcon.Ports -> {
                icons.device(canvas, Device.PC, cx, cy - h * 0.22f, h * 0.42f)
                val r = h * 0.11f
                val py = cy + h * 0.58f
                fillP.color = 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(cx - r * 3.3f, py - r * 1.9f, cx + r * 3.3f, py + r * 1.9f, r * 1.9f, r * 1.9f, fillP)
                lineP.color = 0xFF3A4350.toInt(); lineP.strokeWidth = r * 0.3f
                canvas.drawRoundRect(cx - r * 3.3f, py - r * 1.9f, cx + r * 3.3f, py + r * 1.9f, r * 1.9f, r * 1.9f, lineP)
                fillP.color = 0xFF3A4350.toInt(); canvas.drawCircle(cx - r * 1.4f, py, r, fillP)
                lineP.strokeWidth = r * 0.45f; canvas.drawCircle(cx + r * 1.4f, py, r * 0.78f, lineP)
            }
            LegendIcon.Overload -> {
                // The map's timer ring: dark track, white casing, the red arc that runs out, and the "!" sign.
                arc.set(cx - h * 0.62f, cy - h * 0.62f, cx + h * 0.62f, cy + h * 0.62f)
                lineP.color = 0x802A1418.toInt(); lineP.strokeWidth = h * 0.3f; canvas.drawOval(arc, lineP)
                lineP.color = 0xFFFFFFFF.toInt(); lineP.strokeWidth = h * 0.21f; canvas.drawOval(arc, lineP)
                lineP.color = ProblemBadges.ALARM; lineP.strokeWidth = h * 0.16f
                canvas.drawArc(arc, -90f, 250f, false, lineP)
                fillP.color = ProblemBadges.ALARM; canvas.drawCircle(cx, cy, h * 0.3f, fillP)
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = h * 0.5f; text.color = 0xFFFFFFFF.toInt()
                canvas.drawText("!", cx, cy + text.textSize * 0.36f, text)
                text.textAlign = Paint.Align.LEFT
            }
        }
    }

    /** True if the tile of [e] cannot show its title or its description in full. */
    private fun shortened(e: LegendEntry, inner: Float, titleSize: Float, descSize: Float): Boolean {
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = titleSize
        if (TextWrap.wrap(e.title, inner, TITLE_LINES + 20) { text.measureText(it) }.size > TITLE_LINES) return true
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        return e.description.isNotEmpty() && TextWrap.wrap(e.description, inner, MAX_LINES + 20) { text.measureText(it) }.size > MAX_LINES
    }

    private fun pill(canvas: Canvas, label: String, down: Boolean, u: Float) {
        val depth = 4f * u
        val sink = if (down) depth * 0.8f else 0f
        val radius = r.height() / 2f
        fillP.color = 0xFFD5DAD2.toInt()
        canvas.drawRoundRect(r.left, r.top + depth, r.right, r.bottom + depth, radius, radius, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(r.left, r.top + sink, r.right, r.bottom + sink, radius, radius, fillP)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        canvas.drawText(fit(label, r.width() - 24f * u), r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText(ELLIPSIS) > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    private fun wrap(s: String, maxWidth: Float, maxLines: Int): List<String> =
        if (s.isEmpty()) emptyList() else TextWrap.wrap(s, maxWidth, maxLines) { text.measureText(it) }

    companion object {
        const val BACK = "back"
        private const val MARGIN_DP = 16f
        private const val GAP_DP = 10f
        private const val ROW_GAP_DP = 10f
        private const val SECTION_GAP_DP = 8f
        private const val MIN_TILE_DP = 230f
        private const val MAX_COLS = 3
        private const val TOUCH_DP = 48f
        private const val ICON_DP = 52f
        private const val MAX_LINES = 5
        private const val TITLE_LINES = 2
        private const val ELLIPSIS = "…"
    }
}
