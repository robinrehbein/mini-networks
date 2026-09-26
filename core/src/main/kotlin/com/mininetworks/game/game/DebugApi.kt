package com.mininetworks.game.game

/**
 * Marks debug/test hooks that bypass the game rules (e.g. granting budget, jumping weeks).
 * Public so tests in other modules and a future debug menu can use them; gameplay code must not.
 */
@RequiresOptIn(message = "Debug/test API that bypasses the game rules.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class DebugApi
