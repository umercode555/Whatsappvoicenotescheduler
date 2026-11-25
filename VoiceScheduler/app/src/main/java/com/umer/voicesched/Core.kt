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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Task lifecycle (one-way, enforced atomically in [Store]):
 *
 *   PENDING --alarm fires--> AWAITING --user approves--> SENDING --> SENT | FAILED
 *      ^                        |  \--skip--> MISSED
 *      \------- snooze ---------/
 *
 * A task can enter SENDING only from AWAITING, and WhatsApp can be launched for it only once
 * ([Store.markLaunched]). Every trigger (alarm, notification, boot, app open, retries) goes through
 * these checks, so the same voice note can never be sent twice.
 */
object Status {
    const val PENDING = "PENDING"
    const val AWAITING = "AWAITING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val MISSED = "MISSED"
}

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val pkg: String,
    val avatar: String?,
    val sound: String?,
    val soundName: String?
)

data class Item(
    val id: Long,
    val contactId: Long,
    val name: String,
    val number: String,
    val file: String,
    val mime: String,
    val durMs: Long,
    val timeMs: Long,
    val pkg: String,
    val status: String,
    val error: String?,
    val launched: Boolean = false,
    val stateAt: Long = 0L
)

object Store {
    private lateinit var sp: SharedPreferences
    private var items: List<Item> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        sp = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        contacts = readContacts(sp.getString("contacts", "[]") ?: "[]")
        items = readItems(sp.getString("items", "[]") ?: "[]")
        migrate()
        loaded = true
    }

    private fun now() = System.currentTimeMillis()

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else getString(k).takeIf { it.isNotEmpty() }

    private fun readItems(s: String): List<Item> {
        val out = ArrayList<Item>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                try {
                    val o = a.getJSONObject(i)
                    out.add(
                        Item(
                            id = o.getLong("id"), contactId = o.optLong("cid", 0L),
                            name = o.getString("name"), number = o.getString("number"),
                            file = o.getString("file"), mime = o.optString("mime", "audio/ogg"),
                            durMs = o.optLong("dur", 0L), timeMs = o.getLong("time"),
                            pkg = o.optString("pkg", "com.whatsapp"), status = o.getString("status"),
                            error = o.str("error"), launched = o.optBoolean("launched", false),
                            stateAt = o.optLong("stateAt", o.getLong("time"))
                        )
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun readContacts(s: String): List<Contact> {
        val out = ArrayList<Contact>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                try {
                    val o = a.getJSONObject(i)
                    out.add(
                        Contact(
                            o.getLong("id"), o.getString("name"), o.getString("number"),
                            o.optString("pkg", "com.whatsapp"), o.str("avatar"), o.str("sound"), o.str("soundName")
                        )
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** commit() (not apply()) so a state change is on disk before we act on it. */
    private fun persist() {
        val ia = JSONArray()
        for (it in items) {
            ia.put(
                JSONObject().put("id", it.id).put("cid", it.contactId).put("name", it.name)
                    .put("number", it.number).put("file", it.file).put("mime", it.mime).put("dur", it.durMs)
                    .put("time", it.timeMs).put("pkg", it.pkg).put("status", it.status)
                    .put("error", it.error ?: JSONObject.NULL).put("launched", it.launched)
                    .put("stateAt", it.stateAt)
            )
        }
        val ca = JSONArray()
        for (c in contacts) {
            ca.put(
                JSONObject().put("id", c.id).put("name", c.name).put("number", c.number).put("pkg", c.pkg)
                    .put("avatar", c.avatar ?: JSONObject.NULL).put("sound", c.sound ?: JSONObject.NULL)
                    .put("soundName", c.soundName ?: JSONObject.NULL)
            )
        }
        sp.edit().putString("items", ia.toString()).putString("contacts", ca.toString()).commit()
    }

    /** Old versions had no profiles: build one per phone number from existing tasks. */
    private fun migrate() {
        if (items.none { it.contactId == 0L }) return
        val cs = contacts.toMutableList()
        val fixed = items.map { it ->
            if (it.contactId != 0L) it else {
                var c = cs.firstOrNull { x -> x.number == it.number }
                if (c == null) {
                    c = Contact(Random.nextLong(1, Long.MAX_VALUE), it.name, it.number, it.pkg, null, null, null)
                    cs.add(c)
                }
                it.copy(contactId = c.id)
            }
        }
        contacts = cs
        items = fixed
        persist()
    }

    // ───── profiles ─────
    @Synchronized fun contacts(): List<Contact> = contacts
    @Synchronized fun contact(id: Long): Contact? = contacts.firstOrNull { it.id == id }
    @Synchronized fun newContactId(): Long = Random.nextLong(1, Long.MAX_VALUE)

    @Synchronized
    fun saveContact(c: Contact) {
        contacts = if (contacts.any { it.id == c.id }) contacts.map { if (it.id == c.id) c else it } else contacts + c
        // keep not-yet-sent tasks pointing at the up-to-date name/number/app
        items = items.map {
            if (it.contactId == c.id && (it.status == Status.PENDING || it.status == Status.AWAITING))
                it.copy(name = c.name, number = c.number, pkg = c.pkg) else it
        }
        persist()
    }

    @Synchronized
    fun removeContact(id: Long) {
        contacts = contacts.filter { it.id != id }
        items = items.filter { it.contactId != id }
        persist()
    }

    // ───── tasks ─────
    @Synchronized fun all(): List<Item> = items
    @Synchronized fun get(id: Long): Item? = items.firstOrNull { it.id == id }
    @Synchronized fun forContact(cid: Long): List<Item> = items.filter { it.contactId == cid }

    private fun put(i: Item) {
        items = items.map { if (it.id == i.id) i else it }
        persist()
    }

    @Synchronized fun add(i: Item) { items = items + i; persist() }
    @Synchronized fun remove(id: Long) { items = items.filter { it.id != id }; persist() }

    /** PENDING -> AWAITING. Returns null if the task was not PENDING, so an alarm can only ever fire once. */
    @Synchronized
    fun fire(id: Long): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.PENDING) return null
        val c = i.copy(status = Status.AWAITING, error = null, launched = false, stateAt = now())
        put(c); return c
    }

    /** AWAITING -> SENDING (the user's approval). Null if already approved / handled. */
    @Synchronized
    fun approve(id: Long): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.AWAITING) return null
        val c = i.copy(status = Status.SENDING, error = null, launched = false, stateAt = now())
        put(c); return c
    }

    /** True exactly once per approved task: the single permission to open WhatsApp for it. */
    @Synchronized
    fun markLaunched(id: Long): Boolean {
        val i = get(id) ?: return false
        if (i.status != Status.SENDING || i.launched) return false
        put(i.copy(launched = true)); return true
    }

    @Synchronized
    fun unmarkLaunched(id: Long) {
        val i = get(id) ?: return
        if (i.status == Status.SENDING && i.launched) put(i.copy(launched = false))
    }

    /** SENDING -> SENT / FAILED. Never touches a task in any other state. */
    @Synchronized
    fun finish(id: Long, ok: Boolean, error: String?): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.SENDING) return null
        val c = i.copy(status = if (ok) Status.SENT else Status.FAILED, error = if (ok) null else error, stateAt = now())
        put(c); return c
    }

    @Synchronized
    fun snooze(id: Long, ms: Long): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.AWAITING) return null
        val c = i.copy(status = Status.PENDING, timeMs = now() + ms, launched = false, stateAt = now())
        put(c); return c
    }

    @Synchronized
    fun skip(id: Long): Item? {
        val i = get(id) ?: return null
        if (i.status != Status.AWAITING) return null
        val c = i.copy(status = Status.MISSED, error = "Skipped by you", stateAt = now())
        put(c); return c
    }

    /** Edit / reschedule / retry. Not allowed while a send is in progress. */
    @Synchronized
    fun reschedule(id: Long, timeMs: Long): Item? {
        val i = get(id) ?: return null
        if (i.status == Status.SENDING) return null
        val c = i.copy(status = Status.PENDING, timeMs = timeMs, error = null, launched = false, stateAt = now())
        put(c); return c
    }

    @Synchronized
    fun expireOverdue() {
        val n = now()
        var changed = false
        items = items.map {
            val c = when {
                it.status == Status.PENDING && n - it.timeMs > 15 * 60_000L ->
                    it.copy(status = Status.MISSED, error = "Phone was off or app was stopped at send time", stateAt = n)
                it.status == Status.AWAITING && n - it.stateAt > 60 * 60_000L ->
                    it.copy(status = Status.MISSED, error = "Not approved within 1 hour", stateAt = n)
                it.status == Status.SENDING && n - it.stateAt > 10 * 60_000L ->
                    it.copy(
                        status = Status.FAILED, stateAt = n,
                        error = if (it.launched) "Sending was interrupted. Check the chat before retrying."
                        else "Sending was interrupted"
                    )
                else -> it
            }
            if (c !== it) changed = true
            c
        }
        if (changed) persist()
    }

    /** After a reboot nothing can still be sending. Never auto-resend: a launched task may already be delivered. */
    @Synchronized
    fun interruptSending() {
        val n = now()
        var changed = false
        items = items.map {
            if (it.status != Status.SENDING) it else {
                changed = true
                if (it.launched) it.copy(status = Status.FAILED, stateAt = n,
                    error = "Interrupted by restart. Check the chat before retrying.")
                else it.copy(status = Status.AWAITING, stateAt = n)
            }
        }
        if (changed) persist()
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun cc(ctx: Context): String = sp(ctx).getString("cc", "92") ?: "92"
    fun setCc(ctx: Context, v: String) { sp(ctx).edit().putString("cc", v).apply() }

    // alarm settings
    const val SND_SYSTEM = "system"
    const val SND_CUSTOM = "custom"
    const val SND_SILENT = "silent"

    fun sndMode(ctx: Context): String = sp(ctx).getString("snd_mode", SND_SYSTEM) ?: SND_SYSTEM
    fun setSndMode(ctx: Context, v: String) { sp(ctx).edit().putString("snd_mode", v).apply() }
    fun sndPath(ctx: Context): String? = sp(ctx).getString("snd_path", null)?.takeIf { File(it).exists() }
    fun sndName(ctx: Context): String? = sp(ctx).getString("snd_name", null)
    fun setSnd(ctx: Context, path: String?, name: String?) {
        sp(ctx).edit().putString("snd_path", path).putString("snd_name", name).apply()
    }
    fun vibrate(ctx: Context) = sp(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("vibrate", v).apply() }
    fun ringSec(ctx: Context) = sp(ctx).getInt("ring_sec", 60)
    fun setRingSec(ctx: Context, v: Int) { sp(ctx).edit().putInt("ring_sec", v).apply() }
    fun notifyDone(ctx: Context) = sp(ctx).getBoolean("notify_done", true)
    fun setNotifyDone(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("notify_done", v).apply() }

    /** File path of the sound to ring with. "" = phone's default alarm, null = silent (vibrate only). */
    fun alarmFor(ctx: Context, c: Contact?): String? {
        val own = c?.sound
        if (own != null && File(own).exists()) return own
        return when (sndMode(ctx)) {
            SND_SILENT -> null
            SND_CUSTOM -> sndPath(ctx) ?: ""
            else -> ""
        }
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

    /** True if the file is a real Ogg container (what WhatsApp treats as a voice note). */
    fun isOgg(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && String(b, Charsets.ISO_8859_1) == "OggS"
        }
    } catch (e: Exception) {
        false
    }

    private fun isOggOpus(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(96)
            val n = s.read(b)
            n >= 64 && String(b, 0, 4, Charsets.ISO_8859_1) == "OggS" &&
                String(b, 0, n, Charsets.ISO_8859_1).contains("OpusHead")
        } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * Always produces OGG/Opus. There is deliberately NO fallback that copies an MP3/M4A as-is:
     * that fallback is what made some notes arrive in WhatsApp as plain audio attachments.
     */
    fun importAudio(ctx: Context, uri: Uri): Imported? {
        if (isOggOpus(ctx, uri)) {
            val out = newVoiceFile(ctx)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                if (out.length() > 0 && isOgg(out)) return Imported(out, "audio/ogg", duration(out), null)
            } catch (e: Exception) {
            }
            out.delete()
        }
        repeat(2) {
            val out = newVoiceFile(ctx)
            val ok = try {
                OpusTranscoder.transcode(ctx, uri, out)
            } catch (e: Throwable) {
                false
            }
            if (ok && out.length() > 0 && isOgg(out)) return Imported(out, "audio/ogg", duration(out), null)
            out.delete()
        }
        return null
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Copies a picked alarm sound (MP3 etc.) into private storage so it keeps working later. Never sent anywhere. */
    fun importAlarm(ctx: Context, uri: Uri, key: String): Pair<String, String>? {
        return try {
            val name = displayName(ctx, uri) ?: "Custom sound"
            val ext = name.substringAfterLast('.', "mp3").take(5).ifBlank { "mp3" }
            val d = File(ctx.filesDir, "alarm"); d.mkdirs()
            val f = File(d, "${key}_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() == 0L) null else f.absolutePath to name
        } catch (e: Exception) {
            null
        }
    }
}

object Avatars {
    /** Picks a gallery image, crops it square, shrinks it and stores it privately. Returns the file path. */
    fun save(ctx: Context, uri: Uri, key: Long): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            var rot = 0
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    rot = when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
            }
            val s = minOf(bmp.width, bmp.height)
            val m = Matrix().apply { if (rot != 0) postRotate(rot.toFloat()) }
            var sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s, m, true)
            if (sq.width > 600) sq = Bitmap.createScaledBitmap(sq, 600, 600, true)
            val d = File(ctx.filesDir, "avatars"); d.mkdirs()
            val f = File(d, "a${key}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return f.absolutePath
        } catch (e: Throwable) {
            return null
        }
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

    fun fullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

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
        // same request code + FLAG_UPDATE_CURRENT: re-scheduling replaces, never duplicates
        am.setAlarmClock(AlarmManager.AlarmClockInfo(item.timeMs, show), pi(ctx, item.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(ctx, id))
    }
}

object Notifs {
    const val CH_SEND = "sending_v2"
    const val CH_RING = "ringing_v2"
    const val CH_APPROVE = "approve_v2"
    const val CH_STATUS = "status"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel("send") } catch (e: Exception) {}
        nm.createNotificationChannel(
            NotificationChannel(CH_SEND, "Sending voice notes", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RING, "Alarm running", NotificationManager.IMPORTANCE_LOW)
        )
        // silent: the app plays its own (customisable) alarm sound
        val ap = NotificationChannel(CH_APPROVE, "Approve voice note", NotificationManager.IMPORTANCE_HIGH)
        ap.setSound(null, null)
        ap.enableVibration(false)
        ap.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ap)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun fg(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SEND).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Voice Scheduler").setContentText(text).setOngoing(true).build()

    fun ringing(ctx: Context) =
        NotificationCompat.Builder(ctx, CH_RING).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Voice Scheduler").setContentText("A voice note is waiting for your approval")
            .setOngoing(true).build()

    private fun approvalId(id: Long) = (id % 100000).toInt() + 2000

    fun approvalIntent(ctx: Context, id: Long): Intent =
        Intent(ctx, ApprovalActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun approval(ctx: Context, item: Item, who: String) {
        val pi = PendingIntent.getActivity(
            ctx, approvalId(item.id), approvalIntent(ctx, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.timeMs))
        val n = NotificationCompat.Builder(ctx, CH_APPROVE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Send voice note to $who?")
            .setContentText("Scheduled for $t. Tap to approve and send.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi).setFullScreenIntent(pi, true)
            .setOngoing(true).setOnlyAlertOnce(true).setColor(0xFF21C063.toInt())
            .addAction(0, "APPROVE", pi)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(approvalId(item.id), n)
        } catch (e: Exception) {
        }
    }

    fun cancelApproval(ctx: Context, id: Long) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(approvalId(id)) } catch (e: Exception) {}
    }

    fun result(ctx: Context, id: Long, title: String, text: String, ok: Boolean = false) {
        if (ok && !Prefs.notifyDone(ctx)) return
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

    fun playUri(ctx: Context, uri: Uri, onDone: () -> Unit) {
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(ctx, uri)
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
