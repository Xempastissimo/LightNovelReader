package com.xempastissimo.lightnovelreader.ui.screen.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.repo.AppSettings
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

    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { settingsRepository.setKeepScreenOn(value) }

    fun setVolumeKeyPaging(value: Boolean) = viewModelScope.launch { settingsRepository.setVolumeKeyPaging(value) }

    fun setBarFollowsTheme(value: Boolean) = viewModelScope.launch { settingsRepository.setBarFollowsTheme(value) }

    fun setBarHue(value: Float) = viewModelScope.launch { settingsRepository.setBarHue(value) }

    fun setBarSaturation(value: Float) = viewModelScope.launch { settingsRepository.setBarSaturation(value) }

    fun setBarValue(value: Float) = viewModelScope.launch { settingsRepository.setBarValue(value) }

    fun resetBarColor() = viewModelScope.launch { settingsRepository.resetBarColor() }

    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

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
                    OutlinedButton(onClick = viewModel::logout, modifier = Modifier.padding(top = 8.dp)) {
                        Text("退出登录")
                    }
                } else {
                    Text(
                        text = "未登录。榜单、搜索与在线书架需要登录后使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenLogin, modifier = Modifier.padding(top = 8.dp)) {
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
                    onValueChange = viewModel::setFontSize,
                    valueRange = ReaderSettings.MIN_FONT_SIZE..ReaderSettings.MAX_FONT_SIZE,
                    steps = 19,
                )

                Text(
                    text = "行距 ${"%.1f".format(state.reader.lineHeightMultiplier)} 倍",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.lineHeightMultiplier,
                    onValueChange = viewModel::setLineHeight,
                    valueRange = ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
                )

                Text(
                    text = "段间距 ${state.reader.paragraphSpacingDp} dp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.paragraphSpacingDp.toFloat(),
                    onValueChange = { viewModel.setParagraphSpacing(it.toInt()) },
                    valueRange = 0f..40f,
                )

                Text(
                    text = "页边距 ${state.reader.horizontalPaddingDp} dp",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.reader.horizontalPaddingDp.toFloat(),
                    onValueChange = { viewModel.setHorizontalPadding(it.toInt()) },
                    valueRange = 0f..48f,
                )

                Text(text = "背景主题", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = ReaderTheme.entries,
                    selected = state.reader.theme,
                    label = { it.label },
                    onSelect = viewModel::setReaderTheme,
                )

                ReaderBarColorEditor(
                    settings = state.reader,
                    onFollowsTheme = viewModel::setBarFollowsTheme,
                    onHue = viewModel::setBarHue,
                    onSaturation = viewModel::setBarSaturation,
                    onValue = viewModel::setBarValue,
                    onReset = viewModel::resetBarColor,
                )

                SwitchRow(
                    title = "音量键翻页",
                    checked = state.reader.volumeKeyPaging,
                    onCheckedChange = viewModel::setVolumeKeyPaging,
                )
                Text(
                    text = "开启后，音量减键翻到下一页、音量加键翻回上一页。" +
                        "本章翻完后会接着进入上一章 / 下一章；只有在没有相邻章节时，" +
                        "音量键才恢复调节音量的作用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SwitchRow(
                    title = "阅读时保持屏幕常亮",
                    checked = state.reader.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn,
                )
            }

            // --------------------------------------------------------------- app
            SettingsCard(title = "外观") {
                Text(text = "深色模式", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = ThemeMode.entries,
                    selected = state.app.themeMode,
                    label = { it.label },
                    onSelect = viewModel::setThemeMode,
                )
                Text(
                    text = "「跟随系统」会随手机的深色模式设置一起切换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SwitchRow(
                    title = "使用系统动态取色",
                    checked = state.app.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
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
                    text = "图片缓存：${formatBytes(state.imageCacheBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::clearImageCache) { Text("清理图片缓存") }
                    OutlinedButton(onClick = viewModel::clearOfflineBooks) { Text("清理离线章节") }
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
                    text = "诊断：该站点对非浏览器客户端返回 403（Cloudflare 校验请求指纹）。" +
                        "下面的检查会用应用自带的浏览器内核打开站点首页，确认这条通路是否可用——" +
                        "这只影响直接抓取，不影响你在应用内登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = viewModel::probeBrowserEngine,
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

            Text(
                text = "轻小说阅读器 · 框架演示版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    SnackbarHost(hostState = snackbarHostState)
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
 */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
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
            OutlinedButton(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
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
    onFollowsTheme: (Boolean) -> Unit,
    onHue: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onValue: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val bar = readerBarPalette(settings, readerPalette(settings.theme))

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

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsCardPreview() {
    LightNovelReaderTheme {
        SettingsCard(title = "阅读外观") {
            Text("字号 16 sp", style = MaterialTheme.typography.bodyMedium)
            Text("行距 1.5 倍", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SwitchRowPreview() {
    LightNovelReaderTheme {
        SwitchRow(title = "阅读时保持屏幕常亮", checked = true, onCheckedChange = {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SwitchRowOffPreview() {
    LightNovelReaderTheme {
        SwitchRow(title = "使用系统动态取色", checked = false, onCheckedChange = {})
    }
}
