package com.mininetworks.game.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Scenes
import com.mininetworks.game.ui.menu.Screen

/**
 * A view whose game just ended the way a player sees it: the town of [Scenes.hud] played until a phone without a
 * server overloads, so the result card shows the recorded time-lapse and the reason for the loss.
 */
@OptIn(DebugApi::class)
internal fun lostGameView(context: Context, setUp: (GameView) -> Unit = {}): GameView {
    val world = Scenes.hud()
    val phone = world.addClient(Device.PHONE, 8, 9)
    repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
    val view = GameView(context)
    setUp(view)
    val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
    var guard = 0
    while (view.currentScreen != Screen.GAME_OVER) {
        view.advance(1f / 60f)
        check(guard++ < 60 * 60) { "no game over within a minute" }
    }
    // Let an achievement toast of the first game over pass: while it shows, the card moves below it for a moment.
    repeat(60 * 8) { view.advance(1f / 60f) }
    bmp.recycle()
    return view
}
