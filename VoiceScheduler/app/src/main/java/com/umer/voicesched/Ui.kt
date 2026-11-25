package com.umer.voicesched

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Bg = Color(0xFF0B141A)
val Surf = Color(0xFF1F2C34)
val SheetBg = Color(0xFF191B1D)
val RowHi = Color(0xFF2B2E32)
val Green = Color(0xFF21C063)
val Muted = Color(0xFF8696A0)
val Red = Color(0xFFEF5350)
val Amber = Color(0xFFFFB74D)
val Blue = Color(0xFF64B5F6)
val Glass = Color(0x16FFFFFF)
val GlassBorder = Color(0x26FFFFFF)

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

private fun midnight(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today · 3:00 PM", "Tomorrow · 9:00 AM", "Fri 28 Nov · 7:15 PM" */
fun fmtRel(ms: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    val diff = Math.round((midnight(ms) - midnight(System.currentTimeMillis())) / 86_400_000.0).toInt()
    val day = when (diff) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
    return "$day · $time"
}

// ───────────────────────── shared look ─────────────────────────

fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.clip(shape).background(Glass).border(1.dp, GlassBorder, shape)

@Composable
fun Modifier.bounceClick(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, label = "bounce")
    return this.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun Backdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B141A), Color(0xFF0C2428), Color(0xFF0B141A)))
        )
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Green.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(900f, 150f), radius = 900f
                )
            )
        )
        content()
    }
}

@Composable
fun Avatar(path: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(path) {
        if (path == null) null else try {
            android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF25D366), Color(0xFF0E7C66)))),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().take(1).uppercase().ifBlank { "?" }, color = Color.White,
                fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val (label, col) = when (status) {
        Status.SENT -> "Sent" to Green
        Status.FAILED -> "Failed" to Red
        Status.MISSED -> "Missed" to Amber
        Status.SENDING -> "Sending…" to Blue
        Status.AWAITING -> "Needs approval" to Amber
        else -> "Scheduled" to Muted
    }
    Text(
        label, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
        } else Spacer(Modifier.width(12.dp))
        Text(
            title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}

@Composable
fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), content = content)
}

// ───────────────────────── data snapshot ─────────────────────────

data class Snap(val contacts: List<Contact>, val items: List<Item>)

@Composable
fun rememberSnap(tick: Int): Snap {
    var s by remember { mutableStateOf(Snap(Store.contacts(), Store.all())) }
    LaunchedEffect(tick) {
        while (true) {
            val n = Snap(Store.contacts(), Store.all())
            if (n != s) s = n
            delay(1000)
        }
    }
    return s
}

// ───────────────────────── navigation ─────────────────────────

sealed class Screen {
    object Home : Screen()
    data class Profile(val id: Long) : Screen()
    data class Edit(val id: Long) : Screen()
    data class NewNote(val id: Long) : Screen()
    object Settings : Screen()
}

@Composable
fun Root(tick: Int) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val snap = rememberSnap(tick)
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    Backdrop {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith fadeOut(tween(140))
            },
            label = "nav"
        ) { s ->
            when (s) {
                is Screen.Home -> HomeScreen(
                    snap, tick,
                    onOpen = { stack.add(Screen.Profile(it)) },
                    onNewProfile = { stack.add(Screen.Edit(0L)) },
                    onSettings = { stack.add(Screen.Settings) }
                )
                is Screen.Profile -> ProfileScreen(
                    s.id, snap, onBack = { pop() },
                    onEdit = { stack.add(Screen.Edit(s.id)) },
                    onNewNote = { stack.add(Screen.NewNote(s.id)) }
                )
                is Screen.Edit -> EditProfileScreen(
                    s.id, onBack = { pop() },
                    onSaved = { newId ->
                        // creating a profile: jump straight into it
                        if (s.id == 0L) {
                            stack.removeAt(stack.lastIndex)
                            stack.add(Screen.Profile(newId))
                        } else pop()
                    },
                    onDeleted = {
                        while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    }
                )
                is Screen.NewNote -> NewNoteScreen(s.id, onBack = { pop() })
                is Screen.Settings -> SettingsScreen(tick, onBack = { pop() })
            }
        }
    }
}

// ───────────────────────── HOME ─────────────────────────

@Composable
fun HomeScreen(snap: Snap, tick: Int, onOpen: (Long) -> Unit, onNewProfile: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val setupOk = remember(tick, snap) { Setup.allOk(ctx) }
    val live = snap.items.filter { it.status == Status.PENDING || it.status == Status.AWAITING }
        .sortedWith(compareBy({ it.status != Status.AWAITING }, { it.timeMs }))

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Voice Scheduler", null) {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = Color.White) }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!setupOk) item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x40FFB74D))
                            .clickable { onSettings() }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Amber)
                        Spacer(Modifier.width(12.dp))
                        Text("Finish setup so sending works", color = Color.White, modifier = Modifier.weight(1f))
                        Text("Open", color = Green, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (live.isNotEmpty()) {
                    item {
                        Text("Up next", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                    items(live.take(3), key = { "n" + it.id }) { it ->
                        NextUpCard(it, snap.contacts.firstOrNull { c -> c.id == it.contactId }) {
                            if (it.status == Status.AWAITING)
                                ctx.startActivity(Notifs.approvalIntent(ctx, it.id))
                            else onOpen(it.contactId)
                        }
                    }
                }
                item {
                    Text("Profiles", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                if (snap.contacts.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().glass().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.PersonAdd, null, tint = Green, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Create your first profile", color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text("Add someone like Baba with a photo, then schedule voice notes for them.",
                            color = Muted, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(snap.contacts, key = { "c" + it.id }) { c ->
                    ProfileCard(c, snap.items.filter { it.contactId == c.id }) { onOpen(c.id) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewProfile, containerColor = Green, contentColor = Color.Black,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("New profile", fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}

@Composable
fun NextUpCard(item: Item, contact: Contact?, onClick: () -> Unit) {
    val waiting = item.status == Status.AWAITING
    val accent = if (waiting) Amber else Green
    Row(
        Modifier.fillMaxWidth().glass().border(BorderStroke(1.dp, accent.copy(alpha = if (waiting) 0.7f else 0.25f)),
            RoundedCornerShape(22.dp)).bounceClick(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(contact?.avatar, contact?.name ?: item.name, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(contact?.name ?: item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(fmtRel(item.timeMs), color = Muted, fontSize = 13.sp)
            }
        }
        if (waiting) {
            Text("APPROVE", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Amber)
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        } else StatusPill(item.status)
    }
}

@Composable
fun ProfileCard(c: Contact, items: List<Item>, onClick: () -> Unit) {
    val live = items.filter { it.status == Status.PENDING || it.status == Status.AWAITING }
    val next = live.minByOrNull { it.timeMs }
    val sent = items.count { it.status == Status.SENT }
    Row(
        Modifier.fillMaxWidth().glass().bounceClick(onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.avatar, c.name, 64.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+" + c.number, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (live.isEmpty()) "Nothing scheduled" else "${live.size} scheduled",
                    if (live.isEmpty()) Muted else Green)
                if (sent > 0) Chip("$sent sent", Muted)
            }
            if (next != null) {
                Text("Next: " + fmtRel(next.timeMs), color = Color(0xFFB0BEC5), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
fun Chip(text: String, col: Color) {
    Text(
        text, color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
