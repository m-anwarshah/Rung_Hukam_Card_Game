package com.champ.rung

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.champ.rung.ui.RungApp
import com.champ.rung.ui.theme.RungTheme

class MainActivity : ComponentActivity() {

    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Card games involve long stretches of looking without tapping; keep the
        // screen awake instead of holding a wake lock (Play policy friendly).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RungTheme {
                RungApp(vm)
            }
        }
    }
}
