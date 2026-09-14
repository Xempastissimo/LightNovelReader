package com.xempastissimo.lightnovelreader.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.repo.AppSettings
import com.xempastissimo.lightnovelreader.data.repo.PageTurnMode
import com.xempastissimo.lightnovelreader.data.repo.ReaderSettings
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme
import com.xempastissimo.lightnovelreader.data.repo.SettingsRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.repo.ThemeMode
import com.xempastissimo.lightnovelreader.data.repo.formatBytes
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Urls
import com.xempastissimo.lightnovelreader.domain.model.UserSession
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import com.xempastissimo.lightnovelreader.ui.component.Motion
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme
import com.xempastissimo.lightnovelreader.ui.theme.ReaderBarPalette
import com.xempastissimo.lightnovelreader.ui.theme.readerBarPalette
import com.xempastissimo.lightnovelreader.ui.theme.readerPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val reader: ReaderSettings = ReaderSettings(),
    val app: AppSettings = AppSettings(),
    val session: UserSession? = null,
    val offlineBytes: Long = 0L,
    /** Whole-book packs: how many, and what they cost. */
    val packCount: Int = 0,
    val packBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val message: String? = null,
    val probing: Boolean = false,
    val probeResult: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val shelfRepository: ShelfRepository,
    private val source: BookSource,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { reader ->
                _state.update { it.copy(reader = reader) }
            }
        }
        viewModelScope.launch {
            settingsRepository.appSettings.collect { app ->
                _state.update { it.copy(app = app) }
            }
        }
        refreshAccountAndCache()
    }

    fun refreshAccountAndCache() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    session = source.currentSession(),
                    offlineBytes = container.bookRepository.totalOfflineSizeBytes(),
                    packCount = container.bookRepository.downloadedBooks().size,
                    packBytes = container.bookRepository.totalPackSizeBytes(),
                    imageCacheBytes = container.imageLoader.diskCacheSize(),
                )
            }
        }
    }

    fun setFontSize(value: Float) = viewModelScope.launch { settingsRepository.setFontSize(value) }

    fun setLineHeight(value: Float) = viewModelScope.launch { settingsRepository.setLineHeight(value) }

    fun setParagraphSpacing(value: Int) = viewModelScope.launch { settingsRepository.setParagraphSpacing(value) }

    fun setHorizontalPadding(value: Int) = viewModelScope.launch { settingsRepository.setHorizontalPadding(value) }

    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { settingsRepository.setReaderTheme(theme) }

    fun setPageTurnMode(mode: PageTurnMode) = viewModelScope.launch { settingsRepository.setPageTurnMode(mode) }

    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { settingsRepository.setKeepScreenOn(value) }

    fun setVolumeKeyPaging(value: Boolean) = viewModelScope.launch { settingsRepository.setVolumeKeyPaging(value) }

    fun setInvertVolumeKeyPaging(value: Boolean) =
        viewModelScope.launch { settingsRepository.setInvertVolumeKeyPaging(value) }

    fun setBarFollowsTheme(value: Boolean) = viewModelScope.launch { settingsRepository.setBarFollowsTheme(value) }

    fun setBarHue(value: Float) = viewModelScope.launch { settingsRepository.setBarHue(value) }

    fun setBarSaturation(value: Float) = viewModelScope.launch { settingsRepository.setBarSaturation(value) }

    fun setBarValue(value: Float) = viewModelScope.launch { settingsRepository.setBarValue(value) }

    fun resetBarColor() = viewModelScope.launch { settingsRepository.resetBarColor() }

    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    /**
     * OLED pure black, from the long press on the dark choices.
     *
     * Turning it *on* also selects the dark theme. OLED black is a property of the dark
     * background, so switching it on while the app is light would set a flag with nothing on screen
     * to show for it — and the gesture was made on a button that says 深色, so landing on the dark
     * theme is what the user asked for either way. Turning it off leaves the theme alone.
     *
     * A message is reported because the gesture is invisible: a long press on a chip that already
     * looks selected needs to say what it did.
     */
    fun toggleOledBlack() {
        val enabled = !_state.value.app.oledBlack
        viewModelScope.launch {
            if (enabled) settingsRepository.setThemeMode(ThemeMode.DARK)
            settingsRepository.setOledBlack(enabled)
            _state.update {
                it.copy(
                    message = if (enabled) {
                        "已开启 OLED 纯黑：深色背景为 #000000"
                    } else {
                        "已关闭 OLED 纯黑"
                    },
                )
            }
        }
    }

    fun logout() {
        source.logout()
        viewModelScope.launch {
            shelfRepository.replaceOnlineEntries(emptyList())
            refreshAccountAndCache()
            _state.update { it.copy(message = "已退出登录") }
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            container.imageLoader.clearDiskCache()
            refreshAccountAndCache()
            _state.update { it.copy(message = "图片缓存已清理") }
        }
    }

    fun clearOfflineBooks() {
        viewModelScope.launch {
            container.chapterCache.clear()
            refreshAccountAndCache()
            _state.update { it.copy(message = "离线章节已清理") }
        }
    }

    /**
     * Deletes the downloaded whole-book packs, and the bookmarks with them.
     *
     * Only the packs, plus the bookmarks: a downloaded book keeps working after this, because its
     * chapters were imported when it was downloaded and the reader prefers those. What goes is the
     * second copy of the text — the archive — which is the thing this button exists to reclaim.
     * Bookmarks go because they are notes about downloaded copies, and there are none left.
     */
    fun clearPacks() {
        viewModelScope.launch {
            container.bookRepository.deleteAllPacks()
            refreshAccountAndCache()
            _state.update { it.copy(message = "整本下载已清理") }
        }
    }

    /**
     * Diagnostic: load the source's home page through the app's own browser
     * engine.
     *
     * The plain HTTP client is rejected by the site's bot protection (Cloudflare
     * inspects the TLS fingerprint), so this tells the user — and the log —
     * whether the browser engine can reach the site from this device. Fetching
     * the page is exactly what a browser does; nothing is bypassed.
     */
    fun probeBrowserEngine() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true, probeResult = null) }
        viewModelScope.launch {
            val result = container.browserFetcher.fetch(Wenku8Urls.INDEX)
            val summary = when (result) {
                is com.xempastissimo.lightnovelreader.data.network.BrowserFetchResult.Success -> {
                    val html = result.html
                    val signedIn = html.contains("logout.php")
                    "✅ 浏览器内核可以访问站点：${html.length} 字符，${if (signedIn) "已处于登录状态" else "当前未登录"}"
                }

                is com.xempastissimo.lightnovelreader.data.network.BrowserFetchResult.Failure ->
                    "❌ 浏览器内核访问失败：${result.message}"
            }
            _state.update { it.copy(probing = false, probeResult = summary) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer) = AppViewModelFactory<SettingsViewModel> {
            SettingsViewModel(it.settingsRepository, it.shelfRepository, it.bookSource, it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalAppContainer.current)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    SettingsContent(
        state = state,
        actions = SettingsActions(
            onOpenLogin = onOpenLogin,
            onLogout = viewModel::logout,
            onFontSize = viewModel::setFontSize,
            onLineHeight = viewModel::setLineHeight,
            onParagraphSpacing = viewModel::setParagraphSpacing,
            onHorizontalPadding = viewModel::setHorizontalPadding,
            onReaderTheme = viewModel::setReaderTheme,
            onPageTurnMode = viewModel::setPageTurnMode,
            onVolumeKeyPaging = viewModel::setVolumeKeyPaging,
            onInvertVolumeKeyPaging = viewModel::setInvertVolumeKeyPaging,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onBarFollowsTheme = viewModel::setBarFollowsTheme,
            onBarHue = viewModel::setBarHue,
            onBarSaturation = viewModel::setBarSaturation,
            onBarValue = viewModel::setBarValue,
            onResetBarColor = viewModel::resetBarColor,
            onThemeMode = viewModel::setThemeMode,
            onToggleOledBlack = viewModel::toggleOledBlack,
            onDynamicColor = viewModel::setDynamicColor,
            onClearImageCache = viewModel::clearImageCache,
            onClearOfflineBooks = viewModel::clearOfflineBooks,
            onClearPacks = viewModel::clearPacks,
            onProbeBrowserEngine = viewModel::probeBrowserEngine,
        ),
    )

    SnackbarHost(hostState = snackbarHostState)
}

/**
 * Everything the settings list can do, in one value.
 *
 * Bundled into a data class with a default for each entry so the screen's body takes two
 * parameters instead of twenty, and so a preview can render the whole list by supplying
 * only the state. The defaults are deliberate no-ops rather than `{}`-style omissions: a
 * preview must not need a view model to exist, and a missing callback is a compile error
 * here rather than a dead button in the panel.
 */
data class SettingsActions(
    val onOpenLogin: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onFontSize: (Float) -> Unit = {},
    val onLineHeight: (Float) -> Unit = {},
    val onParagraphSpacing: (Int) -> Unit = {},
    val onHorizontalPadding: (Int) -> Unit = {},
    val onReaderTheme: (ReaderTheme) -> Unit = {},
    val onPageTurnMode: (PageTurnMode) -> Unit = {},
    val onVolumeKeyPaging: (Boolean) -> Unit = {},
    val onInvertVolumeKeyPaging: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onBarFollowsTheme: (Boolean) -> Unit = {},
    val onBarHue: (Float) -> Unit = {},
    val onBarSaturation: (Float) -> Unit = {},
    val onBarValue: (Float) -> Unit = {},
    val onResetBarColor: () -> Unit = {},
    val onThemeMode: (ThemeMode) -> Unit = {},
    val onToggleOledBlack: () -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onClearImageCache: () -> Unit = {},
    val onClearOfflineBooks: () -> Unit = {},
    val onClearPacks: () -> Unit = {},
    val onProbeBrowserEngine: () -> Unit = {},
)

/**
 * The settings list itself.
 *
 * Stateless and view-model-free on purpose: it is what the `@Preview`s render, so a
 * section can be adjusted in Android Studio without installing the app. The screen above
 * is the only part that knows about [SettingsViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions = SettingsActions(),
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ------------------------------------------------------------ account
            SettingsCard(title = "账号") {
                val session = state.session
                if (session != null) {
                    Text("已登录：${session.userName}", style = MaterialTheme.typography.bodyMedium)
                    if (session.groupName.isNotBlank()) {
                        Text(
                            text = "会员类型：${session.groupName}${if (session.honorName.isNotBlank()) " · ${session.honorName}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = actions.onLogout, modifier = Modifier.padding(top = 8.dp)) {
                        Text("退出登录")
                    }
                } else {
                    Text(
                        text = "未登录。榜单、搜索与在线书架需要登录后使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = actions.onOpenLogin, modifier = Modifier.padding(top = 8.dp)) {
                        Text("登录书源账号")
                    }
                }
            }

            // ------------------------------------------------------------- reader
            SettingsCard(title = "阅读外观") {
                Text(
                    text = "字号 ${state.reader.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.fontSizeSp,
                    onValueChange = actions.onFontSize,
                    valueRange = ReaderSettings.MIN_FONT_SIZE..ReaderSettings.MAX_FONT_SIZE,
                    steps = 19,
                )

                Text(
                    text = "行距 ${"%.1f".format(state.reader.lineHeightMultiplier)} 倍",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.lineHeightMultiplier,
                    onValueChange = actions.onLineHeight,
                    valueRange = ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
                )

                Text(
                    text = "段间距 ${state.reader.paragraphSpacingDp} dp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.paragraphSpacingDp.toFloat(),
                    onValueChange = { actions.onParagraphSpacing(it.toInt()) },
                    valueRange = 0f..40f,
                )

                Text(
                    text = "页边距 ${state.reader.horizontalPaddingDp} dp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.horizontalPaddingDp.toFloat(),
                    onValueChange = { actions.onHorizontalPadding(it.toInt()) },
                    valueRange = 0f..48f,
                )

                Text(text = "背景主题", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = ReaderTheme.entries,
                    selected = state.reader.theme,
                    label = { it.label },
                    onSelect = actions.onReaderTheme,
                    // 夜间 is this row's dark choice, so it is where the OLED gesture belongs.
                    onLongSelect = { theme ->
                        if (theme == ReaderTheme.DARK) actions.onToggleOledBlack()
                    },
                )
                Text(
                    text = oledHint(state.app.oledBlack),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.app.oledBlack) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Text(text = "翻页方向", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = PageTurnMode.entries,
                    selected = state.reader.pageTurnMode,
                    label = { it.label },
                    onSelect = actions.onPageTurnMode,
                )
                Text(
                    text = "「左右翻页」把本章按屏幕高度真实分页，一页正好一屏，左右滑动或音量键翻页；" +
                        "「上下滚动」是一整章连续滚动，不预先分页。" +
                        "两种模式都会预加载相邻章节，翻到下一章不用等。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ReaderBarColorEditor(
                    settings = state.reader,
                    oledBlack = state.app.oledBlack,
                    onFollowsTheme = actions.onBarFollowsTheme,
                    onHue = actions.onBarHue,
                    onSaturation = actions.onBarSaturation,
                    onValue = actions.onBarValue,
                    onReset = actions.onResetBarColor,
                )

                SwitchRow(
                    title = "音量键翻页",
                    checked = state.reader.volumeKeyPaging,
                    onCheckedChange = actions.onVolumeKeyPaging,
                )
                Text(
                    text = "开启后，音量减键前进（左右翻页翻一页、上下滚动滚一屏）、音量加键后退。" +
                        "本章翻完后会接着进入上一章 / 下一章；只有在没有相邻章节时，" +
                        "音量键才恢复调节音量的作用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Only while 音量键翻页 is on: the choice means nothing otherwise, and a
                // switch that is guaranteed to do nothing is worse than no switch. It
                // expands into place rather than appearing outright, so the row that just
                // arrived is legible as having come from the switch above it.
                AnimatedVisibility(
                    visible = state.reader.volumeKeyPaging,
                    enter = expandVertically(animationSpec = tween(Motion.ENTER_MILLIS)) +
                        fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)),
                    exit = shrinkVertically(animationSpec = tween(Motion.EXIT_MILLIS)) +
                        fadeOut(animationSpec = tween(Motion.EXIT_MILLIS)),
                    label = "invertVolumeKeys",
                ) {
                    Column {
                        SwitchRow(
                            title = "反转音量翻页",
                            checked = state.reader.invertVolumeKeyPaging,
                            onCheckedChange = actions.onInvertVolumeKeyPaging,
                        )
                        Text(
                            text = "方向反过来：音量加键翻到下一页、音量减键翻回上一页。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SwitchRow(
                    title = "阅读时保持屏幕常亮",
                    checked = state.reader.keepScreenOn,
                    onCheckedChange = actions.onKeepScreenOn,
                )
            }

            // --------------------------------------------------------------- app
            SettingsCard(title = "外观") {
                Text(text = "深色模式", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = ThemeMode.entries,
                    selected = state.app.themeMode,
                    label = { it.label },
                    onSelect = actions.onThemeMode,
                    onLongSelect = { mode ->
                        if (mode == ThemeMode.DARK) actions.onToggleOledBlack()
                    },
                )
                Text(
                    text = "「跟随系统」会随手机的深色模式设置一起切换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = oledHint(state.app.oledBlack),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.app.oledBlack) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                SwitchRow(
                    title = "使用系统动态取色",
                    checked = state.app.useDynamicColor,
                    onCheckedChange = actions.onDynamicColor,
                )
                Text(
                    text = "关闭时使用应用自带的纸张配色，阅读更统一。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------------------- cache
            SettingsCard(title = "存储") {
                Text(
                    text = "离线章节：${formatBytes(state.offlineBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "整本下载：${state.packCount} 本 · ${formatBytes(state.packBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "图片缓存：${formatBytes(state.imageCacheBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "「整本下载」是站点打包的 txt，与导入的章节各占一份空间；" +
                        "清理它之后已下载的书仍可离线阅读，只是不能再从本机 txt 重新导入。" +
                        "已下载小说的本地书签也会一并清除——书签是给已下载的书做标记的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = actions.onClearImageCache) { Text("清理图片缓存") }
                    OutlinedButton(onClick = actions.onClearOfflineBooks) { Text("清理离线章节") }
                }
                OutlinedButton(
                    onClick = actions.onClearPacks,
                    enabled = state.packCount > 0,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("清理整本下载")
                }
            }

            // ------------------------------------------------------------ source
            SettingsCard(title = "书源") {
                Text(
                    text = "当前书源：轻小说文库（www.wenku8.net）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "所有请求串行发送，频率约 1 次/秒，并遵守站点的限流响应；" +
                        "离线缓存仅供个人在已授权范围内阅读使用，请勿再分发。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "「下载全本」用的是站点下载页自己给出的地址（dl.wenku8.com），" +
                        "不是自造的接口；打包文件同样只供个人阅读，请勿再分发或上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    text = "诊断：该站点对非浏览器客户端返回 403（Cloudflare 校验请求指纹）。" +
                        "下面的检查会用应用自带的浏览器内核打开站点首页，确认这条通路是否可用——" +
                        "这只影响直接抓取，不影响你在应用内登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = actions.onProbeBrowserEngine,
                    enabled = !state.probing,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(if (state.probing) "检查中…" else "检查浏览器内核是否可访问站点")
                }
                state.probeResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (result.startsWith("✅")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // -------------------------------------------------------- developer
            // The last card in the list, and the only one that leaves the app: it opens
            // the project's GitHub page so issues and PRs have an address the user can
            // actually reach from here.
            SettingsCard(
                title = "开发者",
                onClick = { context.openInBrowser(DEVELOPER_PAGE_URL) },
            ) {
                Text(
                    text = "GitHub 项目主页",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = DEVELOPER_PAGE_URL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "点击此处打开项目主页，可以查看源码、反馈问题或提交 PR。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "轻小说阅读器 · 框架演示版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/**
 * One section of the settings list: a hairline outline, no fill.
 *
 * The sections used to be filled with `surface`, which is a shade lighter than the page's
 * own background (`NightSurface` / `PaperSurface` against `NightBackground` / `PaperWarm`),
 * so the page read as a stack of raised slabs rather than one background with sections on
 * it. Only the border and the section title separate them now.
 *
 * This is a `Surface` and not a `Card` on purpose. `Card` passes its container colour
 * through `surfaceColorAtElevation`, which replaces a *transparent* colour with an opaque
 * tonal-elevation one — `cardColors(containerColor = Color.Transparent)` therefore still
 * paints a raised tile, and there is no way to opt out from the outside. `Surface` paints
 * the colour it is given, so `Color.Transparent` really is nothing.
 *
 * [onClick] is null for the purely informational sections; those must not ripple — or be
 * announced as buttons — when the user taps their text.
 */
@Composable
private fun SettingsCard(
    title: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        onClick = onClick,
                        role = Role.Button,
                        // Without this a `clickable` inside a lazy list draws the
                        // ripple of whatever scrolled under the finger.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                    )
                },
            ),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            content()
        }
    }
}

/**
 * Hands a link to whatever the device has: a browser, or a chooser if there is more
 * than one. Does nothing when there is nothing at all — a settings card must not crash
 * over a missing browser.
 */
private fun Context.openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A row of equal-width choices.
 *
 * The selected label eases between colours rather than switching instantly, which is
 * what makes a tap on a three-way choice register as having done something.
 *
 * [onLongSelect] adds a second, hidden action to each chip — currently OLED pure black on the two
 * dark choices. The chips are therefore a `Surface` with `combinedClickable` rather than an
 * `OutlinedButton`: a button owns its own click handling, so stacking a `pointerInput` long-press
 * on top of it makes the long press fire the button's click as well, in an order that depends on
 * pointer dispatch rather than on anything this file decides. The shape, border and 40 dp minimum
 * height are Material's own outlined-button values, so the row looks unchanged.
 *
 * A long press selects the option first and then runs [onLongSelect]. `combinedClickable` fires
 * *either* the click or the long click, never both, so without this a long press on a chip that has
 * no second action — 跟随系统, 浅色 — would be a gesture that does nothing at all.
 */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onLongSelect: ((T) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val labelColor by animateColorAsState(
                targetValue = if (option == selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                animationSpec = tween(durationMillis = Motion.ENTER_MILLIS),
                label = "choiceLabel",
            )
            val shape = RoundedCornerShape(20.dp)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = { onSelect(option) },
                        onLongClick = onLongSelect?.let { long ->
                            {
                                onSelect(option)
                                long(option)
                            }
                        },
                    ),
                shape = shape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        color = labelColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * Picks the colour of the reader's floating bars.
 *
 * Editing happens in HSV with three sliders rather than as a hex field, because the
 * bars are seen behind text: hue chooses the family, saturation how strong, and value
 * how dark — and each of those is a single judgement the preview makes obvious. The
 * ink on the bar is derived from the result, so no combination can end up unreadable.
 */
@Composable
private fun ReaderBarColorEditor(
    settings: ReaderSettings,
    oledBlack: Boolean,
    onFollowsTheme: (Boolean) -> Unit,
    onHue: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onValue: (Float) -> Unit,
    onReset: () -> Unit,
) {
    // The preview has to be the colour the bars will actually be, which under 「跟随阅读背景」
    // includes whether the page behind them is OLED black.
    val bar = readerBarPalette(settings, readerPalette(settings.theme, oledBlack))

    Text(text = "顶栏 / 底栏颜色", style = MaterialTheme.typography.bodyMedium)
    ReaderBarPreview(bar)

    SwitchRow(
        title = "跟随阅读背景",
        checked = settings.barFollowsTheme,
        onCheckedChange = onFollowsTheme,
    )

    if (settings.barFollowsTheme) {
        Text(
            text = "顶栏与底栏使用当前的阅读背景色，和书页连成一片。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = "色相 ${settings.barHue.toInt()}°",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(value = settings.barHue, onValueChange = onHue, valueRange = 0f..360f)

    Text(
        text = "饱和度 ${(settings.barSaturation * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(value = settings.barSaturation, onValueChange = onSaturation, valueRange = 0f..1f)

    Text(
        text = "明度 ${(settings.barValue * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(value = settings.barValue, onValueChange = onValue, valueRange = 0f..1f)

    TextButton(onClick = onReset) { Text("恢复默认蓝色") }
}

/** The project's GitHub page, opened by the 开发者 card at the foot of the screen. */
private const val DEVELOPER_PAGE_URL = "https://github.com/Xempastissimo/LightNovelReader"

/**
 * What the OLED line under either theme row says.
 *
 * One string for both rows because it is one switch: the long press on 深色 and the long press on
 * 夜间 change the same setting, and the app and the reader then agree. Saying which state it is in
 * matters because the gesture leaves no other trace on a chip that already looks selected.
 */
private fun oledHint(enabled: Boolean): String =
    "长按「深色」或「夜间」可切换 OLED 纯黑：深色背景使用 #000000，OLED 屏上更省电。" +
        "当前：" + if (enabled) "已开启（应用与阅读页都是纯黑背景）" else "未开启"

/** A miniature of the reader's bars, so the colour can be judged before it is used. */
@Composable
private fun ReaderBarPreview(bar: ReaderBarPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bar.container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第一章 示例章节",
            color = bar.content,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "上一章 · 下一章",
            color = bar.content,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Preview(name = "分区卡片 · 浅色", showBackground = true, widthDp = 360)
@Composable
private fun SettingsCardPreview() {
    LightNovelReaderTheme(dynamicColor = false) {
        SettingsCard(title = "阅读外观") {
            Text("字号 16 sp", style = MaterialTheme.typography.bodyMedium)
            Text("行距 1.5 倍", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The same section at night: the border has to carry the shape without the fill. */
@Preview(
    name = "分区卡片 · 夜间",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsCardNightPreview() {
    LightNovelReaderTheme(darkTheme = true, dynamicColor = false) {
        SettingsCard(title = "阅读外观") {
            Text("字号 16 sp", style = MaterialTheme.typography.bodyMedium)
            Text("行距 1.5 倍", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "开关 · 开", showBackground = true, widthDp = 360)
@Composable
private fun SwitchRowPreview() {
    LightNovelReaderTheme {
        SwitchRow(title = "阅读时保持屏幕常亮", checked = true, onCheckedChange = {})
    }
}

@Preview(name = "开关 · 关", showBackground = true, widthDp = 360)
@Composable
private fun SwitchRowOffPreview() {
    LightNovelReaderTheme {
        SwitchRow(title = "使用系统动态取色", checked = false, onCheckedChange = {})
    }
}
