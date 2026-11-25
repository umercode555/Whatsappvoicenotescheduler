package com.umer.voicesched

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent { AppTheme { Root(tick) } }
    }

    override fun onResume() {
        super.onResume()
        Store.expireOverdue()
        val now = System.currentTimeMillis()
        for (it in Store.all().filter { s -> s.status == Status.PENDING }) {
            try {
                if (it.timeMs > now) Scheduler.schedule(this, it) else SendService.start(this, it.id)
            } catch (e: Exception) {
            }
        }
        tick++
    }
}
