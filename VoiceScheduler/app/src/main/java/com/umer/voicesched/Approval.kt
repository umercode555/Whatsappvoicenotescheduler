package com.umer.voicesched

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen approval. Shows over the lock screen (full-screen notification) and again right after
 * unlock. Nothing is sent until APPROVE is pressed; if the phone is locked Android asks for the
 * PIN / fingerprint first, then the send starts. Approving is atomic ([Store.approve]) so double taps,
 * repeated notifications or two open copies of this screen can never start the same send twice.
 */
class ApprovalActivity : ComponentActivity() {
    private var curId by mutableLongStateOf(-1L)
    private var approving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        curId = intent.getLongExtra("id", -1L)
        setContent {
            AppTheme {
                ApprovalScreen(
                    curId,
                    onApprove = { approve(it) },
                    onSnooze = { id ->
                        val n = Store.snooze(id, 10 * 60_000L)
                        if (n != null) {
                            Scheduler.schedule(this, n)
                            Notifs.cancelApproval(this, id)
                            AlarmService.silence()
                            AlarmService.changed()
                            toast(this, "Snoozed for 10 minutes")
                        }
                        finish()
                    },
                    onSkip = { id ->
                        Store.skip(id)
                        Notifs.cancelApproval(this, id)
                        AlarmService.silence()
                        AlarmService.changed()
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        curId = intent.getLongExtra("id", -1L)
        approving = false
    }

    private fun approve(id: Long) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { doApprove(id) }
                override fun onDismissCancelled() { toast(this@ApprovalActivity, "Unlock your phone to send") }
                override fun onDismissError() { toast(this@ApprovalActivity, "Unlock your phone to send") }
            })
        } else {
            doApprove(id)
        }
    }

    private fun doApprove(id: Long) {
        if (approving) return
        approving = true
        val it = Store.approve(id)
        AlarmService.silence()
        Notifs.cancelApproval(this, id)
        AlarmService.changed()
        Player.stop()
        if (it != null) {
            try {
                SendService.start(this, id)
            } catch (e: Exception) {
                Store.finish(id, false, "Android blocked the send service: " + e.message)
            }
        }
        finish()
    }
}

@Composable
fun ApprovalScreen(
    startId: Long, onApprove: (Long) -> Unit, onSnooze: (Long) -> Unit, onSkip: (Long) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val km = remember { ctx.getSystemService(KeyguardManager::class.java) }
    var item by remember { mutableStateOf<Item?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(km.isKeyguardLocked) }

    LaunchedEffect(startId) {
        while (true) {
            item = Store.get(startId)?.takeIf { it.status == Status.AWAITING }
                ?: Store.all().filter { it.status == Status.AWAITING }.minByOrNull { it.timeMs }
            loaded = true
            locked = km.isKeyguardLocked
            // the user is looking at an unlocked phone: stop the noise, keep the screen
            if (!locked) AlarmService.silence()
            delay(600)
        }
    }
    DisposableEffect(Unit) { onDispose { Player.stop() } }
    LaunchedEffect(loaded, item == null) {
        if (loaded && item == null) {
            delay(1200)
            onClose()
        }
    }

    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(
        1f, 1.22f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p"
    )

    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFF0B141A), Color(0xFF0E3B33), Color(0xFF0B141A))
            )
        )
    ) {
        val cur = item
        if (cur == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (loaded) "Nothing waiting for approval" else "…", color = Muted, fontSize = 18.sp)
            }
        } else {
            val contact = Store.contact(cur.contactId)
            val who = contact?.name ?: cur.name
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(48.dp))
                Text("VOICE NOTE READY TO SEND", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp)
                Spacer(Modifier.height(36.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp)) {
                    Box(Modifier.size((150 * pulse).dp).clip(CircleShape).background(Green.copy(alpha = 0.14f)))
                    Avatar(contact?.avatar, who, 132.dp)
                }
                Spacer(Modifier.height(24.dp))
                Text(who, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Scheduled for " + fmtRel(cur.timeMs), color = Muted, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Glass).clickable {
                        if (playing) { Player.stop(); playing = false }
                        else { playing = true; Player.play(cur.file) { playing = false } }
                    }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Green)
                    Spacer(Modifier.width(8.dp))
                    Text("Preview · " + fmtDur(cur.durMs), color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                if (locked) {
                    Text("Your phone will ask you to unlock first", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                Button(
                    onClick = { Player.stop(); playing = false; onApprove(cur.id) },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                    shape = RoundedCornerShape(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("APPROVE & SEND", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { Player.stop(); onSnooze(cur.id) },
                        modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(50)
                    ) { Text("Snooze 10 min", color = Color.White) }
                    TextButton(
                        onClick = { Player.stop(); onSkip(cur.id) },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Skip this one", color = Muted) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
