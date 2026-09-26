package com.mininetworks.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.R
import com.mininetworks.game.game.Reward
import com.mininetworks.game.game.RewardOffer
import com.mininetworks.game.game.Rewards
import com.mininetworks.game.game.World
import com.mininetworks.game.render.DeviceIcons
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import kotlin.math.sin

/**
 * The week reward choice, drawn on the game canvas over the paused map: two large cards, each a thick slab with a
 * small isometric diorama of its reward. [hit] maps a tap to the card index for [World.chooseReward].
 */
class RewardDialog(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val cards = listOf(RectF(), RectF())
    private val drawn = RectF()
    private val path = Path()
    private val fillP = fill(0)
    private val icons = DeviceIcons()
    private val texts = Texts(context)
    private val dim = fill(0xD9F3F1EC.toInt())
    private val ink = 0xFF262B33.toInt()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = ink }

    // Isometric projection of the current diorama: origin and half tile width in pixels.
    private var ox = 0f
    private var oy = 0f
    private var u = 1f

    /** Index of the card under the screen point, or null. Valid for the last drawn frame. */
    fun hit(x: Float, y: Float): Int? = cards.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }

    fun draw(canvas: Canvas, world: World, offer: RewardOffer, width: Int, height: Int, time: Float, pressed: Int? = null) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        val cardW = minOf(width * 0.3f, height * 0.5f, 320 * density)
        val cardH = minOf(cardW * 1.08f, height * 0.56f)
        val gap = cardW * 0.14f
        val depth = cardW * 0.05f
        val top = height * 0.28f
        val left = (width - 2 * cardW - gap) / 2f

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = cardW * 0.1f
        canvas.drawText(context.getString(R.string.hud_date, world.year, offer.week), width / 2f, top - cardW * 0.22f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = cardW * 0.065f
        // This week's unlock message, without the year the heading already shows.
        world.lastNews?.takeIf { world.lastNewsTime == world.time }?.let {
            canvas.drawText(texts.news(it, withYear = false), width / 2f, top - cardW * 0.1f, text)
        }
        text.color = 0xFF5B6674.toInt()
        canvas.drawText(context.getString(R.string.reward_prompt), width / 2f, top + cardH + cardW * 0.2f, text)
        text.color = ink

        offer.choices.forEachIndexed { i, reward ->
            val r = cards[i]
            r.set(left + i * (cardW + gap), top, left + i * (cardW + gap) + cardW, top + cardH)
            drawCard(canvas, r, depth, sink = if (pressed == i) depth * 0.8f else 0f, reward, time + i * 0.7f)
        }
    }

    /** Card [slot] on its slab of thickness [depth]; a pressed card's face sinks by [sink] onto the slab. */
    private fun drawCard(canvas: Canvas, slot: RectF, depth: Float, sink: Float, reward: Reward, time: Float) {
        val r = drawn.apply { set(slot); offset(0f, sink) }
        val accent = accentOf(reward)
        val radius = r.width() * 0.07f
        fillP.color = 0x33000000
        canvas.drawRoundRect(slot.left + depth, slot.top + depth * 2.2f, slot.right + depth, slot.bottom + depth * 2.2f, radius, radius, fillP)
        fillP.color = 0xFFE3E6E1.toInt().shade(-0.2f)
        canvas.drawRoundRect(slot.left, slot.top + depth, slot.right, slot.bottom + depth, radius, radius, fillP)
        fillP.color = 0xFFFAFAF7.toInt()
        canvas.drawRoundRect(r, radius, radius, fillP)

        // Diorama: one grass tile with the reward standing on it.
        ox = r.centerX()
        oy = r.top + r.height() * 0.33f
        u = r.width() * 0.2f
        fillP.color = accent.shade(0.8f)
        canvas.drawRoundRect(r.left + depth, r.top + depth, r.right - depth, r.top + r.height() * 0.55f, radius * 0.7f, radius * 0.7f, fillP)
        tile(canvas, 0xFFDDE9D6.toInt(), 0xFFB9C9AF.toInt())
        val bob = 0.06f * sin(time * 2.2f)
        when (reward) {
            Reward.BUDGET -> coins(canvas, bob)
            Reward.ROUTERS -> routers(canvas, bob, time)
            Reward.SERVER_VOUCHER -> server(canvas, accent, bob)
            Reward.ACCESS_POINT -> accessPoint(canvas, accent, bob, time)
            Reward.CELL_TOWER -> cellTower(canvas, time)
        }

        val cx = r.centerX()
        var y = r.top + r.height() * 0.7f
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = accent.shade(-0.25f)
        text.textSize = r.width() * 0.13f
        canvas.drawText(amountOf(reward), cx, y, text)
        text.color = ink
        text.textSize = r.width() * 0.085f
        y += r.width() * 0.11f
        canvas.drawText(context.getString(titleOf(reward)), cx, y, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = r.width() * 0.058f
        text.color = 0xFF5B6674.toInt()
        for (line in wrap(context.getString(descOf(reward)), r.width() * 0.84f)) {
            y += text.textSize * 1.3f
            canvas.drawText(line, cx, y, text)
        }
        text.color = ink
    }

    private fun accentOf(reward: Reward) = when (reward) {
        Reward.BUDGET -> 0xFFE9A92B.toInt()
        Reward.ROUTERS -> 0xFF3BA55C.toInt()
        Reward.SERVER_VOUCHER -> 0xFF2E86AB.toInt()
        Reward.ACCESS_POINT -> 0xFF8E6CC0.toInt()
        Reward.CELL_TOWER -> 0xFF1FA39A.toInt()
    }

    private fun amountOf(reward: Reward) = when (reward) {
        Reward.BUDGET -> context.getString(R.string.reward_amount_plus, Rewards.BUDGET)
        Reward.ROUTERS -> context.getString(R.string.reward_amount_plus, Rewards.ROUTERS)
        Reward.SERVER_VOUCHER -> context.getString(R.string.reward_amount_voucher)
        Reward.ACCESS_POINT -> context.getString(R.string.reward_amount_plus, Rewards.ACCESS_POINTS)
        Reward.CELL_TOWER -> context.getString(R.string.reward_amount_plus, Rewards.CELL_TOWERS)
    }

    private fun titleOf(reward: Reward) = when (reward) {
        Reward.BUDGET -> R.string.reward_budget_title
        Reward.ROUTERS -> R.string.reward_routers_title
        Reward.SERVER_VOUCHER -> R.string.reward_voucher_title
        Reward.ACCESS_POINT -> R.string.reward_access_point_title
        Reward.CELL_TOWER -> R.string.reward_cell_tower_title
    }

    private fun descOf(reward: Reward) = when (reward) {
        Reward.BUDGET -> R.string.reward_budget_desc
        Reward.ROUTERS -> R.string.reward_routers_desc
        Reward.SERVER_VOUCHER -> R.string.reward_voucher_desc
        Reward.ACCESS_POINT -> R.string.reward_access_point_desc
        Reward.CELL_TOWER -> R.string.reward_cell_tower_desc
    }

    private fun wrap(s: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in s.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && text.measureText(next) > maxWidth) {
                lines += line
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    // ---------------------------------------------------------------- isometric diorama

    private fun sx(x: Float, y: Float) = ox + (x - y) * u
    private fun sy(x: Float, y: Float, z: Float = 0f) = oy + (x + y) * u / 2f - z * u

    private fun tile(canvas: Canvas, top: Int, side: Int) = box(canvas, 0f, 0f, 2f, 0.18f, top, side, z0 = -0.18f)

    private fun coins(canvas: Canvas, bob: Float) {
        val gold = 0xFFE9A92B.toInt()
        for ((x, y, n) in listOf(Triple(-0.35f, 0.25f, 3), Triple(0.3f, -0.3f, 5), Triple(0.35f, 0.4f, 2))) {
            for (k in 0 until n) coin(canvas, x, y, k * 0.13f + if (n == 5) bob else 0f, gold)
        }
    }

    private fun coin(canvas: Canvas, x: Float, y: Float, z: Float, color: Int) {
        val cx = sx(x, y)
        val r = u * 0.42f
        fillP.color = color.shade(-0.3f)
        canvas.drawOval(cx - r, sy(x, y, z) - r * 0.5f, cx + r, sy(x, y, z) + r * 0.5f, fillP)
        canvas.drawRect(cx - r, sy(x, y, z + 0.1f), cx + r, sy(x, y, z), fillP)
        fillP.color = color
        canvas.drawOval(cx - r, sy(x, y, z + 0.1f) - r * 0.5f, cx + r, sy(x, y, z + 0.1f) + r * 0.5f, fillP)
        fillP.color = color.shade(0.35f)
        canvas.drawOval(cx - r * 0.55f, sy(x, y, z + 0.1f) - r * 0.27f, cx + r * 0.55f, sy(x, y, z + 0.1f) + r * 0.27f, fillP)
    }

    private fun routers(canvas: Canvas, bob: Float, time: Float) {
        for ((x, y) in listOf(-0.45f to 0.2f, 0.25f to -0.4f).sortedBy { it.first + it.second }) {
            val z = if (x > 0f) bob.coerceAtLeast(0f) else 0f
            box(canvas, x, y, 0.75f, 0.28f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt(), z0 = z)
            icons.router(canvas, sx(x, y), sy(x, y, z + 0.28f) - u * 0.2f, u * 0.32f, time)
        }
    }

    private fun server(canvas: Canvas, color: Int, bob: Float) {
        box(canvas, 0f, 0f, 0.95f, 0.6f, color.shade(0.15f), 0xFFE9ECEF.toInt())
        // The free extra rack unit hovers above the tower.
        val z = 0.85f + bob
        box(canvas, 0f, 0f, 0.95f, 0.6f, color.shade(0.55f), 0xFFF4F6F8.toInt(), z0 = z)
        fillP.color = color
        val ax = sx(0f, 0f); val ay = sy(0f, 0f, z + 0.6f) - u * 0.12f
        path.reset()
        path.moveTo(ax, ay - u * 0.45f)
        path.lineTo(ax + u * 0.32f, ay - u * 0.1f)
        path.lineTo(ax + u * 0.12f, ay - u * 0.1f)
        path.lineTo(ax + u * 0.12f, ay + u * 0.12f)
        path.lineTo(ax - u * 0.12f, ay + u * 0.12f)
        path.lineTo(ax - u * 0.12f, ay - u * 0.1f)
        path.lineTo(ax - u * 0.32f, ay - u * 0.1f)
        path.close()
        canvas.drawPath(path, fillP)
    }

    /** An access point on a small plinth inside its tinted radio circle. */
    private fun accessPoint(canvas: Canvas, color: Int, bob: Float, time: Float) {
        fillP.color = color and 0x00FFFFFF or 0x33000000
        canvas.drawOval(sx(0f, 0f) - u * 1.3f, sy(0f, 0f) - u * 0.65f, sx(0f, 0f) + u * 1.3f, sy(0f, 0f) + u * 0.65f, fillP)
        box(canvas, 0f, 0f, 0.7f, 0.45f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt())
        icons.accessPoint(canvas, sx(0f, 0f), sy(0f, 0f, 0.45f + bob) - u * 0.3f, u * 0.55f, color, time)
    }

    private fun cellTower(canvas: Canvas, time: Float) {
        box(canvas, 0f, 0f, 0.8f, 0.12f, 0xFFCBD2D9.toInt(), 0xFFB9C2CC.toInt())
        icons.cellTower(canvas, sx(0f, 0f), sy(0f, 0f, 0.12f) - u * 0.75f, u * 0.75f, time)
    }

    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int, z0: Float = 0f) {
        val x0 = cx - s / 2; val y0 = cy - s / 2; val x1 = cx + s / 2; val y1 = cy + s / 2
        val z1 = z0 + h
        face(sx(x0, y1), sy(x0, y1, z0), sx(x1, y1), sy(x1, y1, z0), sx(x1, y1), sy(x1, y1, z1), sx(x0, y1), sy(x0, y1, z1))
        fillP.color = side.shade(-0.12f); canvas.drawPath(path, fillP)
        face(sx(x1, y0), sy(x1, y0, z0), sx(x1, y1), sy(x1, y1, z0), sx(x1, y1), sy(x1, y1, z1), sx(x1, y0), sy(x1, y0, z1))
        fillP.color = side.shade(-0.25f); canvas.drawPath(path, fillP)
        face(sx(x0, y0), sy(x0, y0, z1), sx(x1, y0), sy(x1, y0, z1), sx(x1, y1), sy(x1, y1, z1), sx(x0, y1), sy(x0, y1, z1))
        fillP.color = top; canvas.drawPath(path, fillP)
    }

    private fun face(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        path.reset()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy); path.close()
    }
}
