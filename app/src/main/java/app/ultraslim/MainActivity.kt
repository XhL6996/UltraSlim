package app.ultraslim

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultraslim.data.ThemeSettings
import app.ultraslim.ui.AppRoot
import app.ultraslim.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) forwardShareIntent(intent)
        setContent {
            val app = application as UltraSlimApp
            val themeSettings by app.settingsRepository.theme
                .collectAsStateWithLifecycle(initialValue = ThemeSettings())
            AppTheme(themeMode = themeSettings.themeMode, dynamicColor = themeSettings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardShareIntent(intent)
    }

    private fun forwardShareIntent(intent: Intent?) {
        val uris = extractStreamUris(intent)
        if (uris.isNotEmpty()) {
            (application as UltraSlimApp).conversionRepository.submitIncoming(uris)
        }
    }

    /**
     * Reads ACTION_SEND / ACTION_SEND_MULTIPLE image URIs. Some senders attach
     * EXTRA_STREAM loosely typed, so fall back to lenient reads when the strict
     * APIs come up empty.
     */
    @Suppress("DEPRECATION")
    private fun extractStreamUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(out::add)

            Intent.ACTION_SEND_MULTIPLE ->
                runCatching {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                }.getOrNull()?.let(out::addAll)
        }
        if (out.isEmpty() && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            runCatching { intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM) }.getOrNull()?.let(out::add)
            runCatching {
                intent.getParcelableArrayExtra(Intent.EXTRA_STREAM)?.mapNotNull { it as? Uri }
            }.getOrNull()?.let(out::addAll)
            intent.data?.takeIf { it.scheme == "content" }?.let(out::add)
        }
        return out.distinct()
    }
}
