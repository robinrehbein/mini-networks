package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Crash soak (docs/TOP100.md A1): [GAMES] games of [GAME_SECONDS] simulated seconds (30 in-game minutes) each, at the
 * game's own step of 1/60 s, with random player input mixed into a [GreedyBot] so the networks grow into the late
 * game instead of dying in week 2. The random input covers every player action of [World] — laying cables (also
 * invalid ones), removing, upgrading and repairing them, upgrading servers, placing and picking up routers, placing
 * radios, switching WLAN channels and 5 GHz, rewards, the bonus router, continue after game over — plus a save and
 * load round trip through [Save] every few seconds, after which the game goes on with the loaded world.
 *
 * Every game has its own fixed seed ([SoakRun.play]), so a failure names the game and replays exactly. A game that is
 * lost for good starts again on the same scenery with the next seed and keeps counting towards its 30 minutes; the
 * games cycle through every scenery.
 */
@OptIn(DebugApi::class)
class SoakTest {

    @Test
    fun twentyGamesOfThirtyMinutesRunWithoutException() {
        val started = System.nanoTime()
        val runs = (0 until GAMES).toList().parallelStream().map { SoakRun.play(it, GAME_SECONDS) }.toList()
        val seconds = (System.nanoTime() - started) / 1e9
        for (r in runs) {
            assertTrue("game ${r.game}: only ${r.simulated} s simulated", r.simulated >= GAME_SECONDS)
            assertTrue("game ${r.game}: no save round trip", r.saves > 0)
            assertTrue("game ${r.game}: too few random actions (${r.actions})", r.actions > 1000)
        }
        println(
            "SoakTest: $GAMES games x ${GAME_SECONDS.toInt()} s in %.1f s wall time; ".format(seconds) +
                "actions ${runs.sumOf { it.actions }}, saves ${runs.sumOf { it.saves }}, " +
                "restarts ${runs.sumOf { it.restarts }}, max week ${runs.maxOf { it.maxWeek }}, max nodes ${runs.maxOf { it.maxNodes }}",
        )
    }

    /** Same game number, same result: the soak replays exactly, so a crash it finds can be debugged. */
    @Test
    fun soakGamesAreDeterministic() {
        val a = SoakRun.play(3, SHORT_SECONDS)
        val b = SoakRun.play(3, SHORT_SECONDS)
        assertEquals(a.finalSave, b.finalSave)
        assertEquals(a.actions, b.actions)
    }

    private companion object {
        const val GAMES = 20
        const val GAME_SECONDS = 30f * 60f
        const val SHORT_SECONDS = 120f
    }
}

class SoakRun(
    val game: Int,
    val simulated: Float,
    val actions: Int,
    val saves: Int,
    val restarts: Int,
    val maxWeek: Int,
    val maxNodes: Int,
    val finalSave: String,
) {
    @OptIn(DebugApi::class)
    companion object {
        private const val STEP = 1f / 60f
        /** Random input and the bot act this often, in game seconds. */
        private const val THINK_SECONDS = 0.5f
        /** Save and load about this often, in game seconds. */
        private const val SAVE_SECONDS = 15f

        fun play(game: Int, seconds: Float): SoakRun {
            val scenario = Scenarios.all[game % Scenarios.all.size]
            val rnd = Random(1000L + game)
            var seed = 1L + game * 100L
            var w = World(scenario, seed = seed)
            var bot = GreedyBot(w)
            var simulated = 0f
            var think = 0f
            var saveIn = SAVE_SECONDS
            var actions = 0
            var saves = 0
            var restarts = 0
            var maxWeek = 0
            var maxNodes = 0
            try {
                while (simulated < seconds) {
                    if (w.gameOver) {
                        if (rnd.nextInt(3) != 0 && w.continueAfterGameOver()) continue
                        seed++
                        restarts++
                        w = World(scenario, seed = seed)
                        bot = GreedyBot(w)
                        continue
                    }
                    if (w.rewardOffer != null) {
                        if (rnd.nextInt(4) == 0) w.claimBonusRouter()
                        // Sometimes an index out of range first: it must be refused, not crash.
                        if (rnd.nextInt(8) == 0) check(!w.chooseReward(rnd.nextInt(3, 9)))
                        if (rnd.nextBoolean()) bot.chooseReward() else w.chooseReward(rnd.nextInt(3))
                        actions++
                        continue
                    }
                    think -= STEP
                    if (think <= 0f) {
                        think = THINK_SECONDS
                        if (rnd.nextInt(10) < 7) bot.act()
                        repeat(1 + rnd.nextInt(3)) { randomAction(w, rnd); actions++ }
                        // Keep money and stock flowing now and then, so the late game with big networks gets played too.
                        if (rnd.nextInt(40) == 0) w.grant(rnd.nextInt(50, 400), rnd.nextInt(0, 3), rnd.nextInt(0, 2), rnd.nextInt(0, 2))
                    }
                    val before = w.time
                    w.update(STEP)
                    simulated += w.time - before
                    saveIn -= STEP
                    if (saveIn <= 0f) {
                        saveIn = SAVE_SECONDS * (0.5f + rnd.nextFloat())
                        val text = Save.encode(w)
                        val loaded = assertNotNull("game $game: own save does not load", Save.decode(text))
                        assertEquals("game $game: save round trip changed the world", text, Save.encode(loaded))
                        w = loaded
                        bot = GreedyBot(w)
                        saves++
                    }
                    maxWeek = maxOf(maxWeek, w.week)
                    maxNodes = maxOf(maxNodes, w.nodes.size)
                }
            } catch (e: Throwable) {
                throw AssertionError("soak game $game (${scenario.id}, world seed $seed) crashed at t=${w.time}", e)
            }
            return SoakRun(game, simulated, actions, saves, restarts, maxWeek, maxNodes, Save.encode(w))
        }

        private fun <T> assertNotNull(message: String, value: T?): T {
            org.junit.Assert.assertNotNull(message, value)
            return value!!
        }

        /** One random player action; about a fifth of them are aimed at invalid targets on purpose. */
        private fun randomAction(w: World, rnd: Random) {
            val nodes = w.nodes
            val cables = w.cables
            fun anyNode() = nodes.randomOrNull(rnd)
            fun anyCable() = cables.randomOrNull(rnd)
            // Cells somewhat outside the grid as well, as a stray touch at the map edge would give.
            fun anyX() = rnd.nextInt(-2, w.cols + 2)
            fun anyY() = rnd.nextInt(-2, w.rows + 2)
            when (rnd.nextInt(14)) {
                0, 1, 2 -> {
                    val a = anyNode() ?: return
                    val b = (if (rnd.nextInt(5) == 0) a else anyNode()) ?: return
                    w.connectError(a, b, CableType.entries.random(rnd))
                    w.connect(a, b, CableType.entries.random(rnd), Bend.entries.random(rnd).takeIf { rnd.nextBoolean() })
                }
                3 -> anyCable()?.let { if (rnd.nextInt(3) == 0) w.removeCable(it) }
                4 -> anyCable()?.let { c -> CableType.entries.random(rnd).let { w.upgradeError(c, it); w.upgrade(c, it) } }
                5 -> anyCable()?.let { w.repairError(it); w.repair(it) }
                6 -> anyNode()?.let { w.serverUpgradeError(it); w.upgradeServer(it) }
                7 -> { w.placeError(NodeKind.ROUTER, anyX(), anyY()); w.placeRouter(anyX(), anyY()) }
                8 -> anyNode()?.let { w.pickUpError(it); w.pickUp(it) }
                9 -> w.placeRadio(RadioType.entries.random(rnd), anyX(), anyY())
                10 -> anyNode()?.let { n -> w.cycleChannel(n); if (rnd.nextInt(4) == 0) { w.wifiUpgradeError(n); w.upgradeTo5Ghz(n) } }
                11 -> {
                    // What the HUD reads every frame, also for nodes and cables that were just removed.
                    anyNode()?.let { n ->
                        w.interferers(n); w.radioCapacity(n); w.radioSlots(n); w.waitingAt(n); w.serverBusy(n)
                        if (n.kind == NodeKind.CLIENT) {
                            for (s in n.device!!.services) { w.routeProblem(n, s); w.bestRoute(n, s) }
                            anyNode()?.let { o -> w.checkCable(n, o, CableType.entries.random(rnd)) }
                        }
                    }
                    anyCable()?.let { w.cableLoad(it); w.refundOf(it) }
                    w.failure; w.eligibleRewards(); w.nearestFree(Cell(anyX(), anyY())); w.nodeAt(Cell(anyX(), anyY()))
                    for (p in w.packets.take(8)) w.packetPosition(p)
                }
                12 -> anyNode()?.let { n -> w.planLayout(n.cell, Cell(anyX(), anyY()), Bend.entries.random(rnd)) }
                else -> if (rnd.nextInt(6) == 0) {
                    // Extra incidents on top of the planned ones (debug hooks; they need a target without one).
                    if (rnd.nextBoolean()) {
                        anyCable()?.takeIf { c -> w.incidents.none { it.cable === c } }?.let { w.announceExcavator(it) }
                    } else {
                        anyNode()?.takeIf { n -> n.kind != NodeKind.CLIENT && w.incidents.none { it.node === n } }?.let { w.announcePowerOutage(it) }
                    }
                }
            }
        }
    }
}
