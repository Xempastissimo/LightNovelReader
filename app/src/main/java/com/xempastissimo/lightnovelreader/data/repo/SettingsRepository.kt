package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Reading appearance: everything the reader settings sheet can change. */
enum class ReaderTheme(val label: String) {
    PAPER("米黄"),
    LIGHT("白纸"),
    DARK("夜间"),
    GREEN("护眼"),
}

/** Whether the app follows the device's light/dark setting or is pinned to one. */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.7f,
    val paragraphSpacingDp: Int = 12,
    val horizontalPaddingDp: Int = 20,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val keepScreenOn: Boolean = true,
    val pageTurnAnimation: Boolean = true,
    /** Volume-down turns the page forward and volume-up turns it back. */
    val volumeKeyPaging: Boolean = false,
    /** Paint the reader's bars with the reading theme instead of the colour below. */
    val barFollowsTheme: Boolean = false,
    /** The reader bar colour, stored as HSV because that is what the sliders edit. */
    val barHue: Float = DEFAULT_BAR_HUE,
    val barSaturation: Float = DEFAULT_BAR_SATURATION,
    val barValue: Float = DEFAULT_BAR_VALUE,
) {
    companion object {
        const val MIN_FONT_SIZE = 12f
        const val MAX_FONT_SIZE = 32f
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.6f

        /**
         * HSV of the stock reader-bar blue (`0xFF1E5AA8`).
         *
         * The bars used to be a compile-time constant; storing the exact HSV behind
         * it means a user who never opens the colour controls still gets precisely
         * the bar the app has always drawn.
         */
        const val DEFAULT_BAR_HUE = 213.91f
        const val DEFAULT_BAR_SATURATION = 0.8214f
        const val DEFAULT_BAR_VALUE = 0.6588f
    }
}

/** App level preferences, separate from the reader so each screen observes only what it needs. */
data class AppSettings(
    val useDynamicColor: Boolean = true,
    val downloadConcurrency: Int = 1,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences backed by DataStore.
 *
 * DataStore is already part of the dependency set and gives a `Flow` out of the
 * box, which keeps the Compose layer free of manual listeners.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsStore)

    val readerSettings: Flow<ReaderSettings> = store.data.map { preferences ->
        ReaderSettings(
            fontSizeSp = preferences[KEY_FONT_SIZE] ?: ReaderSettings().fontSizeSp,
            lineHeightMultiplier = preferences[KEY_LINE_HEIGHT] ?: ReaderSettings().lineHeightMultiplier,
            paragraphSpacingDp = preferences[KEY_PARAGRAPH_SPACING] ?: ReaderSettings().paragraphSpacingDp,
            horizontalPaddingDp = preferences[KEY_HORIZONTAL_PADDING] ?: ReaderSettings().horizontalPaddingDp,
            theme = preferences[KEY_THEME]?.let { name ->
                ReaderTheme.entries.firstOrNull { it.name == name }
            } ?: ReaderSettings().theme,
            keepScreenOn = preferences[KEY_KEEP_SCREEN_ON] ?: ReaderSettings().keepScreenOn,
            pageTurnAnimation = preferences[KEY_PAGE_TURN_ANIMATION] ?: ReaderSettings().pageTurnAnimation,
            volumeKeyPaging = preferences[KEY_VOLUME_KEY_PAGING] ?: ReaderSettings().volumeKeyPaging,
            barFollowsTheme = preferences[KEY_BAR_FOLLOWS_THEME] ?: ReaderSettings().barFollowsTheme,
            barHue = preferences[KEY_BAR_HUE] ?: ReaderSettings().barHue,
            barSaturation = preferences[KEY_BAR_SATURATION] ?: ReaderSettings().barSaturation,
            barValue = preferences[KEY_BAR_VALUE] ?: ReaderSettings().barValue,
        )
    }

    val appSettings: Flow<AppSettings> = store.data.map { preferences ->
        AppSettings(
            useDynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: AppSettings().useDynamicColor,
            downloadConcurrency = preferences[KEY_DOWNLOAD_CONCURRENCY] ?: AppSettings().downloadConcurrency,
            themeMode = preferences[KEY_THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: AppSettings().themeMode,
        )
    }

    suspend fun setFontSize(value: Float) = put(KEY_FONT_SIZE, value.coerceIn(ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE))

    suspend fun setLineHeight(value: Float) = put(KEY_LINE_HEIGHT, value.coerceIn(ReaderSettings.MIN_LINE_HEIGHT, ReaderSettings.MAX_LINE_HEIGHT))

    suspend fun setParagraphSpacing(value: Int) = put(KEY_PARAGRAPH_SPACING, value.coerceIn(0, 48))

    suspend fun setHorizontalPadding(value: Int) = put(KEY_HORIZONTAL_PADDING, value.coerceIn(0, 48))

    suspend fun setReaderTheme(theme: ReaderTheme) = put(KEY_THEME, theme.name)

    suspend fun setKeepScreenOn(value: Boolean) = put(KEY_KEEP_SCREEN_ON, value)

    suspend fun setPageTurnAnimation(value: Boolean) = put(KEY_PAGE_TURN_ANIMATION, value)

    suspend fun setVolumeKeyPaging(value: Boolean) = put(KEY_VOLUME_KEY_PAGING, value)

    suspend fun setBarFollowsTheme(value: Boolean) = put(KEY_BAR_FOLLOWS_THEME, value)

    suspend fun setBarHue(value: Float) = put(KEY_BAR_HUE, value.coerceIn(0f, 360f))

    suspend fun setBarSaturation(value: Float) = put(KEY_BAR_SATURATION, value.coerceIn(0f, 1f))

    suspend fun setBarValue(value: Float) = put(KEY_BAR_VALUE, value.coerceIn(0f, 1f))

    /** Puts the bar colour back to the stock blue, leaving "follow the theme" alone. */
    suspend fun resetBarColor() {
        store.edit { preferences ->
            preferences.remove(KEY_BAR_HUE)
            preferences.remove(KEY_BAR_SATURATION)
            preferences.remove(KEY_BAR_VALUE)
        }
    }

    suspend fun setDynamicColor(value: Boolean) = put(KEY_DYNAMIC_COLOR, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME_MODE, mode.name)

    suspend fun setDownloadConcurrency(value: Int) = put(KEY_DOWNLOAD_CONCURRENCY, value.coerceIn(1, 3))

    /** Wipes every stored preference (used by "重置设置"). */
    suspend fun resetReaderSettings() {
        store.edit { preferences ->
            preferences.remove(KEY_FONT_SIZE)
            preferences.remove(KEY_LINE_HEIGHT)
            preferences.remove(KEY_PARAGRAPH_SPACING)
            preferences.remove(KEY_HORIZONTAL_PADDING)
            preferences.remove(KEY_THEME)
            preferences.remove(KEY_KEEP_SCREEN_ON)
            preferences.remove(KEY_PAGE_TURN_ANIMATION)
            preferences.remove(KEY_VOLUME_KEY_PAGING)
            preferences.remove(KEY_BAR_FOLLOWS_THEME)
            preferences.remove(KEY_BAR_HUE)
            preferences.remove(KEY_BAR_SATURATION)
            preferences.remove(KEY_BAR_VALUE)
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { preferences -> preferences[key] = value }
    }

    private companion object {
        val KEY_FONT_SIZE = floatPreferencesKey("reader_font_size")
        val KEY_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val KEY_PARAGRAPH_SPACING = intPreferencesKey("reader_paragraph_spacing")
        val KEY_HORIZONTAL_PADDING = intPreferencesKey("reader_horizontal_padding")
        val KEY_THEME = stringPreferencesKey("reader_theme")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("reader_keep_screen_on")
        val KEY_PAGE_TURN_ANIMATION = booleanPreferencesKey("reader_page_turn_animation")
        val KEY_VOLUME_KEY_PAGING = booleanPreferencesKey("reader_volume_key_paging")
        val KEY_BAR_FOLLOWS_THEME = booleanPreferencesKey("reader_bar_follows_theme")
        val KEY_BAR_HUE = floatPreferencesKey("reader_bar_hue")
        val KEY_BAR_SATURATION = floatPreferencesKey("reader_bar_saturation")
        val KEY_BAR_VALUE = floatPreferencesKey("reader_bar_value")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("app_dynamic_color")
        val KEY_DOWNLOAD_CONCURRENCY = intPreferencesKey("app_download_concurrency")
        val KEY_THEME_MODE = stringPreferencesKey("app_theme_mode")
    }
}
