package com.umer.voicesched

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records straight to OGG/Opus, the same format WhatsApp uses for voice notes. */
class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    var file: File? = null
        private set
    private var startAt = 0L

    @Suppress("DEPRECATION")
    fun start(out: File) {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(48000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        mr = r
        file = out
        startAt = SystemClock.elapsedRealtime()
    }

    /** Returns duration in ms, or 0 if recording was too short/failed. */
    fun stop(): Long {
        val r = mr ?: return 0
        val d = SystemClock.elapsedRealtime() - startAt
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false
            file?.delete()
        }
        try { r.release() } catch (e: Exception) {}
        mr = null
        return if (ok) d else 0
    }

    fun cancel() {
        val r = mr ?: return
        try { r.stop() } catch (e: Exception) {}
        try { r.release() } catch (e: Exception) {}
        mr = null
        file?.delete()
        file = null
    }
}
