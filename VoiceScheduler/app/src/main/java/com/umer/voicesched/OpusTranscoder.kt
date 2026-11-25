package com.umer.voicesched

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

private class Resampler(val inRate: Int) {
    private val step = inRate.toDouble() / 48000.0
    private var pos = 0.0
    private var last = 0f
    private var first = true

    fun process(inp: ShortArray, n: Int, out: ShortArray): Int {
        if (n == 0) return 0
        if (first) {
            last = inp[0].toFloat(); first = false
        }
        var o = 0
        while (true) {
            val i = pos.toInt()
            if (i + 1 > n) break
            val a = if (i == 0) last else inp[i - 1].toFloat()
            val b = inp[i].toFloat()
            val fr = (pos - i).toFloat()
            if (o >= out.size) break
            out[o++] = (a + (b - a) * fr).toInt().toShort()
            pos += step
        }
        pos -= n
        last = inp[n - 1].toFloat()
        return o
    }
}

/** Converts any Android-decodable audio into mono 48k Opus-in-OGG (WhatsApp voice note format). */
object OpusTranscoder {
    fun transcode(ctx: Context, src: Uri, out: File): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(ctx, src, null)
            var tr = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    tr = i; fmt = f; break
                }
            }
            if (tr < 0 || fmt == null) return false
            ex.selectTrack(tr)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1)
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder = enc
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            if (out.exists()) out.delete()
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = mux

            var rs = Resampler(inRate)
            var pending = ByteArray(1 shl 16)
            var pLen = 0
            var decIn = false
            var decDone = false
            var encIn = false
            var encDone = false
            var totalSamples = 0L
            var track = -1
            val di = MediaCodec.BufferInfo()
            val ei = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 6 * 60_000L

            while (!encDone) {
                if (System.currentTimeMillis() > deadline) return false

                // 1. feed decoder
                if (!decIn) {
                    val i = dec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decIn = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // 2. drain decoder into pending PCM
                if (!decDone && pLen < 262144) {
                    val o = dec.dequeueOutputBuffer(di, 2000)
                    if (o >= 0) {
                        if (di.size > 0) {
                            val ob = dec.getOutputBuffer(o)!!
                            ob.position(di.offset)
                            ob.limit(di.offset + di.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val sh = ShortArray(sb.remaining())
                            sb.get(sh)
                            val ch = maxOf(inCh, 1)
                            val frames = sh.size / ch
                            val mono = ShortArray(frames)
                            if (ch == 1) {
                                System.arraycopy(sh, 0, mono, 0, frames)
                            } else {
                                for (f in 0 until frames) {
                                    var s = 0
                                    for (c in 0 until ch) s += sh[f * ch + c]
                                    mono[f] = (s / ch).toShort()
                                }
                            }
                            val outS = ShortArray((frames * 48000L / rs.inRate).toInt() + 8)
                            val cnt = rs.process(mono, frames, outS)
                            if (pLen + cnt * 2 > pending.size) {
                                pending = pending.copyOf(maxOf(pending.size * 2, pLen + cnt * 2))
                            }
                            for (k in 0 until cnt) {
                                val v = outS[k].toInt()
                                pending[pLen++] = (v and 0xff).toByte()
                                pending[pLen++] = ((v shr 8) and 0xff).toByte()
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if ((di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = dec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            val r = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (r != inRate) {
                                inRate = r; rs = Resampler(r)
                            }
                        }
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            inCh = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }

                // 3. feed encoder
                if (!encIn && (pLen > 0 || decDone)) {
                    val i = enc.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        val take = minOf(pLen, b.capacity()) and 1.inv()
                        val pts = totalSamples * 1_000_000L / 48000L
                        if (take > 0) {
                            b.put(pending, 0, take)
                            enc.queueInputBuffer(i, 0, take, pts, 0)
                            System.arraycopy(pending, take, pending, 0, pLen - take)
                            pLen -= take
                            totalSamples += take / 2
                        } else {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encIn = true
                        }
                    }
                }

                // 4. drain encoder into muxer
                val eo = enc.dequeueOutputBuffer(ei, 2000)
                if (eo >= 0) {
                    val eb = enc.getOutputBuffer(eo)!!
                    if ((ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0
                    if (ei.size > 0 && muxStarted) {
                        eb.position(ei.offset)
                        eb.limit(ei.offset + ei.size)
                        mux.writeSampleData(track, eb, ei)
                    }
                    enc.releaseOutputBuffer(eo, false)
                    if ((ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true
                } else if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxStarted = true
                }
            }
            if (muxStarted) {
                mux.stop()
                muxStarted = false
                return out.exists() && out.length() > 0
            }
            return false
        } catch (e: Throwable) {
            return false
        } finally {
            try { extractor?.release() } catch (e: Throwable) {}
            try { decoder?.stop() } catch (e: Throwable) {}
            try { decoder?.release() } catch (e: Throwable) {}
            try { encoder?.stop() } catch (e: Throwable) {}
            try { encoder?.release() } catch (e: Throwable) {}
            try { muxer?.release() } catch (e: Throwable) {}
        }
    }
}
