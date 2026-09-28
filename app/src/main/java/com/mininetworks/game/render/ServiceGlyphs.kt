package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import com.mininetworks.game.game.Service

/**
 * Pictograms for the services: an envelope for mail, a handset for calls, a gamepad for gaming, a play button for
 * streaming, a video camera for video calls, a photo camera for camera uploads and a cloud for backups. They replace
 * the abstract shapes the game started with (players could not tell what a triangle or a pentagon meant). Every place
 * that shows a service uses them: packets on the cables, waiting requests at a device, the sign on a server's roof,
 * the delivery pop, the legend and the recap map. The pictogram carries the meaning, the color only supports it, so
 * the colorblind palette changes nothing about which is which.
 *
 * Paths: Material Icons (round), Apache License 2.0; the router sign uses "lan" the same way, see docs/licenses-material-icons.txt.
 */
object ServiceGlyphs {
    private const val VIEWPORT = 24f

    private val paths: Map<Service, Path> = Service.entries.associateWith { s ->
        when (s) {
            Service.MAIL -> parse(
                "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-.4 4.25-6.54 4.09c-.65.41-1.47.41-2.12 0L4.4 8.25a.85.85 0 1 1 .9-1.44L12 11l6.7-4.19a.85.85 0 1 1 .9 1.44z",
            )
            Service.CALL -> parse(
                "m19.23 15.26-2.54-.29a1.99 1.99 0 0 0-1.64.57l-1.84 1.84a15.045 15.045 0 0 1-6.59-6.59l1.85-1.85c.43-.43.64-1.03.57-1.64l-.29-2.52a2.001 2.001 0 0 0-1.99-1.77H5.03c-1.13 0-2.07.94-2 2.07.53 8.54 7.36 15.36 15.89 15.89 1.13.07 2.07-.87 2.07-2v-1.73c.01-1.01-.75-1.86-1.76-1.98z",
            )
            Service.GAMING -> parse(
                "m21.58 16.09-1.09-7.66A3.996 3.996 0 0 0 16.53 5H7.47C5.48 5 3.79 6.46 3.51 8.43l-1.09 7.66a2.545 2.545 0 0 0 4.32 2.16L9 16h6l2.25 2.25c.48.48 1.13.75 1.8.75 1.56 0 2.75-1.37 2.53-2.91zM11 11H9v2H8v-2H6v-1h2V8h1v2h2v1zm4-1c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm2 3c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z",
            )
            // Nudged half a unit left: the triangle's optical center sits right of its box center.
            Service.STREAMING -> parse("M7.5 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18a1 1 0 0 0 0-1.69L9.04 5.98A.998.998 0 0 0 7.5 6.82z")
            Service.VIDEO_CALL -> parse(
                "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l2.29 2.29c.63.63 1.71.18 1.71-.71V8.91c0-.89-1.08-1.34-1.71-.71L17 10.5z",
            )
            Service.CAMERA_UPLOAD -> parse(
                "M20 4h-3.17l-1.24-1.35A1.99 1.99 0 0 0 14.12 2H9.88c-.56 0-1.1.24-1.48.65L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 13c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z",
            ).apply { addCircle(12f, 12f, 3f, Path.Direction.CW) }
            Service.CLOUD_BACKUP -> parse(
                "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
            )
        }
    }

    private fun parse(data: String): Path = PathParser.createPathFromPathData(data)

    /** Material Icons "lan": the network tree on a router's sign, so routers read as network gear like servers. */
    private val network: Path = parse(
        "M15 22h4c1.1 0 2-.9 2-2v-3c0-1.1-.9-2-2-2h-1v-2c0-1.1-.9-2-2-2h-3V9h1c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v3c0 1.1.9 2 2 2h1v2H8c-1.1 0-2 .9-2 2v2H5c-1.1 0-2 .9-2 2v3c0 1.1.9 2 2 2h4c1.1 0 2-.9 2-2v-3c0-1.1-.9-2-2-2H8v-2h8v2h-1c-1.1 0-2 .9-2 2v3c0 1.1.9 2 2 2z",
    )

    /** A router's sign: white disc, rim in [rimColor], the network tree in [color]; the counterpart of [sign]. */
    fun networkSign(c: Canvas, x: Float, y: Float, r: Float, color: Int, rimColor: Int) {
        fillP.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, r, fillP)
        rimP.color = rimColor; rimP.strokeWidth = r * 0.16f
        c.drawCircle(x, y, r, rimP)
        fillP.color = color
        val size = r * 1.2f
        val k = size / VIEWPORT
        c.save()
        c.translate(x - size / 2f, y - size / 2f)
        c.scale(k, k)
        c.drawPath(network, fillP)
        c.restore()
    }

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Below this radius in pixels a token is a plain disc: a pictogram would only be a smudge. */
    private const val GLYPH_MIN_PX = 3.2f

    /** The pictogram alone, [size] pixels across (the 24-unit icon box), centered on ([x], [y]). */
    fun glyph(c: Canvas, s: Service, x: Float, y: Float, size: Float, color: Int) {
        fillP.color = color
        val k = size / VIEWPORT
        c.save()
        c.translate(x - size / 2f, y - size / 2f)
        c.scale(k, k)
        c.drawPath(paths.getValue(s), fillP)
        c.restore()
    }

    /**
     * A request: a disc of radius [r] in the service color with the white pictogram and a white sticker rim, so it
     * stays readable on dark cables and grass. [alpha] fades the whole token (0–255).
     */
    fun token(c: Canvas, s: Service, x: Float, y: Float, r: Float, alpha: Int = 255, rim: Boolean = true) {
        val col = ServiceColors.of(s)
        if (rim) {
            fillP.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
            c.drawCircle(x, y, r * 1.22f, fillP)
        }
        fillP.color = withAlpha(col, alpha)
        c.drawCircle(x, y, r, fillP)
        if (r >= GLYPH_MIN_PX) glyph(c, s, x, y, r * 1.36f, withAlpha(0xFFFFFFFF.toInt(), alpha))
    }

    /** A response on its way back: white disc, pictogram and rim in the service color, so it reads as "answer". */
    fun response(c: Canvas, s: Service, x: Float, y: Float, r: Float) {
        val col = ServiceColors.of(s)
        fillP.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, r, fillP)
        rimP.color = col; rimP.strokeWidth = r * 0.24f
        c.drawCircle(x, y, r - rimP.strokeWidth / 2f, rimP)
        if (r >= GLYPH_MIN_PX) glyph(c, s, x, y, r * 1.2f, col)
    }

    /**
     * The sign of a server: a white disc of radius [r] with a rim in [rimColor] and the pictogram in the service color,
     * the same look as the service's legend entry.
     */
    fun sign(c: Canvas, s: Service, x: Float, y: Float, r: Float, rimColor: Int) {
        fillP.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, r, fillP)
        rimP.color = rimColor; rimP.strokeWidth = r * 0.16f
        c.drawCircle(x, y, r, rimP)
        glyph(c, s, x, y, r * 1.35f, ServiceColors.of(s))
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or ((((color ushr 24) * alpha) / 255) shl 24)
}
