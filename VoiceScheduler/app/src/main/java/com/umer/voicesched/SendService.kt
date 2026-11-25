package com.umer.voicesched

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
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
    /** Hands the voice file to WhatsApp, straight into the chosen chat. */
    fun launch(ctx: Context, id: Long): Boolean {
        val item = Store.get(id) ?: return false
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", File(item.file))
        val i = Intent(Intent.ACTION_SEND)
        i.type = item.mime
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
        return true
    }
}

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getLongExtra("id", -1L)
        val go = {
            try {
                Launcher.launch(this, id)
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

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        try {
            SendService.start(context, id)
        } catch (e: Exception) {
            Store.get(id)?.let {
                Store.update(it.copy(status = Status.FAILED, error = "Android blocked background start: " + e.message))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val now = System.currentTimeMillis()
        for (it in Store.all().filter { s -> s.status == Status.PENDING || s.status == Status.SENDING }) {
            if (it.timeMs > now) {
                Scheduler.schedule(context, it)
            } else if (now - it.timeMs < 15 * 60_000L) {
                Store.update(it.copy(status = Status.PENDING))
                try {
                    SendService.start(context, it.id)
                } catch (e: Exception) {
                    Store.update(it.copy(status = Status.MISSED, error = "Phone was off at send time"))
                }
            } else {
                Store.update(it.copy(status = Status.MISSED, error = "Phone was off at send time"))
            }
        }
    }
}

class SendService : Service() {
    companion object {
        private const val FG_ID = 77
        private const val FSI_ID = 78

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
                    stopForeground(true)
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
        val it = Store.get(id) ?: return
        Store.update(it.copy(status = Status.FAILED, error = msg))
        Notifs.result(this, id, "Voice note failed: " + it.name, msg)
    }

    private fun openWhatsApp(id: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        val wake = Intent(this, WakeActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, id.toInt(), wake, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try { nm.notify(FSI_ID, Notifs.fsi(this, pi)) } catch (e: Exception) {}
        try { startActivity(wake) } catch (e: Exception) {}
        if (!waitFor(4000) { Automation.launched }) {
            try { Launcher.launch(this, id) } catch (e: Exception) {}
        }
        nm.cancel(FSI_ID)
    }

    private fun process(id: Long) {
        val item = Store.claim(id) ?: return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "voicesched:send"
        )
        wl.acquire(4 * 60_000L)
        try {
            if (!File(item.file).exists()) return fail(id, "Audio file is missing")
            if (!Wa.installed(this).contains(item.pkg)) return fail(id, "WhatsApp is not installed")
            if (!Setup.a11yEnabled(this)) return fail(id, "Accessibility service is off. Enable it in setup.")

            var ok = false
            for (attempt in 1..2) {
                Automation.begin(id, item.pkg)
                openWhatsApp(id)
                val done = waitFor(40_000) {
                    WhatsAppAccessibilityService.instance?.nudge()
                    Automation.clicked
                }
                if (done) { ok = true; break }
            }
            if (ok) {
                Thread.sleep(5000)
                WhatsAppAccessibilityService.instance?.goHome()
                Store.update(item.copy(status = Status.SENT, error = null))
                Notifs.result(this, id, "Voice note sent", "To " + item.name)
            } else {
                fail(
                    id,
                    "Could not press Send in WhatsApp. Check: phone not PIN-locked, accessibility on, number has WhatsApp."
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
