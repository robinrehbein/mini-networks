package com.mininetworks.game

import android.os.StrictMode

/**
 * StrictMode for debug builds (docs/TOP100.md A3): every thread and VM check is on and logs to Logcat (tag
 * `StrictMode`), so disk or network access on the UI thread, leaked closeables and the like show up while developing.
 * Only logging, never a crash, and never in release builds: [MainActivity] calls [install] only when
 * `BuildConfig.DEBUG` is set.
 */
object DebugChecks {
    fun install() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}
