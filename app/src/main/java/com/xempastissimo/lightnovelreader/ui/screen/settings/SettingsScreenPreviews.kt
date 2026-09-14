package com.xempastissimo.lightnovelreader.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.xempastissimo.lightnovelreader.data.repo.AppSettings
import com.xempastissimo.lightnovelreader.data.repo.PageTurnMode
import com.xempastissimo.lightnovelreader.data.repo.ReaderSettings
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme
import com.xempastissimo.lightnovelreader.data.repo.ThemeMode
import com.xempastissimo.lightnovelreader.domain.model.UserSession
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme

/**
 * Previews for the whole settings screen.
 *
 * Screen-level previews deliberately live in their own file, away from the screen: a
 * `@Preview` drags in the tooling and a pile of fixtures that the shipping code has no
 * other use for, and keeping them apart is what lets `SettingsScreen.kt` be read as just
 * the screen. `SettingsContent` is stateless exactly so that these can exist — the
 * shipping screen is the only part that needs a view model and a network.
 *
 * How to use them in Android Studio:
 *  * open this file and use the **Split** or **Design** button (top right of the editor);
 *  * every name below appears in the drop-down at the top of the Preview panel, so the
 *    states can be flipped between without editing anything;
 *  * `uiMode` on the two 外观 previews is what switches the whole palette between the
 *    light and dark scheme — that is the no-code way to check a colour change in both.
 */
@Preview(name = "设置 · 未登录", showBackground = true, widthDp = 412, heightDp = 1400)
@Preview(name = "设置 · 已登录", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
private fun SettingsScreenPreview(
    @PreviewParameter(SettingsPreviewStates::class) state: SettingsUiState,
) {
    LightNovelReaderTheme(darkTheme = false, dynamicColor = false) {
        SettingsContent(state = state)
    }
}

/**
 * The screen at night, drawn with the app's own paper palette rather than the system's
 * dynamic one.
 *
 * `dynamicColor = false` on purpose even though the app ships with dynamic colour on by
 * default: the dynamic palette is generated from the user's wallpaper, so a preview that
 * used it would show different colours on every machine and could not be used to judge a
 * contrast change. The app's own palette is the one that is actually designed here.
 */
@Preview(
    name = "设置 · 夜间",
    showBackground = true,
    widthDp = 412,
    heightDp = 1400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsScreenNightPreview() {
    LightNovelReaderTheme(darkTheme = true, dynamicColor = false) {
        SettingsContent(state = SettingsPreviewStates.loggedIn)
    }
}

/**
 * The states worth looking at side by side.
 *
 * Selected through the Preview panel's drop-down rather than by editing the preview, which
 * is the whole point of `@PreviewParameter`: the rows that only appear in one state —
 * 退出登录 versus 登录书源账号, the ✔/✖ diagnostic line — can be compared by flipping a
 * selector instead of commenting code in and out.
 *
 * Deliberately **not** private. The preview tooling resolves the provider by reflection,
 * so a private one can fail to load ("PreviewParameterProvider not found") even though it
 * compiles; the same applies to the `values` property.
 */
class SettingsPreviewStates : PreviewParameterProvider<SettingsUiState> {
    override val values: Sequence<SettingsUiState> = sequenceOf(
        loggedOut,
        loggedIn,
        probeFailed,
        readerTweaked,
        scrollingReader,
    )

    companion object {
        /** Nobody signed in: the account section offers the login button instead. */
        val loggedOut = SettingsUiState(
            reader = ReaderSettings(),
            app = AppSettings(useDynamicColor = false, themeMode = ThemeMode.LIGHT),
            session = null,
            offlineBytes = 1_572_864L,
            imageCacheBytes = 4_823_040L,
        )

        /** The everyday state: a session, and a reader already tuned. */
        val loggedIn = SettingsUiState(
            reader = ReaderSettings(
                fontSizeSp = 20f,
                lineHeightMultiplier = 1.8f,
                paragraphSpacingDp = 16,
                horizontalPaddingDp = 24,
                theme = ReaderTheme.PAPER,
                volumeKeyPaging = true,
                keepScreenOn = true,
            ),
            app = AppSettings(useDynamicColor = false, themeMode = ThemeMode.SYSTEM),
            session = UserSession(
                userId = "123456",
                userName = "示例用户",
                groupName = "普通会员",
                honorName = "签到达人",
            ),
            offlineBytes = 268_435_456L,
            imageCacheBytes = 52_428_800L,
        )

        /** The browser-engine probe finished badly: the ✖ line in the 书源 section. */
        val probeFailed = loggedIn.copy(
            probeResult = "❌ 浏览器内核访问失败：Cloudflare 质询未通过，请先在「使用浏览器登录」中完成校验",
        )

        /** A reader whose bars follow their own colour instead of the page. */
        val readerTweaked = loggedIn.copy(
            reader = loggedIn.reader.copy(
                barFollowsTheme = false,
                barHue = 210f,
                barSaturation = 0.55f,
                barValue = 0.28f,
            ),
        )

        /**
         * The other 翻页方向, so the two-way choice can be compared in the panel without
         * flipping it in the app — which is what the drop-down exists for.
         */
        val scrollingReader = loggedIn.copy(
            reader = loggedIn.reader.copy(pageTurnMode = PageTurnMode.VERTICAL),
        )
    }
}
