package com.umer.voicesched

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun SetupRow(title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (ok) Green else Color(0xFF3A3F44)),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (ok) Icons.Default.Check else Icons.Default.Warning, null, tint = if (ok) Color.Black else Muted,
                modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(desc, color = Muted, fontSize = 12.sp)
        }
        if (!ok) TextButton(onClick = onClick) { Text(action, color = Green) }
    }
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(desc, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.Black)
        )
    }
}

@Composable
fun SoundOption(title: String, desc: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Green))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            if (desc != null) Text(desc, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SettingsScreen(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var cc by remember { mutableStateOf(Prefs.cc(ctx)) }
    var mode by remember { mutableStateOf(Prefs.sndMode(ctx)) }
    var sndName by remember { mutableStateOf(Prefs.sndName(ctx)) }
    var sndPath by remember { mutableStateOf(Prefs.sndPath(ctx)) }
    var vibrate by remember { mutableStateOf(Prefs.vibrate(ctx)) }
    var ringSec by remember { mutableIntStateOf(Prefs.ringSec(ctx)) }
    var notifyDone by remember { mutableStateOf(Prefs.notifyDone(ctx)) }
    var testing by remember { mutableStateOf(false) }
    val key = tick + refresh
    DisposableEffect(Unit) { onDispose { Player.stop() } }

    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = Files.importAlarm(ctx, uri, "global")
            if (r == null) toast(ctx, "Could not read that audio file")
            else {
                sndPath?.let { old -> try { File(old).delete() } catch (e: Exception) {} }
                Prefs.setSnd(ctx, r.first, r.second); Prefs.setSndMode(ctx, Prefs.SND_CUSTOM)
                sndPath = r.first; sndName = r.second; mode = Prefs.SND_CUSTOM
            }
        }
    }

    fun toggleTest() {
        if (testing) { Player.stop(); testing = false; return }
        testing = true
        val done = { testing = false }
        when {
            mode == Prefs.SND_SILENT -> { testing = false; toast(ctx, "Silent: the phone will only vibrate") }
            mode == Prefs.SND_CUSTOM && sndPath != null -> Player.play(sndPath!!, done)
            else -> Player.playUri(
                ctx,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                done
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Alarm & notifications", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp))
            SectionCard {
                Text("Alarm sound", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Rings when a scheduled time arrives, even if the app is closed or the phone is locked.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                SoundOption("Phone's default alarm", null, mode == Prefs.SND_SYSTEM) {
                    mode = Prefs.SND_SYSTEM; Prefs.setSndMode(ctx, mode)
                }
                SoundOption("My MP3 / audio file", sndName ?: "Not chosen yet", mode == Prefs.SND_CUSTOM) {
                    if (sndPath == null) pickSound.launch(arrayOf("audio/*"))
                    else { mode = Prefs.SND_CUSTOM; Prefs.setSndMode(ctx, mode) }
                }
                SoundOption("Silent (vibrate only)", null, mode == Prefs.SND_SILENT) {
                    mode = Prefs.SND_SILENT; Prefs.setSndMode(ctx, mode)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { pickSound.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(50)) {
                        Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Upload MP3", color = Color.White)
                    }
                    OutlinedButton(onClick = { toggleTest() }, shape = RoundedCornerShape(50)) {
                        Icon(if (testing) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Green,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (testing) "Stop" else "Test", color = Color.White)
                    }
                }
            }
            SectionCard {
                SwitchRow("Vibrate", "Vibrate while the alarm rings.", vibrate) {
                    vibrate = it; Prefs.setVibrate(ctx, it)
                }
                Spacer(Modifier.height(8.dp))
                Text("Ring for", color = Color.White, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (s, l) ->
                        FilterChip(selected = ringSec == s, onClick = { ringSec = s; Prefs.setRingSec(ctx, s) },
                            label = { Text(l) })
                    }
                }
                Text("After this the sound stops, but the approval notification stays until you act.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                SwitchRow("Notify when sent", "Show a notification after each voice note is delivered.", notifyDone) {
                    notifyDone = it; Prefs.setNotifyDone(ctx, it)
                }
            }

            Text("Setup", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            key.let {
                SetupRow("WhatsApp installed", "WhatsApp or WhatsApp Business", Wa.installed(ctx).isNotEmpty(), "") {}
                SetupRow("Accessibility service",
                    "Lets the app tap Send inside WhatsApp. Settings → Accessibility → Voice Scheduler → On.",
                    Setup.a11yEnabled(ctx), "Open") {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow("Display over other apps", "Lets the app show the approval screen and open WhatsApp from the background.",
                    Setup.overlay(ctx), "Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow("Notifications", "Shows the approval request and results.", Setup.notif(ctx), "Allow") {
                    if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SetupRow("Full-screen alerts", "Lets the approval screen appear over the lock screen.",
                    Setup.fullScreen(ctx), "Allow") {
                    try {
                        ctx.startActivity(
                            Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:" + ctx.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                SetupRow("Battery: unrestricted", "Stops the phone killing the app before send time.", Setup.battery(ctx), "Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            SectionCard {
                Text("Default country code", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Used for numbers starting with 0 (92 = Pakistan).", color = Muted, fontSize = 12.sp)
                OutlinedTextField(cc, { v -> cc = v.filter { it.isDigit() }.take(4); Prefs.setCc(ctx, cc) },
                    singleLine = true, modifier = Modifier.padding(top = 8.dp))
            }
            SectionCard {
                Text("How sending works", color = Color.White, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• At the scheduled time the alarm rings and an approval screen appears, even on the lock screen.\n" +
                        "• Tap APPROVE & SEND. If the phone is locked it asks for your PIN/fingerprint first.\n" +
                        "• Each voice note is sent once, then marked Sent. It can never fire again.\n" +
                        "• If you unlock later, the approval screen appears automatically.\n" +
                        "• Voice notes always go as WhatsApp voice messages (Opus format), never as MP3 files.\n" +
                        "• Vivo/Oppo/Xiaomi: also allow Autostart and set battery to Unrestricted in phone settings.\n" +
                        "• Test first: schedule 2 minutes ahead.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
