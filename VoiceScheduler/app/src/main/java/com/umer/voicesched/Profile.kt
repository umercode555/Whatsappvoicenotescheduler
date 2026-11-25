package com.umer.voicesched

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

fun deleteItem(ctx: android.content.Context, it: Item) {
    Scheduler.cancel(ctx, it.id)
    try { File(it.file).delete() } catch (e: Exception) {}
    Store.remove(it.id)
    Notifs.cancelApproval(ctx, it.id)
    AlarmService.changed()
}

private fun newId(): Long = Random.nextInt(1, Int.MAX_VALUE).toLong()

// ───────────────────────── PROFILE ─────────────────────────

private class Resched(val item: Item, val copy: Boolean)

@Composable
fun ProfileScreen(cid: Long, snap: Snap, onBack: () -> Unit, onEdit: () -> Unit, onNewNote: () -> Unit) {
    val ctx = LocalContext.current
    val contact = snap.contacts.firstOrNull { it.id == cid }
    if (contact == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val items = snap.items.filter { it.contactId == cid }
    var tab by remember { mutableIntStateOf(0) }
    val pending = items.filter { it.status == Status.PENDING || it.status == Status.AWAITING || it.status == Status.SENDING }
        .sortedBy { it.timeMs }
    val done = items.filter { it.status == Status.SENT }.sortedByDescending { it.timeMs }
    val failed = items.filter { it.status == Status.FAILED || it.status == Status.MISSED }
        .sortedByDescending { it.timeMs }
    val shown = when (tab) { 0 -> pending; 1 -> done; else -> failed }
    var playing by remember { mutableLongStateOf(-1L) }
    var resched by remember { mutableStateOf<Resched?>(null) }
    var confirmDelete by remember { mutableStateOf<Item?>(null) }
    DisposableEffect(Unit) { onDispose { Player.stop() } }

    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack) {
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null, tint = Color.White) }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(contact.avatar, contact.name, 104.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(contact.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("+" + contact.number, color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Chip(if (contact.pkg.endsWith("w4b")) "WhatsApp Business" else "WhatsApp", Green)
                }
            }
            item {
                Button(
                    onClick = onNewNote,
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New voice note", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabPill("Pending", pending.size, tab == 0, Modifier.weight(1f)) { tab = 0 }
                    TabPill("Completed", done.size, tab == 1, Modifier.weight(1f)) { tab = 1 }
                    TabPill("Failed", failed.size, tab == 2, Modifier.weight(1f)) { tab = 2 }
                }
            }
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (tab) { 0 -> "No scheduled voice notes"; 1 -> "Nothing sent yet"; else -> "No failures" },
                        color = Muted, fontSize = 15.sp
                    )
                }
            }
            items(shown, key = { it.id }) { it ->
                ItemCard(
                    it, playing == it.id,
                    onPlay = {
                        if (playing == it.id) { Player.stop(); playing = -1L }
                        else { playing = it.id; Player.play(it.file) { playing = -1L } }
                    },
                    onApprove = { ctx.startActivity(Notifs.approvalIntent(ctx, it.id)) },
                    onReschedule = { resched = Resched(it, false) },
                    onAgain = { resched = Resched(it, true) },
                    onRetryNow = {
                        val n = Store.reschedule(it.id, System.currentTimeMillis() + 8000)
                        if (n != null) {
                            Scheduler.schedule(ctx, n)
                            toast(ctx, "Will ask for your approval in 8 seconds")
                        }
                    },
                    onDelete = { confirmDelete = it }
                )
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = Surf,
            title = { Text("Delete this voice note?", color = Color.White) },
            text = { Text(fmtWhen(d.timeMs) + ". This also cancels its reminder.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    if (playing == d.id) { Player.stop(); playing = -1L }
                    deleteItem(ctx, d); confirmDelete = null
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = Muted) } }
        )
    }

    resched?.let { r ->
        ScheduleSheet(
            initialMs = if (r.copy) null else r.item.timeMs,
            title = if (r.copy) "Send again" else "Reschedule",
            onDismiss = { resched = null },
            onConfirm = { t ->
                if (r.copy) {
                    try {
                        val nf = Files.newVoiceFile(ctx)
                        File(r.item.file).copyTo(nf, overwrite = true)
                        val n = r.item.copy(
                            id = newId(), file = nf.absolutePath, timeMs = t, status = Status.PENDING,
                            error = null, launched = false, stateAt = System.currentTimeMillis()
                        )
                        Store.add(n); Scheduler.schedule(ctx, n)
                        toast(ctx, "Scheduled for " + fmtWhen(t))
                    } catch (e: Exception) {
                        toast(ctx, "Audio file is missing, record a new one")
                    }
                } else {
                    val n = Store.reschedule(r.item.id, t)
                    if (n != null) {
                        Scheduler.schedule(ctx, n)
                        Notifs.cancelApproval(ctx, n.id)
                        AlarmService.changed()
                        toast(ctx, "Rescheduled for " + fmtWhen(t))
                    } else toast(ctx, "Can't change it while it is sending")
                }
                resched = null
            }
        )
    }
}

@Composable
fun TabPill(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Green else Glass)
            .clickable { onClick() }.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count", color = if (selected) Color.Black else Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(if (selected) Color(0x33000000) else Green.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Glass).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ItemCard(
    it: Item, playing: Boolean, onPlay: () -> Unit, onApprove: () -> Unit, onReschedule: () -> Unit,
    onAgain: () -> Unit, onRetryNow: () -> Unit, onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Green.copy(alpha = 0.18f)).clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Green)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtRel(it.timeMs), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(it.timeMs) + " · " + fmtDur(it.durMs),
                    color = Muted, fontSize = 12.sp)
            }
            StatusPill(it.status)
        }
        if (it.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(it.error, color = Red, fontSize = 12.sp)
        }
        if (it.status == Status.SENDING) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Blue, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Sending to WhatsApp…", color = Blue, fontSize = 12.sp)
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (it.status == Status.AWAITING) ActionPill("Approve", Icons.Default.Check, Green, onApprove)
                if (it.status == Status.PENDING || it.status == Status.AWAITING)
                    ActionPill("Reschedule", Icons.Default.Edit, Color.White, onReschedule)
                if (it.status == Status.FAILED || it.status == Status.MISSED) {
                    ActionPill("Reschedule", Icons.Default.Edit, Color.White, onReschedule)
                    ActionPill("Retry", Icons.Default.Refresh, Green, onRetryNow)
                }
                if (it.status == Status.SENT) ActionPill("Send again", Icons.Default.ContentCopy, Green, onAgain)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, null, tint = Muted)
                }
            }
        }
    }
}

// ───────────────────────── EDIT / CREATE PROFILE ─────────────────────────

@Composable
fun EditProfileScreen(cid: Long, onBack: () -> Unit, onSaved: (Long) -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val existing = remember { Store.contact(cid) }
    val id = remember { existing?.id ?: Store.newContactId() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var number by remember { mutableStateOf(existing?.number ?: "") }
    var avatar by remember { mutableStateOf(existing?.avatar) }
    var sound by remember { mutableStateOf(existing?.sound) }
    var soundName by remember { mutableStateOf(existing?.soundName) }
    val installed = remember { Wa.installed(ctx) }
    var pkg by remember { mutableStateOf(existing?.pkg ?: installed.firstOrNull() ?: "com.whatsapp") }
    var confirmDelete by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { Player.stop() } }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val p = Avatars.save(ctx, uri, id)
            if (p == null) toast(ctx, "Could not use that picture") else avatar = p
        }
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (uri != null) {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (name.isBlank()) name = c.getString(0) ?: ""
                    number = normalizeNumber(ctx, c.getString(1) ?: "")
                }
            }
        }
    }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = Files.importAlarm(ctx, uri, "c$id")
            if (r == null) toast(ctx, "Could not read that audio file")
            else { sound = r.first; soundName = r.second }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing == null) "New profile" else "Edit profile", onBack) {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, null, tint = Red)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.clickable { pickPhoto.launch(arrayOf("image/*")) }) {
                    Avatar(avatar, name, 120.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(38.dp).clip(CircleShape).background(Green),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.CameraAlt, null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                }
            }
            Text("Tap to choose a photo from your gallery", color = Muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            SectionCard {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name (e.g. Baba)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    number, { number = it }, label = { Text("WhatsApp number, e.g. 0300… or +92…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    },
                    shape = RoundedCornerShape(50)
                ) { Text("Pick from phone contacts", color = Color.White) }
                if (installed.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        installed.forEach { p ->
                            FilterChip(selected = pkg == p, onClick = { pkg = p },
                                label = { Text(if (p.endsWith("w4b")) "WhatsApp Business" else "WhatsApp") })
                        }
                    }
                }
            }
            SectionCard {
                Text("Alarm sound for this person", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Optional. Overrides the default alarm sound from Settings. Only used to wake you, never sent.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                if (sound != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text(soundName ?: "Custom sound", color = Color.White, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = {
                            if (previewing) { Player.stop(); previewing = false }
                            else { previewing = true; Player.play(sound!!) { previewing = false } }
                        }) { Icon(if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Green) }
                        IconButton(onClick = {
                            Player.stop(); previewing = false
                            try { File(sound!!).delete() } catch (e: Exception) {}
                            sound = null; soundName = null
                        }) { Icon(Icons.Default.Close, null, tint = Muted) }
                    }
                } else {
                    OutlinedButton(onClick = { pickSound.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(50)) {
                        Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Choose MP3 / audio", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val norm = normalizeNumber(ctx, number)
                when {
                    name.isBlank() -> toast(ctx, "Enter a name")
                    norm.length < 8 -> toast(ctx, "Number looks too short")
                    else -> {
                        Store.saveContact(Contact(id, name.trim(), norm, pkg, avatar, sound, soundName))
                        onSaved(id)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)
        ) { Text("Save profile", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = Surf,
            title = { Text("Delete ${existing.name}?", color = Color.White) },
            text = { Text("The profile and all of its scheduled and past voice notes will be removed.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    for (it in Store.forContact(existing.id)) deleteItem(ctx, it)
                    existing.avatar?.let { a -> try { File(a).delete() } catch (e: Exception) {} }
                    existing.sound?.let { a -> try { File(a).delete() } catch (e: Exception) {} }
                    Store.removeContact(existing.id)
                    confirmDelete = false
                    onDeleted()
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Muted) } }
        )
    }
}

// ───────────────────────── NEW VOICE NOTE ─────────────────────────

@Composable
fun NewNoteScreen(cid: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val contact = remember { Store.contact(cid) }
    if (contact == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var file by remember { mutableStateOf<File?>(null) }
    var dur by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var previewing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var scheduled by remember { mutableStateOf(false) }
    val recorder = remember { Recorder(ctx) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel(); Player.stop()
            if (!scheduled) try { file?.delete() } catch (e: Exception) {}
        }
    }

    fun startRec() {
        try {
            val f = Files.newVoiceFile(ctx)
            recorder.start(f)
            file = null; recording = true; elapsed = 0
        } catch (e: Exception) {
            toast(ctx, "Cannot start recording: " + e.message)
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startRec() else toast(ctx, "Microphone permission needed to record")
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; file = null
            scope.launch {
                val res = withContext(Dispatchers.IO) { Files.importAudio(ctx, uri) }
                busy = false
                if (res == null) toast(ctx, "Could not convert that file to a WhatsApp voice note. Try another file (MP3, M4A, WAV, OGG).")
                else { file = res.file; dur = res.durMs }
            }
        }
    }

    LaunchedEffect(recording) {
        val t0 = SystemClock.elapsedRealtime()
        while (recording) {
            elapsed = SystemClock.elapsedRealtime() - t0
            delay(200)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("New voice note", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth().glass().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(contact.avatar, contact.name, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Send to", color = Muted, fontSize = 12.sp)
                    Text(contact.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            SectionCard {
                Text("Voice note", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Green, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Converting to voice format…", color = Color.White)
                    }
                    recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Red).clickable {
                                val d = recorder.stop()
                                recording = false
                                val f = recorder.file
                                if (d < 700 || f == null || !f.exists()) {
                                    toast(ctx, "Recording too short"); f?.delete()
                                } else { file = f; dur = d }
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Stop, null, tint = Color.White) }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Recording…", color = Red, fontWeight = FontWeight.SemiBold)
                            Text(fmtDur(elapsed), color = Color.White, fontSize = 22.sp)
                        }
                    }
                    file != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Green).clickable {
                                if (previewing) { Player.stop(); previewing = false }
                                else { previewing = true; Player.play(file!!.absolutePath) { previewing = false } }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (previewing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Voice note ready", color = Color.White, fontWeight = FontWeight.Medium)
                            Text(fmtDur(dur) + " · WhatsApp voice-note format", color = Muted, fontSize = 13.sp)
                        }
                        IconButton(onClick = {
                            Player.stop(); previewing = false
                            file?.delete(); file = null
                        }) { Icon(Icons.Default.Close, null, tint = Muted) }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) startRec() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Record")
                        }
                        OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*", "application/ogg")) },
                            shape = RoundedCornerShape(50)) {
                            Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Choose file", color = Color.White)
                        }
                    }
                }
            }
        }
        val ready = file != null && !recording && !busy
        Button(
            onClick = { Player.stop(); previewing = false; showSheet = true },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green, contentColor = Color.Black,
                disabledContainerColor = Surf, disabledContentColor = Muted
            )
        ) { Text("Schedule", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }

    if (showSheet) {
        ScheduleSheet(
            initialMs = null, title = "Schedule message",
            onDismiss = { showSheet = false },
            onConfirm = { timeMs ->
                val f = file
                if (f == null || !Files.isOgg(f)) {
                    toast(ctx, "This audio is not in voice-note format. Record or choose it again.")
                    showSheet = false
                } else {
                    val item = Item(
                        newId(), contact.id, contact.name, contact.number, f.absolutePath, "audio/ogg", dur, timeMs,
                        contact.pkg, Status.PENDING, null, false, System.currentTimeMillis()
                    )
                    Store.add(item)
                    Scheduler.schedule(ctx, item)
                    scheduled = true
                    showSheet = false
                    if (!Setup.allOk(ctx)) toast(ctx, "Scheduled. Finish setup in Settings or it will not send.")
                    else toast(ctx, "Scheduled for " + fmtWhen(timeMs))
                    onBack()
                }
            }
        )
    }
}

// ─────────────── WhatsApp-style "Schedule message" sheet ───────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(initialMs: Long?, title: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val ctx = LocalContext.current
    val today = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }
    val days = remember { (0..365).map { (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, it) } } }
    val dateFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val monFmt = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
    val dateLabels = remember { days.map { dateFmt.format(it.time) } }
    val hourLabels = remember { listOf("12") + (1..11).map { it.toString() } }
    val minLabels = remember { (0..59).map { "%02d".format(it) } }
    val apLabels = remember { listOf("am", "pm") }
    val init = remember {
        Calendar.getInstance().apply {
            if (initialMs != null && initialMs > System.currentTimeMillis()) timeInMillis = initialMs
            else { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }
        }
    }

    var dIdx by remember {
        mutableIntStateOf(days.indexOfFirst {
            it.get(Calendar.YEAR) == init.get(Calendar.YEAR) && it.get(Calendar.DAY_OF_YEAR) == init.get(Calendar.DAY_OF_YEAR)
        }.coerceAtLeast(0))
    }
    var hIdx by remember { mutableIntStateOf(init.get(Calendar.HOUR)) }
    var mIdx by remember { mutableIntStateOf(init.get(Calendar.MINUTE)) }
    var apIdx by remember { mutableIntStateOf(init.get(Calendar.AM_PM)) }
    val startD = remember { dIdx }
    val startH = remember { hIdx }
    val startM = remember { mIdx }
    val startA = remember { apIdx }

    fun chosen(): Calendar = (days[dIdx].clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hIdx + if (apIdx == 1) 12 else 0)
        set(Calendar.MINUTE, mIdx); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    val c = chosen()
    val label = "Send ${c.get(Calendar.DAY_OF_MONTH)} ${monFmt.format(c.time)} at " +
        "${if (hIdx == 0) 12 else hIdx}:${"%02d".format(mIdx)} ${if (apIdx == 0) "am" else "pm"}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        contentColor = Color.White
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 26.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp))
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth().height(46.dp)
                        .clip(RoundedCornerShape(12.dp)).background(RowHi)
                )
                Row(Modifier.fillMaxSize()) {
                    Wheel(dateLabels, startD, false, Modifier.weight(2.3f)) { dIdx = it }
                    Wheel(hourLabels, startH, true, Modifier.weight(1f)) { hIdx = it }
                    Wheel(minLabels, startM, true, Modifier.weight(1f)) { mIdx = it }
                    Wheel(apLabels, startA, false, Modifier.weight(1f)) { apIdx = it }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    val t = chosen().timeInMillis
                    if (t < System.currentTimeMillis() + 5000) toast(ctx, "Pick a future time")
                    else onConfirm(t)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color(0xFF0B141A))
            ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun Wheel(labels: List<String>, initial: Int, loop: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    val n = labels.size
    val itemH = 46.dp
    val count = if (loop) n * 400 else n
    val start = if (loop) n * 200 + initial else initial
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val li = state.layoutInfo
                val center = (li.viewportStartOffset + li.viewportEndOffset) / 2
                val c = li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }
                if (c != null) {
                    val d = (c.offset + c.size / 2 - center).toFloat()
                    if (abs(d) > 0.5f) state.animateScrollBy(d)
                    onSelect(c.index % n)
                }
            }
        }
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = itemH * 2),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count) { index ->
            val li = state.layoutInfo
            val info = li.visibleItemsInfo.firstOrNull { it.index == index }
            val center = (li.viewportStartOffset + li.viewportEndOffset) / 2
            val dist = if (info == null) 3f else abs(info.offset + info.size / 2 - center).toFloat() / info.size
            val a = (1f - dist * 0.38f).coerceIn(0.12f, 1f)
            val sel = dist < 0.5f
            Box(Modifier.height(itemH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    labels[index % n],
                    color = if (sel) Color.White else Color(0xFF9AA0A6),
                    fontSize = 21.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.alpha(a)
                )
            }
        }
    }
}
