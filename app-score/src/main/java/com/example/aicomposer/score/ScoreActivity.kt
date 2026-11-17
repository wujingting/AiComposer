package com.example.aicomposer.score

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.aicomposer.ui.AiComposerTheme
import com.example.aicomposer.ui.CoachColors
import com.example.aicomposer.i18n.I18n

class ScoreActivity : ComponentActivity() {
    private val vm: ScoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI language follows the system: Chinese, Japanese, otherwise English
        I18n.lang = I18n.fromLocale(resources.configuration.locales[0])
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AiComposerTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = CoachColors.Paper) {
                    // Keep content out from under the system bars (Android 15+ always draws edge to edge)
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ScoreScreen(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Photos shared from the gallery ("Share" -> this app). */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.add(uris)
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
