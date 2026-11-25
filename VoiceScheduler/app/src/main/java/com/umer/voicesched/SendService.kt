package com.umer.voicesched

import android.app.Activity
import android.app.KeyguardManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Launcher {
    /** Always the native voice-note mime for Ogg/Opus files, whatever the stored value says. */
    fun voiceMime(item: Item): String {
        val n = item.file.lowercase()
        return if (n.endsWith(".opus") || n.endsWith(".ogg")) "audio/ogg" else item.mime
    }

    /**
     * Hands the voice file to WhatsApp, straight into the chosen chat (same intent as before).
     * Guarded by [Store.markLaunched]: it succeeds at most ONCE per approved task, so replayed
     * notifications / activities / retries can never open a second share for the same note.
     */
    fun launchOnce(ctx: Context, id: Long): Boolean {
        if (!Store.markLaunched(id)) return false
        val item = Store.get(id) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", File(item.file))
            val i = Intent(Intent.ACTION_SEND)
            i.type = voiceMime(item)
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra("jid", item.number + "@s.whatsapp.net")
            i.setPackage(item.pkg)
            i.clipData = ClipData.newRawUri("voice", uri)
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            ctx.startActivity(i)
            Automation.markLaunched()
            true
        } catch (e: Exception) {
            // WhatsApp was never opened, so another attempt is still safe
            Store.unmarkLaunched(id)
            false
        }
    }
}

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)
        val id = intent.getLongExtra("id", -1L)
        val go = {
            try {
                Launcher.launchOnce(this, id)
            } catch (e: Exception) {
            }
            finish()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
                override fun onDismissCancelled() { go() }
                override fun onDismissError() { go() }
            })
        } else {
            go()
        }
    }
}

class SendService : Service() {
    companion object {
        private const val FG_ID = 77

        fun start(ctx: Context, id: Long) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, SendService::class.java).putExtra("id", id)
            )
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        startForeground(FG_ID, Notifs.fg(this, "Sending voice note"))
        active.incrementAndGet()
        executor.execute {
            try {
                process(id)
            } catch (e: Throwable) {
                fail(id, "Unexpected error: " + e.message)
            } finally {
                if (active.decrementAndGet() <= 0) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return true
            Thread.sleep(300)
        }
        return cond()
    }

    private fun fail(id: Long, msg: String) {
        val it = Store.finish(id, false, msg) ?: return
        Notifs.result(this, id, "Voice note failed: " + it.name, msg)
    }

    private fun openWhatsApp(id: Long) {
        val wake = Intent(this, WakeActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(wake) } catch (e: Exception) {}
        if (!waitFor(4000) { Automation.launched }) {
            // no-op if WakeActivity already launched it
            try { Launcher.launchOnce(this, id) } catch (e: Exception) {}
        }
    }

    private fun process(id: Long) {
        val item = Store.get(id) ?: return
        // only an approved task that has not been handed to WhatsApp yet may run; anything else is a replay
        if (item.status != Status.SENDING || item.launched) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "voicesched:send"
        )
        wl.acquire(4 * 60_000L)
        try {
            val f = File(item.file)
            if (!f.exists()) return fail(id, "Audio file is missing")
            if (!Files.isOgg(f)) return fail(id, "Audio is not in voice-note (Opus) format. Re-add the voice note.")
            if (!Wa.installed(this).contains(item.pkg)) return fail(id, "WhatsApp is not installed")
            if (!Setup.a11yEnabled(this)) return fail(id, "Accessibility service is off. Enable it in Settings.")

            Automation.begin(id, item.pkg)
            openWhatsApp(id)
            val cur = Store.get(id)
            if (cur == null || !cur.launched) return fail(id, "Could not open WhatsApp")
            // ONE launch, ONE wait. No second launch: a retry here is what could double-send.
            val done = waitFor(45_000) {
                WhatsAppAccessibilityService.instance?.nudge()
                Automation.clicked
            }
            if (done) {
                // mark completed immediately and durably, before anything else can run
                Store.finish(id, true, null)
                Notifs.result(this, id, "Voice note sent", "To " + item.name, true)
                Thread.sleep(5000)
                WhatsAppAccessibilityService.instance?.goHome()
            } else {
                fail(
                    id,
                    "Send was not confirmed. Check the chat before retrying (it may have been delivered)."
                )
            }
        } finally {
            Automation.end()
            try { if (wl.isHeld) wl.release() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
