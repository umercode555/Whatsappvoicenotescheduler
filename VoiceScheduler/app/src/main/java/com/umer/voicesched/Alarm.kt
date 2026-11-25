package com.umer.voicesched

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/** Fires when a task's time arrives. Turns PENDING into AWAITING exactly once, then rings. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val cur = Store.get(id) ?: return
        // stale alarm (task was rescheduled / already handled): ignore
        if (cur.status != Status.PENDING || cur.timeMs > System.currentTimeMillis() + 30_000L) return
        val item = Store.fire(id) ?: return
        AlarmService.ring(context, item.id, true)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Store.interruptSending()
        Recovery.run(context, true)
    }
}

/** Idempotent "make the world match the stored state" used on boot and whenever the app opens. */
object Recovery {
    fun run(ctx: Context, soundForWaiting: Boolean) {
        Store.init(ctx)
        val before = Store.all().filter { it.status == Status.AWAITING || it.status == Status.PENDING }
        Store.expireOverdue()
        val now = System.currentTimeMillis()
        for (b in before) {
            val it = Store.get(b.id) ?: continue
            if (b.status == Status.AWAITING && it.status == Status.MISSED)
                Notifs.result(ctx, it.id, "Voice note missed", "Not approved in time: " + it.name)
            if (b.status == Status.PENDING && it.status == Status.MISSED)
                Notifs.result(ctx, it.id, "Voice note missed", "Phone was off at send time: " + it.name)
        }
        for (it in Store.all()) {
            when (it.status) {
                Status.PENDING -> {
                    if (it.timeMs > now) {
                        try { Scheduler.schedule(ctx, it) } catch (e: Exception) {}
                    } else {
                        Store.fire(it.id)?.let { f -> AlarmService.ring(ctx, f.id, true) }
                    }
                }
                Status.AWAITING -> AlarmService.ring(ctx, it.id, soundForWaiting)
            }
        }
    }
}

/**
 * Keeps the alarm sound + persistent approval notification alive while a task is AWAITING.
 * - Plays the chosen sound on the alarm stream (works with screen off / app closed).
 * - Notification uses a full-screen intent so the approval screen appears over the lock screen.
 * - When the phone is unlocked later, the approval screen is shown immediately (USER_PRESENT).
 */
class AlarmService : Service() {
    companion object {
        private const val FG_ID = 79
        @Volatile private var instance: AlarmService? = null

        fun ring(ctx: Context, id: Long, sound: Boolean) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, AlarmService::class.java).putExtra("id", id).putExtra("snd", sound)
                )
            } catch (e: Exception) {
                // can't start the service: still tell the user with a plain notification
                Store.get(id)?.let { Notifs.approval(ctx, it, Store.contact(it.contactId)?.name ?: it.name) }
            }
        }

        fun silence() { instance?.stopSound() }
        fun changed() { instance?.syncNow() }
    }

    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var ringing = false
    private var presentRegistered = false
    private val posted = HashSet<Long>()
    private val sounded = HashSet<Long>()

    private val soundStopper = Runnable { stopSound() }
    private val janitor = object : Runnable {
        override fun run() {
            val before = Store.all().filter { it.status == Status.AWAITING }
            Store.expireOverdue()
            for (b in before) {
                val n = Store.get(b.id)
                if (n != null && n.status == Status.MISSED)
                    Notifs.result(this@AlarmService, n.id, "Voice note missed", "Not approved in time: " + n.name)
            }
            syncNow()
            if (instance != null) h.postDelayed(this, 30_000)
        }
    }

    private val present = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val w = Store.all().filter { it.status == Status.AWAITING }.minByOrNull { it.timeMs } ?: return
            try { c.startActivity(Notifs.approvalIntent(c, w.id)) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        instance = this
        startForeground(FG_ID, Notifs.ringing(this))
        if (!presentRegistered) {
            ContextCompat.registerReceiver(
                this, present, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            presentRegistered = true
        }
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        val snd = intent?.getBooleanExtra("snd", true) ?: false
        val item = if (id >= 0) Store.get(id) else null
        if (item != null && item.status == Status.AWAITING && snd && sounded.add(id)) {
            wakeScreen()
            startSound(item)
            val km = getSystemService(KeyguardManager::class.java)
            if (!km.isKeyguardLocked) {
                try { startActivity(Notifs.approvalIntent(this, id)) } catch (e: Exception) {}
            }
        }
        syncNow()
        h.removeCallbacks(janitor)
        h.postDelayed(janitor, 30_000)
        return START_STICKY
    }

    fun syncNow() {
        val waiting = Store.all().filter { it.status == Status.AWAITING }
        val ids = waiting.map { it.id }.toSet()
        for (w in waiting) {
            if (posted.add(w.id)) Notifs.approval(this, w, Store.contact(w.contactId)?.name ?: w.name)
        }
        for (p in posted.toList()) {
            if (p !in ids) { Notifs.cancelApproval(this, p); posted.remove(p) }
        }
        sounded.retainAll(ids)
        if (waiting.isEmpty()) shutdown()
    }

    private fun shutdown() {
        stopSound()
        h.removeCallbacks(janitor)
        if (presentRegistered) {
            try { unregisterReceiver(present) } catch (e: Exception) {}
            presentRegistered = false
        }
        instance = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE, "voicesched:ring"
            ).acquire(15_000L)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun startSound(item: Item) {
        if (ringing) return
        ringing = true
        val path = Prefs.alarmFor(this, Store.contact(item.contactId))
        if (Prefs.vibrate(this)) {
            try {
                val v = getSystemService(Vibrator::class.java)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
            } catch (e: Exception) {
            }
        }
        if (path != null) {
            var p = if (path.isNotEmpty()) tryPlayer { it.setDataSource(path) } else null
            if (p == null) {
                p = tryPlayer {
                    it.setDataSource(
                        this,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    )
                }
            }
            player = p
        }
        h.removeCallbacks(soundStopper)
        h.postDelayed(soundStopper, Prefs.ringSec(this) * 1000L)
    }

    private fun tryPlayer(src: (MediaPlayer) -> Unit): MediaPlayer? {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            src(p)
            p.isLooping = true
            p.prepare()
            p.start()
            p
        } catch (e: Exception) {
            try { p.release() } catch (x: Exception) {}
            null
        }
    }

    @Suppress("DEPRECATION")
    fun stopSound() {
        h.removeCallbacks(soundStopper)
        try { player?.stop() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        player = null
        try { getSystemService(Vibrator::class.java).cancel() } catch (e: Exception) {}
        // mark everything currently waiting as already announced so it won't restart on re-open
        for (w in Store.all()) if (w.status == Status.AWAITING) sounded.add(w.id)
        ringing = false
    }

    override fun onDestroy() {
        stopSound()
        h.removeCallbacks(janitor)
        if (presentRegistered) {
            try { unregisterReceiver(present) } catch (e: Exception) {}
            presentRegistered = false
        }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
