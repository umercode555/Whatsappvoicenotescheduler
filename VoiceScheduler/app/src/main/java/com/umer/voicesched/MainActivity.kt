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
        // Idempotent: re-arms future alarms, shows anything waiting for approval. It can never
        // start a send by itself (the old code did, which could repeat notes).
        try { Recovery.run(this, false) } catch (e: Exception) {}
        tick++
    }
}
