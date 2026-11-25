package com.umer.voicesched

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object Status {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val MISSED = "MISSED"
}

data class Item(
    val id: Long,
    val name: String,
    val number: String,
    val file: String,
    val mime: String,
    val durMs: Long,
    val timeMs: Long,
    val pkg: String,
    val status: String,
    val error: String?
)

object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun all(): List<Item> {
        val s = sp.getString("items", "[]") ?: "[]"
        val out = ArrayList<Item>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Item(
                        o.getLong("id"), o.getString("name"), o.getString("number"),
                        o.getString("file"), o.optString("mime", "audio/ogg"),
                        o.optLong("dur", 0L), o.getLong("time"),
                        o.optString("pkg", "com.whatsapp"), o.getString("status"),
                        if (o.isNull("error")) null else o.getString("error")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(list: List<Item>) {
        val a = JSONArray()
        for (it in list) {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("number", it.number)
                    .put("file", it.file).put("mime", it.mime).put("dur", it.durMs)
                    .put("time", it.timeMs).put("pkg", it.pkg).put("status", it.status)
                    .put("error", it.error ?: JSONObject.NULL)
            )
        }
        sp.edit().putString("items", a.toString()).apply()
    }

    @Synchronized
    fun get(id: Long): Item? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(i: Item) = save(all() + i)

    @Synchronized
    fun update(i: Item) = save(all().map { if (it.id == i.id) i else it })

    @Synchronized
    fun remove(id: Long) = save(all().filter { it.id != id })

    @Synchronized
    fun claim(id: Long): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.PENDING) return null
        val c = i.copy(status = Status.SENDING, error = null)
        update(c)
        return c
    }

    @Synchronized
    fun expireOverdue() {
        val now = System.currentTimeMillis()
        save(all().map {
            if (it.status == Status.PENDING && now - it.timeMs > 15 * 60_000L)
                it.copy(status = Status.MISSED, error = "Phone was off or app was stopped at send time")
            else if (it.status == Status.SENDING && now - it.timeMs > 10 * 60_000L)
                it.copy(status = Status.FAILED, error = "Sending was interrupted")
            else it
        })
    }
}

object Prefs {
    fun cc(ctx: Context): String =
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("cc", "92") ?: "92"

    fun setCc(ctx: Context, v: String) {
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("cc", v).apply()
    }
}

fun normalizeNumber(ctx: Context, raw: String): String {
    val t = raw.trim()
    val d = t.filter { it.isDigit() }
    if (d.isEmpty()) return ""
    val cc = Prefs.cc(ctx)
    return when {
        t.startsWith("+") -> d
        d.startsWith("00") -> d.drop(2)
        d.startsWith("0") -> cc + d.drop(1)
        d.length <= 10 -> cc + d
        else -> d
    }
}

object Wa {
    val PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")
    fun installed(ctx: Context): List<String> = PKGS.filter {
        try {
            ctx.packageManager.getPackageInfo(it, 0); true
        } catch (e: Exception) {
            false
        }
    }
}

object Files {
    class Imported(val file: File, val mime: String, val durMs: Long, val note: String?)

    fun newVoiceFile(ctx: Context, ext: String = "opus"): File {
        val d = File(ctx.filesDir, "voice")
        d.mkdirs()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return File(d, "PTT-$day-WA${Random.nextInt(1000, 9999)}.$ext")
    }

    fun duration(f: File): Long {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (e: Exception) {
            0L
        }
    }

    fun importAudio(ctx: Context, uri: Uri): Imported? {
        val out = newVoiceFile(ctx)
        val ok = try {
            OpusTranscoder.transcode(ctx, uri, out)
        } catch (e: Throwable) {
            false
        }
        if (ok && out.length() > 0) return Imported(out, "audio/ogg", duration(out), null)
        out.delete()
        val type = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "mp3"
        val raw = newVoiceFile(ctx, ext)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> raw.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            return null
        }
        if (raw.length() == 0L) return null
        return Imported(raw, type, duration(raw), "Could not convert to voice format. It will arrive as an audio file.")
    }
}

object Setup {
    fun a11yEnabled(ctx: Context): Boolean {
        val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(ctx, WhatsAppAccessibilityService::class.java)
        return v.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun notif(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun allOk(ctx: Context) = a11yEnabled(ctx) && overlay(ctx) && notif(ctx) && battery(ctx) &&
        Wa.installed(ctx).isNotEmpty()
}

object Scheduler {
    private fun pi(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, item: Item) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(item.timeMs, show), pi(ctx, item.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(ctx, id))
    }
}

object Notifs {
    const val CH_SEND = "send"
    const val CH_STATUS = "status"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SEND, "Sending voice notes", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun fg(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SEND).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Voice Scheduler").setContentText(text).setOngoing(true).build()

    fun fsi(ctx: Context, pi: PendingIntent) =
        NotificationCompat.Builder(ctx, CH_SEND).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sending voice note").setContentText("Opening WhatsApp")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true).setAutoCancel(true).build()

    fun result(ctx: Context, id: Long, title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_STATUS).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify((id % 100000).toInt() + 1000, n)
        } catch (e: Exception) {
        }
    }
}

object Automation {
    @Volatile var activeId: Long = -1
    @Volatile var pkg: String = ""
    @Volatile var launched = false
    @Volatile var launchedAt = 0L
    @Volatile var clicked = false

    fun begin(id: Long, p: String) {
        pkg = p; launched = false; clicked = false; launchedAt = 0L; activeId = id
    }

    fun markLaunched() {
        launchedAt = SystemClock.elapsedRealtime(); launched = true
    }

    fun end() {
        activeId = -1
    }
}

object Player {
    private var mp: MediaPlayer? = null

    fun play(path: String, onDone: () -> Unit) {
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { onDone() }
            p.prepare()
            p.start()
            mp = p
        } catch (e: Exception) {
            onDone()
        }
    }

    fun stop() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
    }
}
