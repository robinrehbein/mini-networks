package com.mininetworks.game.ui

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * TalkBack and other accessibility services for the canvas-drawn UI (docs/TOP100.md A7): every menu entry, HUD
 * button, scenery card, reward card and text of the last frame is a virtual view with its bounds, text, role
 * (button, switch, heading) and state. Explore-by-touch finds the element under the finger, double tap activates it
 * like a tap would. The framework's [AccessibilityNodeProvider] is used directly, as the app has no AndroidX UI
 * libraries.
 *
 * Threads: the game thread hands each frame's elements to [update]; everything else runs on the UI thread and only
 * reads the published snapshot. [activate] (double tap) and [onFocus] (the service's focus moved onto an element) are
 * called on the UI thread with the element's [UiNode.key]; the caller hands them on to the game thread.
 */
class CanvasAccessibility(
    private val host: View,
    private val activate: (String) -> Unit,
    private val onFocus: (String) -> Unit = {},
) : AccessibilityNodeProvider() {

    /** One published element with its virtual view id. */
    private class Entry(val id: Int, val node: UiNode)

    @Volatile private var entries: List<Entry> = emptyList()

    /** Stable virtual view ids per key, so TalkBack's focus survives frames; game thread only. */
    private val ids = HashMap<String, Int>()
    private var lastNodes: List<UiNode> = emptyList()

    /** The element with accessibility focus and the one under a hovering finger; UI thread only. */
    private var focused = INVALID
    private var hovered = INVALID

    private val manager = host.context.getSystemService(AccessibilityManager::class.java)

    /** Set in tests to publish elements without a running accessibility service. */
    @Volatile internal var forceActive = false

    /** True while an accessibility service listens; only then does the game thread collect elements. */
    val active: Boolean get() = forceActive || manager?.isEnabled == true

    /** The published elements, for tests. */
    val nodes: List<UiNode> get() = entries.map { it.node }

    /**
     * Publishes the elements of a frame (game thread). When they changed, the UI thread tells the services; a changed
     * text of a [LIVE] element (the hint line) is announced.
     */
    fun update(nodes: List<UiNode>) {
        if (nodes == lastNodes) return
        val before = lastNodes
        lastNodes = nodes
        entries = nodes.map { Entry(ids.getOrPut(it.key) { ids.size + 1 }, it) }
        val announce = nodes.firstOrNull { it.key == LIVE }?.takeIf { live -> before.none { it.key == LIVE && it.text == live.text } }
        val liveId = announce?.let { ids[it.key] }
        host.post {
            if (!active) return@post
            if (focused != INVALID && entries.none { it.id == focused }) focused = INVALID
            val changed = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            changed.setSource(host)
            changed.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            send(changed)
            if (liveId != null) send(eventFor(liveId, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)?.apply {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
            })
        }
    }

    /** The virtual view id of the smallest element at ([x], [y]) in view coordinates, or [INVALID]. */
    fun idAt(x: Float, y: Float): Int =
        entries.filter { it.node.bounds.contains(x, y) }.minByOrNull { it.node.bounds.width() * it.node.bounds.height() }?.id ?: INVALID

    /** The virtual view id of the element [key], or [INVALID]. */
    fun idOf(key: String): Int = entries.firstOrNull { it.node.key == key }?.id ?: INVALID

    /** Explore by touch: announces the element under a hovering finger. Returns true if the event was used. */
    fun onHover(e: MotionEvent): Boolean {
        if (manager?.isTouchExplorationEnabled != true && !forceActive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val id = idAt(e.x, e.y)
                setHovered(id)
                return id != INVALID
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                setHovered(INVALID)
                return true
            }
        }
        return false
    }

    private fun setHovered(id: Int) {
        if (id == hovered) return
        val old = hovered
        hovered = id
        if (id != INVALID) send(eventFor(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
        if (old != INVALID) send(eventFor(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT))
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == View.NO_ID) {
            val info = newInfo(null)
            host.onInitializeAccessibilityNodeInfo(info)
            for (e in entries) info.addChild(host, e.id)
            return info
        }
        val entry = entries.firstOrNull { it.id == virtualViewId } ?: return null
        val n = entry.node
        val info = newInfo(entry.id)
        info.packageName = host.context.packageName
        info.className = when (n.kind) {
            UiNode.Kind.BUTTON -> "android.widget.Button"
            UiNode.Kind.TOGGLE -> "android.widget.Switch"
            UiNode.Kind.TEXT, UiNode.Kind.HEADING -> "android.widget.TextView"
        }
        info.setParent(host)
        val bounds = Rect()
        n.bounds.roundOut(bounds)
        @Suppress("DEPRECATION")
        info.setBoundsInParent(bounds)
        val onScreen = IntArray(2)
        host.getLocationOnScreen(onScreen)
        bounds.offset(onScreen[0], onScreen[1])
        info.setBoundsInScreen(bounds)
        info.text = n.text
        info.isEnabled = n.enabled
        info.isVisibleToUser = true
        info.isSelected = n.selected
        if (n.kind == UiNode.Kind.TOGGLE) {
            info.isCheckable = true
            @Suppress("DEPRECATION")
            info.isChecked = n.checked
        }
        if (n.kind == UiNode.Kind.HEADING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.isHeading = true
        if (n.key == LIVE) info.liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (n.actionable) {
            info.isClickable = true
            info.isFocusable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        if (focused == entry.id) {
            info.isAccessibilityFocused = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == View.NO_ID) return host.performAccessibilityAction(action, arguments)
        val entry = entries.firstOrNull { it.id == virtualViewId } ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused != entry.id) {
                    val old = focused
                    focused = entry.id
                    if (old != INVALID) send(eventFor(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
                    send(eventFor(entry.id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
                    onFocus(entry.node.key)
                    host.invalidate()
                }
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused == entry.id) {
                    focused = INVALID
                    send(eventFor(entry.id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
                }
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                if (!entry.node.actionable) return false
                activate(entry.node.key)
                send(eventFor(entry.id, AccessibilityEvent.TYPE_VIEW_CLICKED))
                true
            }
            else -> false
        }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focused != INVALID) createAccessibilityNodeInfo(focused) else null

    private fun newInfo(virtualId: Int?): AccessibilityNodeInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (virtualId == null) AccessibilityNodeInfo(host) else AccessibilityNodeInfo(host, virtualId)
    } else {
        @Suppress("DEPRECATION")
        if (virtualId == null) AccessibilityNodeInfo.obtain(host) else AccessibilityNodeInfo.obtain(host, virtualId)
    }

    private fun event(type: Int): AccessibilityEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AccessibilityEvent(type)
    } else {
        @Suppress("DEPRECATION")
        AccessibilityEvent.obtain(type)
    }

    private fun eventFor(id: Int, type: Int): AccessibilityEvent? {
        val n = entries.firstOrNull { it.id == id }?.node ?: return null
        return event(type).apply {
            setSource(host, id)
            packageName = host.context.packageName
            className = if (n.actionable) "android.widget.Button" else "android.widget.TextView"
            text.add(n.text)
            isEnabled = n.enabled
        }
    }

    private fun send(event: AccessibilityEvent?) {
        if (event == null || !active) return
        if (event.packageName == null) event.packageName = host.context.packageName
        host.parent?.requestSendAccessibilityEvent(host, event)
    }

    companion object {
        /** No element. */
        const val INVALID = Int.MIN_VALUE
        /** The element whose text changes are announced: the HUD's hint line. */
        const val LIVE = "hud:hint"
    }
}
