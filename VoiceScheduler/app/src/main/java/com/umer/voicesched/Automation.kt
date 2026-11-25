package com.umer.voicesched

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Taps WhatsApp's Send button once a scheduled voice note has been handed to WhatsApp. */
class WhatsAppAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: WhatsAppAccessibilityService? = null

        private val SEND_WORDS = setOf("send", "send audio", "إرسال", "ارسال", "بھیجیں", "भेजें")
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Automation.activeId >= 0 && !Automation.clicked) attempt()
    }

    /** Called by SendService on a timer so we never depend on events alone. */
    fun nudge() {
        if (Automation.activeId >= 0 && !Automation.clicked) h.post { attempt() }
    }

    fun goHome() {
        h.post { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun attempt() {
        try {
            if (!Automation.launched) return
            if (SystemClock.elapsedRealtime() - Automation.launchedAt < 1200) return
            val root = rootInActiveWindow ?: return
            val p = root.packageName?.toString() ?: return
            if (p != Automation.pkg) return
            val n = findSend(root, p) ?: return
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Automation.clicked = true
            }
        } catch (e: Exception) {
        }
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in listOf("$pkg:id/send", "$pkg:id/send_btn")) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            val hit = l?.firstOrNull { it.isVisibleToUser }
            if (hit != null) return hit
        }
        return dfs(root, 0)
    }

    private fun dfs(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        val t = n.text?.toString()?.trim()?.lowercase()
        if (n.isVisibleToUser && ((d != null && d in SEND_WORDS) || (t != null && t in SEND_WORDS))) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = dfs(c, depth + 1)
            if (r != null) return r
        }
        return null
    }
}
