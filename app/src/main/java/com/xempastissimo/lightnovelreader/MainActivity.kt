package com.xempastissimo.lightnovelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xempastissimo.lightnovelreader.data.repo.AppSettings
import com.xempastissimo.lightnovelreader.data.repo.ThemeMode
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import com.xempastissimo.lightnovelreader.ui.navigation.AppNavHost
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as App).container
        container.warmUp()

        setContent {
            val appSettings by container.settingsRepository.appSettings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            CompositionLocalProvider(LocalAppContainer provides container) {
                LightNovelReaderTheme(
                    darkTheme = appSettings.themeMode.isDark(),
                    dynamicColor = appSettings.useDynamicColor,
                    oledBlack = appSettings.oledBlack,
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

/**
 * Resolves the stored mode against the device.
 *
 * The default stays `SYSTEM` so an existing install keeps behaving exactly as it did
 * before the setting existed.
 */
@Composable
private fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
