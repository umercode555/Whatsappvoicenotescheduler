package com.umer.voicesched

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

val Bg = Color(0xFF0B141A)
val Surf = Color(0xFF1F2C34)
val SheetBg = Color(0xFF191B1D)
val RowHi = Color(0xFF2B2E32)
val Green = Color(0xFF21C063)
val Muted = Color(0xFF8696A0)
val Red = Color(0xFFEF5350)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green, background = Bg, surface = Surf, onSurface = Color.White,
            onBackground = Color.White
        ),
        content = content
    )
}

fun toast(ctx: android.content.Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtWhen(ms: Long): String = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date(ms))

@Composable
fun Root(tick: Int) {
    var screen by remember { mutableStateOf("home") }
    BackHandler(enabled = screen != "home") { screen = "home" }
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (screen) {
            "new" -> NewScreen(onBack = { screen = "home" })
            "setup" -> SetupScreen(tick, onBack = { screen = "home" })
            else -> HomeScreen(tick, onNew = { screen = "new" }, onSetup = { screen = "setup" })
        }
    }
}

// ───────────────────────── HOME ─────────────────────────

@Composable
fun HomeScreen(tick: Int, onNew: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(Store.all()) }
    LaunchedEffect(tick) {
        while (true) {
            all = Store.all(); delay(1500)
        }
    }
    val setupOk = remember(tick, all) { Setup.allOk(ctx) }
    var playing by remember { mutableLongStateOf(-1L) }
    DisposableEffect(Unit) { onDispose { Player.stop() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice Scheduler", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onSetup) { Icon(Icons.Default.Settings, null, tint = Color.White) }
            }
            val upcoming = all.filter { it.status == Status.PENDING || it.status == Status.SENDING }
                .sortedBy { it.timeMs }
            val history = all.filter { it.status != Status.PENDING && it.status != Status.SENDING }
                .sortedByDescending { it.timeMs }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!setupOk) item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A2A12))
                            .clickable { onSetup() }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB74D))
                        Spacer(Modifier.width(12.dp))
                        Text("Finish setup so sending works", color = Color.White, modifier = Modifier.weight(1f))
                        Text("Open", color = Green, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (all.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Mic, null, tint = Muted, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No voice notes scheduled", color = Muted, fontSize = 16.sp)
                        Text("Tap + to schedule one", color = Muted, fontSize = 14.sp)
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item { Text("Scheduled", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    items(upcoming, key = { it.id }) { ItemCard(it, playing == it.id,
                        onPlay = {
                            if (playing == it.id) { Player.stop(); playing = -1 }
                            else { playing = it.id; Player.play(it.file) { playing = -1 } }
                        },
                        onDelete = { deleteItem(ctx, it) }, onRetry = {}) }
                }
                if (history.isNotEmpty()) {
                    item { Text("History", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    items(history, key = { it.id }) { ItemCard(it, playing == it.id,
                        onPlay = {
                            if (playing == it.id) { Player.stop(); playing = -1 }
                            else { playing = it.id; Player.play(it.file) { playing = -1 } }
                        },
                        onDelete = { deleteItem(ctx, it) },
                        onRetry = {
                            val n = it.copy(timeMs = System.currentTimeMillis() + 8000, status = Status.PENDING, error = null)
                            Store.update(n); Scheduler.schedule(ctx, n)
                        }) }
                }
            }
        }
        FloatingActionButton(
            onClick = onNew, containerColor = Green, contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, null) }
    }
}

fun deleteItem(ctx: android.content.Context, it: Item) {
    Scheduler.cancel(ctx, it.id)
    try { File(it.file).delete() } catch (e: Exception) {}
    Store.remove(it.id)
}

@Composable
fun ItemCard(it: Item, playing: Boolean, onPlay: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
    val (label, col) = when (it.status) {
        Status.SENT -> "Sent" to Green
        Status.FAILED -> "Failed" to Red
        Status.MISSED -> "Missed" to Color(0xFFFFB74D)
        Status.SENDING -> "Sending…" to Color(0xFF64B5F6)
        else -> "Scheduled" to Muted
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surf).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Green.copy(alpha = 0.18f)).clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Green)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(fmtWhen(it.timeMs) + " · " + fmtDur(it.durMs), color = Muted, fontSize = 13.sp)
            }
            Text(label, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (it.status != Status.SENDING) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = Muted) }
            }
        }
        if (it.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(it.error, color = Red, fontSize = 12.sp)
        }
        if (it.status == Status.FAILED || it.status == Status.MISSED) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, null, tint = Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry in 8 seconds", color = Green)
            }
        }
    }
}

// ───────────────────────── NEW ─────────────────────────

@Composable
fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surf).padding(16.dp),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<File?>(null) }
    var mime by remember { mutableStateOf("audio/ogg") }
    var dur by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var previewing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val recorder = remember { Recorder(ctx) }
    val installed = remember { Wa.installed(ctx) }
    var pkg by remember { mutableStateOf(installed.firstOrNull() ?: "com.whatsapp") }

    DisposableEffect(Unit) { onDispose { recorder.cancel(); Player.stop() } }

    fun startRec() {
        try {
            val f = Files.newVoiceFile(ctx)
            recorder.start(f)
            file = null; note = null; recording = true; elapsed = 0
        } catch (e: Exception) {
            toast(ctx, "Cannot start recording: " + e.message)
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startRec() else toast(ctx, "Microphone permission needed to record")
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
                    name = c.getString(0) ?: ""
                    number = normalizeNumber(ctx, c.getString(1) ?: "")
                }
            }
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; note = null; file = null
            scope.launch {
                val res = withContext(Dispatchers.IO) { Files.importAudio(ctx, uri) }
                busy = false
                if (res == null) toast(ctx, "Could not read that audio file")
                else { file = res.file; mime = res.mime; dur = res.durMs; note = res.note }
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
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Text("New voice note", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // contact
            SectionCard {
                Text("Send to", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (number.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Green.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text((name.ifBlank { number }).take(1).uppercase(), color = Green, fontSize = 18.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name.ifBlank { "+$number" }, color = Color.White, fontSize = 16.sp)
                            Text("+$number", color = Muted, fontSize = 13.sp)
                        }
                        TextButton(onClick = { number = ""; name = "" }) { Text("Change", color = Green) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
                            shape = RoundedCornerShape(50)
                        ) { Text("Choose contact") }
                        OutlinedButton(onClick = { showManual = true }, shape = RoundedCornerShape(50)) {
                            Text("Enter number", color = Color.White)
                        }
                    }
                }
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

            // audio
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
                                } else { file = f; mime = "audio/ogg"; dur = d }
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Stop, null, tint = Color.White) }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Recording…", color = Red, fontWeight = FontWeight.SemiBold)
                            Text(fmtDur(elapsed), color = Color.White, fontSize = 22.sp)
                        }
                    }
                    file != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                Text(fmtDur(dur), color = Muted, fontSize = 13.sp)
                            }
                            IconButton(onClick = {
                                Player.stop(); previewing = false
                                file?.delete(); file = null; note = null
                            }) { Icon(Icons.Default.Close, null, tint = Muted) }
                        }
                        if (note != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(note!!, color = Color(0xFFFFB74D), fontSize = 12.sp)
                        }
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
        val ready = number.isNotBlank() && file != null && !recording && !busy
        Button(
            onClick = { Player.stop(); previewing = false; showSheet = true },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green, contentColor = Color.Black,
                disabledContainerColor = Surf, disabledContentColor = Muted
            )
        ) { Text("Schedule", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }

    if (showManual) {
        var n by remember { mutableStateOf("") }
        var nm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManual = false },
            containerColor = Surf,
            title = { Text("Enter number", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nm, { nm = it }, label = { Text("Name (optional)") }, singleLine = true)
                    OutlinedTextField(n, { n = it }, label = { Text("Number, e.g. 0300… or +92…") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val norm = normalizeNumber(ctx, n)
                    if (norm.length < 8) toast(ctx, "Number looks too short")
                    else { number = norm; name = nm.trim(); showManual = false }
                }) { Text("OK", color = Green) }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cancel", color = Muted) } }
        )
    }

    if (showSheet) {
        ScheduleSheet(
            onDismiss = { showSheet = false },
            onConfirm = { timeMs ->
                val f = file!!
                val id = Random.nextInt(1, Int.MAX_VALUE).toLong()
                val item = Item(id, name.ifBlank { "+$number" }, number, f.absolutePath, mime, dur, timeMs, pkg,
                    Status.PENDING, null)
                Store.add(item)
                Scheduler.schedule(ctx, item)
                showSheet = false
                if (!Setup.allOk(ctx)) toast(ctx, "Scheduled. Finish setup (gear icon) or it will not send.")
                else toast(ctx, "Scheduled for " + fmtWhen(timeMs))
                onBack()
            }
        )
    }
}

// ─────────────── WhatsApp-style "Schedule message" sheet ───────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
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
    val init = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) } }

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
            Text("Schedule message", color = Color.White, fontSize = 26.sp, textAlign = TextAlign.Center,
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

// ───────────────────────── SETUP ─────────────────────────

@Composable
fun SetupRow(title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surf).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (ok) Green else Color(0xFF3A3F44)),
            contentAlignment = Alignment.Center
        ) { Icon(if (ok) Icons.Default.Check else Icons.Default.Warning, null, tint = if (ok) Color.Black else Muted,
            modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(desc, color = Muted, fontSize = 12.sp)
        }
        if (!ok) TextButton(onClick = onClick) { Text(action, color = Green) }
    }
}

@Composable
fun SetupScreen(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var cc by remember { mutableStateOf(Prefs.cc(ctx)) }
    val key = tick + refresh

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Text("Setup", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            key.let {
                SetupRow("WhatsApp installed", "WhatsApp or WhatsApp Business", Wa.installed(ctx).isNotEmpty(), "") {}
                SetupRow("Accessibility service",
                    "Lets app tap Send inside WhatsApp. Settings → Accessibility → Voice Scheduler → On.",
                    Setup.a11yEnabled(ctx), "Open") {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow("Display over other apps", "Lets app open WhatsApp from background at send time.",
                    Setup.overlay(ctx), "Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow("Notifications", "Shows sending status and failures.", Setup.notif(ctx), "Allow") {
                    if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SetupRow("Battery: unrestricted", "Stops phone killing app before send time.", Setup.battery(ctx), "Allow") {
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
                Text("Good to know", color = Color.White, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• Phone must be on, not PIN/pattern locked at send time. Swipe-only lock or no lock works.\n" +
                        "• Screen wakes by itself, WhatsApp opens, Send is tapped, then Home.\n" +
                        "• Vivo/Oppo/Xiaomi: also allow Autostart and set battery to Unrestricted in phone settings.\n" +
                        "• Number must have WhatsApp. Voice arrives in Opus format, same as native voice notes.\n" +
                        "• Test first: schedule 2 minutes ahead.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
