package com.example.aicomposer.phone

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.aicomposer.ui.AiComposerTheme
import com.example.aicomposer.ui.CoachColors
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aicomposer.i18n.I18n

class PhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI language follows the system: Chinese, Japanese, otherwise English
        I18n.lang = I18n.fromLocale(resources.configuration.locales[0])
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // keep the screen on while shooting
        setContent {
            AiComposerTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = CoachColors.Paper) {
                    // Keep content out from under the status bar, navigation bar and display cutout
                    // (Android 15+ always draws apps edge to edge)
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        PhoneScreen(viewModel())
                    }
                }
            }
        }
    }
}
