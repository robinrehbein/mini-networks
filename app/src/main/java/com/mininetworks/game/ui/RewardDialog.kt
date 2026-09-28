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
 * small isometric diorama of its reward. [hit] maps a tap to the card index for [World.chooseReward], or to [BONUS]
 * for the optional extra-router pill below the cards ([World.claimBonusRouter]).
 */
class RewardDialog(private val context: Context) {
    private val scale = TextScale.of(context)
    private val density = scale.density
    private val drawnNodes = ArrayList<UiNode>()
    /** Text size factor of the current frame: the card-relative sizes times the system font size. */
    private var textK = 1f
    private var baseW = 1f
    private val cards = listOf(RectF(), RectF())
    private val bonus = RectF()
    private var bonusShown = false
    private val play = Path()
    private val drawn = RectF()
    private val path = Path()
    private val fillP = fill(0)
    private val icons = DeviceIcons()
    private val texts = Texts(context)
    /**
     * A neutral near-black scrim (judge panel: a light one mixed into an olive wash, a teal one muddied the map):
     * the map stays a quiet backdrop, and a tight warm spotlight with slowly turning rays lifts the cards in the middle, so the week's reward feels like a
     * celebration rather than a settings dialog.
     */
    private val dim = fill(0xC014171D.toInt())
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillP = fill(0xCC181D48.toInt())
    private val display = Fonts.display(context)
    private val ink = 0xFF262B33.toInt()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = ink }

    // Isometric projection of the current diorama: origin and half tile width in pixels.
    private var ox = 0f
    private var oy = 0f
    private var u = 1f

    /** Index of the card under the screen point, [BONUS] for the bonus pill, or null. Valid for the last drawn frame. */
    fun hit(x: Float, y: Float): Int? {
        if (bonusShown && bonus.contains(x, y)) return BONUS
        return cards.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }
    }

    /** Where the bonus pill was drawn in the last frame, or null. */
    fun bonusTarget(): RectF? = if (bonusShown) RectF(bonus) else null

    /** Heading, cards, prompt and bonus pill of the last drawn frame, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /**
     * Draws the choice. [bonus] labels the extra-router pill below the cards, null hides it; [video] adds a play sign
     * because the extra costs a rewarded video. Text follows the system font size: the cards get wider and their
     * dioramas smaller to make room. [side] is kept free at the left and right bottom corners (the menu button).
     */
    fun draw(
        canvas: Canvas, world: World, offer: RewardOffer, width: Int, height: Int, time: Float, pressed: Int? = null,
        bonus: String? = null, video: Boolean = false, side: Float = 0f,
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        drawnNodes.clear()
        textK = scale.factor(14f)
        fitK = 1f
        baseW = minOf(width * 0.42f, height * 0.56f, 380 * density)
        // Two cards and their gap (0.14 of a card) stay clear of the menu button's column at both sides.
        val cardW = minOf(baseW * textK, (width - 2 * side) / 2.14f, 380 * density * textK)
        val dateSize = baseW * 0.1f * scale.factor(20f)
        val newsSize = baseW * 0.065f * textK
        val news = world.lastNews?.takeIf { world.lastNewsTime == world.time }?.let { texts.news(it, withYear = false) }
        val headH = dateSize * 1.25f + (if (news != null) newsSize * 1.5f else 0f) + newsSize * 0.2f + 8f * density
        // The weekly pay line is part of the reward: large enough to read at phone size, on its own dark pill.
        // (With large system text the line is already large: it keeps its size so the cards keep their room.)
        val large = textK > 1.15f
        val promptSize = if (large) newsSize else newsSize * 1.2f
        // The pill's side padding; slimmer with large text, so the line keeps to as few rows as before.
        val pillPad = promptSize * (if (large) 0.5f else 1.6f)
        val promptFace = if (large) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        text.typeface = promptFace
        text.textSize = promptSize
        val prompt = context.getString(R.string.reward_prompt, World.Tuning.WEEK_BUDGET)
        // At most two lines inside the pill; whatever does not fit ends the second one with an ellipsis.
        val promptLines = TextWrap.wrap(prompt, width - 2 * side - (if (large) 0f else pillPad), maxLines = 2) { text.measureText(it) }
        val bonusH = bonusHeight()
        // With the bonus pill the cards move up, so prompt and pill fit below them.
        // Room under the cards: their slab and shadow (2.4 × depth), the pill with the prompt, and the bonus pill.
        // Large text keeps every dp for the cards: the pill may then reach a little past the line's own margin.
        val below = promptSize * ((if (large) 1.0f else 1.1f) + 1.3f * promptLines.size) + (if (bonus != null) bonusH + 16f * density else 0f) +
            (if (large) cardW * 0.1f else cardW * 0.12f + 4f * density)
        val gap = cardW * 0.14f
        val depth = cardW * 0.05f
        // The card grows with its texts (large font sizes) up to the room between heading and prompt; only when even
        // that is not enough do the card texts shrink together (autosize), so the amount always stays inside its card.
        val room = height - headH - below - 24f * density
        var cardH: Float
        while (true) {
            blockH = offer.choices.maxOf { blockHeight(it, cardW) }
            val needed = blockH + cardW * 0.08f
            cardH = minOf(maxOf(minOf(cardW * 1.08f, height * 0.62f), needed), room)
            if (needed <= cardH + 0.5f || fitK <= MIN_FIT) break
            fitK = maxOf(MIN_FIT, fitK - 0.04f)
        }
        val top = minOf(height * 0.28f, height - cardH - below - 8f * density).coerceAtLeast(headH + 8f * density)
        val left = (width - 2 * cardW - gap) / 2f
        spotlight(canvas, width / 2f, top + cardH * 0.45f, (2 * cardW + gap) * 0.64f, time)

        text.typeface = display
        text.textSize = dateSize
        val date = context.getString(R.string.hud_date, world.year, offer.week)
        // Clear of the cards: the subtitle keeps most of a line's height from their top edge.
        val newsBaseline = top - maxOf(12f * density, newsSize * 0.6f)
        val dateBaseline = if (news != null) newsBaseline - newsSize * 1.5f else newsBaseline
        // Light text on the dark scrim, like a title card over the paused game.
        text.color = 0xFFFFFFFF.toInt()
        text.setShadowLayer(4f * density, 0f, 1.5f * density, 0x80000000.toInt())
        canvas.drawText(date, width / 2f, dateBaseline, text)
        text.clearShadowLayer()
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = newsSize
        text.color = 0xFFFFD58A.toInt()
        // Warm text in the warm spotlight: a dark shadow keeps it apart from the glow.
        text.setShadowLayer(3f * density, 0f, 1f * density, 0xB3181D48.toInt())
        // This week's unlock message, without the year the heading already shows.
        news?.let { canvas.drawText(fit(it, width - 2 * side), width / 2f, newsBaseline, text) }
        text.clearShadowLayer()
        drawnNodes += UiNode("reward:title", RectF(left, dateBaseline - dateSize, width - left, newsBaseline + newsSize * 0.3f), listOfNotNull(date, news).joinToString(". "), UiNode.Kind.HEADING)
        // Bold white on a dark pill: the hint must read over the map at phone size (judge panel).
        text.color = 0xFFFFFFFF.toInt()
        text.typeface = promptFace
        text.textSize = promptSize
        // Below the cards' slabs and their soft shadow, so the line never touches them.
        val promptTop = top + cardH + depth * 2.4f + promptSize * 0.5f
        val shown = promptLines.map { fit(it, width - 2 * side - (if (large) 0f else pillPad)) }
        val pillW = (shown.maxOfOrNull { text.measureText(it) } ?: 0f) + pillPad
        val pillBottom = promptTop + promptLines.size * promptSize * 1.3f + promptSize * 0.45f
        canvas.drawRoundRect(
            width / 2f - pillW / 2f, promptTop + promptSize * 0.15f, width / 2f + pillW / 2f, pillBottom,
            promptSize, promptSize, pillP,
        )
        var promptY = promptTop
        for (line in shown) {
            promptY += promptSize * 1.3f
            canvas.drawText(line, width / 2f, promptY - promptSize * 0.2f, text)
        }
        drawnNodes += UiNode("reward:prompt", RectF(side, promptTop, width - side, promptY), prompt, UiNode.Kind.TEXT)
        text.color = ink
        bonusShown = bonus != null
        if (bonus != null) {
            drawBonus(canvas, bonus, video, width, height, promptY + promptSize * 0.4f, pressed == BONUS)
            drawnNodes += UiNode("reward:bonus", RectF(this.bonus), bonus, UiNode.Kind.BUTTON)
        }

        // Both cards leave the same room for their texts ([blockH], set above), so their dioramas match.
        offer.choices.forEachIndexed { i, reward ->
            val r = cards[i]
            r.set(left + i * (cardW + gap), top, left + i * (cardW + gap) + cardW, top + cardH)
            drawCard(canvas, r, depth, sink = if (pressed == i) depth * 0.8f else 0f, reward, time + i * 0.7f)
            val said = listOf(amountOf(reward), context.getString(titleOf(reward)), context.getString(descOf(reward))).joinToString(", ")
            drawnNodes += UiNode("reward:$i", RectF(r), said, UiNode.Kind.BUTTON)
        }
    }

    /** Room the texts of the cards need, the most of both; see [blockHeight]. */
    private var blockH = 0f

    /** Shrink of the card texts when they do not fit even the tallest card (1 = none). */
    private var fitK = 1f

    private fun amountSize() = baseW * 0.13f * scale.factor(24f) * fitK
    private fun titleSize() = baseW * 0.085f * textK * fitK
    private fun descSize() = baseW * 0.064f * textK * fitK

    /** Height of the amount, title and wrapped description of [reward] on a card [cardW] wide. */
    private fun blockHeight(reward: Reward, cardW: Float): Float {
        val descSize = descSize()
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        val lines = wrap(context.getString(descOf(reward)), cardW * 0.84f).size
        return amountSize() * 0.85f + titleSize() * 1.3f + lines * descSize * 1.3f + cardW * 0.05f
    }

    private fun bonusHeight() = maxOf(TOUCH_DP * density, scale.px(16f) * 2.4f)

    /**
     * The extra-router pill: centered under the prompt at [top], kept on screen, pressed when [down]. A secondary,
     * outlined button on purpose: the free cards above are the choice that continues the game; the optional video
     * must not look like the way on (no saturated fill, no slab, smaller text than the card titles).
     */
    private fun drawBonus(canvas: Canvas, label: String, video: Boolean, width: Int, height: Int, top: Float, down: Boolean) {
        val h = bonusHeight()
        text.typeface = Typeface.DEFAULT
        text.textSize = scale.px(15f)
        val icon = if (video) h * 0.42f else 0f
        val w = text.measureText(label) + icon + h * 1.1f
        val y = top.coerceAtMost(height - h - 12f * density)
        bonus.set((width - w) / 2f, y, (width + w) / 2f, y + h)
        val line = 0xE6FFFFFF.toInt()
        // A faint dark fill keeps the outline readable over the dimmed map; pressed, it lightens a little.
        fillP.color = if (down) 0x40FFFFFF else 0x26000000
        canvas.drawRoundRect(bonus, h / 2f, h / 2f, fillP)
        outlineP.strokeWidth = 1.5f * density
        outlineP.color = line
        val inset = outlineP.strokeWidth / 2f
        canvas.drawRoundRect(bonus.left + inset, bonus.top + inset, bonus.right - inset, bonus.bottom - inset, h / 2f, h / 2f, outlineP)
        val textX = bonus.centerX() + icon / 2f
        if (video) {
            // A small outlined play sign left of the label.
            val cx = textX - text.measureText(label) / 2f - icon * 0.75f
            val cy = bonus.centerY()
            canvas.drawCircle(cx, cy, icon / 2f - inset, outlineP)
            play.reset()
            play.moveTo(cx - icon * 0.12f, cy - icon * 0.2f)
            play.lineTo(cx + icon * 0.22f, cy)
            play.lineTo(cx - icon * 0.12f, cy + icon * 0.2f)
            play.close()
            fillP.color = line
            canvas.drawPath(play, fillP)
        }
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(label, textX, bonus.centerY() + text.textSize * 0.35f, text)
        text.color = ink
    }

    private val outlineP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }

    private val rimP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val shadowP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E000000 }
    private var blurRadius = 0f
    private var blur: android.graphics.BlurMaskFilter? = null

    /** A blur of [radius] px, rebuilt only when the size changes. */
    private fun blurFor(radius: Float): android.graphics.BlurMaskFilter {
        val r = radius.coerceAtLeast(1f)
        if (blur == null || r != blurRadius) {
            blurRadius = r
            blur = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        return blur!!
    }

    private var spotX = Float.NaN
    private var spotY = Float.NaN
    private var spotR = Float.NaN
    private var haloRadius = 0f
    private var halo: android.graphics.BlurMaskFilter? = null

    private fun haloFor(radius: Float): android.graphics.BlurMaskFilter {
        val r = radius.coerceAtLeast(1f)
        if (halo == null || r != haloRadius) {
            haloRadius = r
            halo = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        return halo!!
    }

    /**
     * The warm spotlight behind the cards at ([cx], [cy]) with radius [r]: a golden glow and soft rays that turn
     * slowly with [time].
     */
    private fun spotlight(canvas: Canvas, cx: Float, cy: Float, r: Float, time: Float) {
        // The gradients depend only on the layout; rebuilt when it changes, not every frame.
        if (cx != spotX || cy != spotY || r != spotR) {
            spotX = cx; spotY = cy; spotR = r
            glowP.shader = android.graphics.RadialGradient(
                cx, cy, r, intArrayOf(0x73FFEBC8, 0x1FFFB84D, 0x00FFB84D), floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            rayP.shader = android.graphics.RadialGradient(
                cx, cy, r * 1.25f, intArrayOf(0x1FFFF3D6, 0x0AFFF3D6, 0x00FFF3D6), floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, glowP)
        path.reset()
        val n = RAYS
        val spin = time * 0.12f
        for (k in 0 until n) {
            val a0 = spin + k * (2f * Math.PI.toFloat() / n)
            val a1 = a0 + Math.PI.toFloat() / n * 0.55f
            path.moveTo(cx, cy)
            path.lineTo(cx + kotlin.math.cos(a0) * r * 1.3f, cy + kotlin.math.sin(a0) * r * 1.3f)
            path.lineTo(cx + kotlin.math.cos(a1) * r * 1.3f, cy + kotlin.math.sin(a1) * r * 1.3f)
            path.close()
        }
        canvas.drawPath(path, rayP)
    }

    /** Card [slot] on its slab of thickness [depth]; a pressed card's face sinks by [sink] onto the slab. */
    private fun drawCard(canvas: Canvas, slot: RectF, depth: Float, sink: Float, reward: Reward, time: Float) {
        val r = drawn.apply { set(slot); offset(0f, sink) }
        val accent = accentOf(reward)
        val radius = r.width() * 0.07f
        // A warm halo around the card: the prize glows in the spotlight.
        haloP.color = 0x66FFD27A
        haloP.maskFilter = haloFor(depth * 3.2f)
        canvas.drawRoundRect(slot.left - depth, slot.top - depth, slot.right + depth, slot.bottom + depth * 2f, radius * 1.3f, radius * 1.3f, haloP)
        // One soft drop shadow straight below the card (a hard offset copy read as a misprinted second card).
        shadowP.maskFilter = blurFor(depth * 1.6f)
        canvas.drawRoundRect(slot.left + depth * 0.4f, slot.top + depth * 2f, slot.right - depth * 0.4f, slot.bottom + depth * 2f, radius, radius, shadowP)
        fillP.color = 0xFFE3E6E1.toInt().shade(-0.2f)
        canvas.drawRoundRect(slot.left, slot.top + depth, slot.right, slot.bottom + depth, radius, radius, fillP)
        fillP.color = 0xFFFAFAF7.toInt()
        canvas.drawRoundRect(r, radius, radius, fillP)
        // A rim in the reward's category colour tells the two choices apart at a glance (judge panel).
        rimP.color = accent; rimP.strokeWidth = depth * 0.7f
        canvas.drawRoundRect(r.left + rimP.strokeWidth / 2f, r.top + rimP.strokeWidth / 2f, r.right - rimP.strokeWidth / 2f, r.bottom - rimP.strokeWidth / 2f, radius, radius, rimP)

        // Text from the bottom up: amount, title and description; larger text pushes it up and shrinks the diorama.
        val w = r.width()
        val amountSize = amountSize()
        val titleSize = titleSize()
        val descSize = descSize()
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        val desc = wrap(context.getString(descOf(reward)), w * 0.84f)
        // Never above the card's own top edge: the amount belongs to its card at any font size.
        val textTop = minOf(r.top + r.height() * 0.7f - amountSize * 0.85f, r.bottom - blockH).coerceAtLeast(r.top + w * 0.04f)
        val f = ((textTop - r.top - r.height() * 0.04f) / (r.height() * 0.56f)).coerceAtMost(1f)

        // Diorama: one grass tile with the reward standing on it; left out when large text needs the whole card.
        if (f >= MIN_DIORAMA) {
            // The diorama stays inside its card (a tall server model poked out of a low card).
            canvas.save()
            canvas.clipRect(r.left, r.top + depth * 0.5f, r.right, r.bottom)
            ox = r.centerX()
            u = minOf(w * 0.2f, r.height() * 0.19f) * f
            // Tall models (the server with its hovering rack unit) sit lower on their plate, and only shrink when even
            // then they would reach over the card's top edge.
            val rise = if (reward == Reward.SERVER_VOUCHER) 2.7f else 1.7f
            oy = minOf(maxOf(r.top + r.height() * 0.33f * f, r.top + depth + rise * u), r.top + r.height() * 0.55f * f - u * 0.9f)
            u = minOf(u, (oy - r.top - depth) / rise)
            fillP.color = accent.shade(0.8f)
            canvas.drawRoundRect(r.left + depth, r.top + depth, r.right - depth, r.top + r.height() * 0.55f * f, radius * 0.7f, radius * 0.7f, fillP)
            tile(canvas, 0xFFDDE9D6.toInt(), 0xFFB9C9AF.toInt())
            val bob = 0.06f * sin(time * 2.2f)
            when (reward) {
                Reward.BUDGET -> coins(canvas, bob)
                Reward.ROUTERS -> routers(canvas, bob, time)
                Reward.SERVER_VOUCHER -> server(canvas, accent, bob)
                Reward.ACCESS_POINT -> accessPoint(canvas, accent, bob, time)
                Reward.CELL_TOWER -> cellTower(canvas, time)
            }
            canvas.restore()
        }

        val cx = r.centerX()
        var y = textTop + amountSize * 0.85f
        text.typeface = display
        text.color = accent.shade(-0.25f)
        text.textSize = amountSize
        canvas.drawText(amountOf(reward), cx, y, text)
        text.color = ink
        text.textSize = titleSize
        val title = context.getString(titleOf(reward))
        // A long title ("Server-Gutschein" at 200 %) scales down to the card instead of being cut.
        if (text.measureText(title) > w * 0.9f) text.textSize = maxOf(titleSize * w * 0.9f / text.measureText(title) * 0.98f, titleSize * 0.45f)
        y += titleSize * 1.3f
        canvas.drawText(fit(title, w * 0.9f), cx, y, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        text.color = 0xFF3F4854.toInt()
        for (line in desc) {
            y += descSize * 1.3f
            canvas.drawText(line, cx, y, text)
        }
        text.color = ink
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in the current text paint. */
    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxWidth) end--
        return s.substring(0, end).trimEnd() + "…"
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

    /** Splits [s] into lines no wider than [maxWidth] in the current text paint (at spaces, and between CJK characters). */
    private fun wrap(s: String, maxWidth: Float): List<String> = TextWrap.wrap(s, maxWidth) { text.measureText(it) }

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

    companion object {
        /** [hit] result for the extra-router pill. */
        const val BONUS = -1
        /** Rays of the spotlight behind the cards. */
        private const val RAYS = 14
        /** Android's minimum touch target. */
        private const val TOUCH_DP = 48f
        /** Smallest shrink of the card texts when the tallest card is still too low for them. */
        private const val MIN_FIT = 0.55f
        /** Smallest diorama (share of its size at the default text size) worth drawing. */
        private const val MIN_DIORAMA = 0.4f
    }
}
