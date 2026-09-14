# AGENTS.md

## 项目概述

单模块 Android 应用，使用 Kotlin 和 Jetpack Compose (Material3) 构建。
包名：`com.xempastissimo.lightnovelreader`

## 构建与运行

```bash
# Windows（主要开发环境）
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug        # 安装到已连接设备/模拟器
.\gradlew.bat test                # 本地单元测试（JVM）
.\gradlew.bat connectedAndroidTest # 设备测试（需要设备）
```

项目中没有 Unix 的 `./gradlew` 脚本，请使用 `.\gradlew.bat`。

## 关键版本

| 组件 | 版本 |
|------|------|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk | 36 |
| minSdk | 26 |
| JVM toolchain | 21 |

版本统一在 `gradle/libs.versions.toml` 中管理，不要在 `app/build.gradle.kts` 中硬编码版本。

## 项目结构

```
app/src/main/java/com/xempastissimo/lightnovelreader/
├── App.kt                   # Application，持有 AppContainer
├── MainActivity.kt          # 唯一 Activity：主题 + 注入 + NavHost
├── core/                    # 与站点无关的底层工具（HTML DOM、字符集、JSON）
├── data/network/            # HttpFetcher、CookieStore、RateLimiter
├── data/source/             # BookSource 接口 + wenku8/ 实现
├── data/repo/               # BookRepository、ShelfRepository、SettingsRepository、ImageLoader
├── domain/model/            # Book、Volume、Chapter、ChapterContent、ReadingProgress
└── ui/                      # AppContainer、导航、组件、主题、screen/*
```

完整架构、wenku8 选择器映射、抓取策略和已知限制请参考 `README.md`。

## 开发规范

- Kotlin 官方代码风格（`gradle.properties` 中 `kotlin.code.style=official`）
- Gradle 配置缓存已启用
- Compose 编译器插件通过 `kotlin.compose` 应用
- `MainActivity` 默认启用边到边显示
- Java 源码/目标兼容性：11
- 所有用户界面字符串为简体中文，与书源一致。
- 界面层不直接访问网络：通过 ViewModel 和 Repository 获取数据，消费 `StateFlow`。
- 添加新书源只需实现 `data/source/BookSource` 并修改 `AppContainer.bookSource`，无需修改任何界面代码。

## 数据存储

下载的小说保存在应用的**内部存储**目录 `filesDir/library/` 下：

```
filesDir/library/
├── shelf.json                          # 书架元数据 + 阅读进度
├── {bookId}/                           # 每本书一个子目录
│   ├── 1.json                          # 章节内容
│   ├── 2.json
│   └── ...
```

站点整本打包下载（详情页「下载全本（站点打包）」）另存一份原始 txt 与索引，**与上面的章节缓存分开**：

```
filesDir/packs/
└── {bookId}/
    ├── text.txt                        # 站点打包原文（UTF-8 或 GBK，逐字节保存）
    └── index.json                      # 元信息 + 目录 + 每章在 text.txt 里的字节区间
```

下载成功后同一批章节**也会**导入 `library/{bookId}/`，两个目录各自可在设置页「存储」清理。读取优先级是：逐章缓存 → 打包切片 → 网络（见 `BookRepository.content`）。

这些目录对其他应用不可见，包括文件管理器。只有本应用能读取这些文件。

## 注意事项

- `compileSdk` 使用非标准的 `release(36) { minorApiLevel = 1 }` 语法 — 修改前需了解 API 级别影响。
- Release 构建禁用了优化（`enable = false`）— 这是当前开发阶段的有意设计。
- 尚未配置 CI/CD 工作流。
- **不要添加未经验证的依赖**。Maven Central 和 dl.google.com 从本机无法访问，因此依赖集是刻意自包含的：使用 `HttpURLConnection` 替代 OkHttp，手写 DOM/CSS 选择器替代 Jsoup，手写 JSON 编解码替代 kotlinx.serialization，手写图片加载替代 Coil。
  `androidx.lifecycle` 2.11.0 需要 compileSdk 37，因此固定为 2.9.4。
- `org.json` 是 Android 框架的一部分，在 JVM 单元测试中是**未模拟的存根**；持久化代码使用 `core/json/Json.kt`，确保数据层可单元测试。
- 源站页面包含 `<input name="title">` 和多余的 `</br>` 标签，这两者会截断解析树；`core/html/Html.kt` 处理这些问题，`HtmlTest` 锁定了该行为。
- 绝不绕过源站的反爬机制。Cloudflare 质询应通过 WebView 登录流程由用户完成，而非伪造请求。
- 保持请求串行并限速（`RateLimiter`，最小间隔 900ms）。
- 刷新按钮统一走 `RefreshThrottle`（`data/network`，2 秒/次，窗口内静默丢弃，不加「刷新过快」提示）。**自动触发的读取不要走它**——首次加载、会话变化后的重取、错误页重试都要立即发出，否则一次点击会挡住真正需要的那次读取。
- 发现页的榜单按页签留在 `DiscoverViewModel` 的内存里（`DiscoverUiState.tabs`，每个页签一份 `DiscoverTabState`，`books == null` 表示本进程还没读过），**只在冷启动与手动刷新时取数**：切页签、从详情页返回、回到前台都不得新增请求（会话变化后修复失败态的重取是唯一允许的自动路径）。"要不要取数"的判断在 `DiscoverTabs.kt`（纯函数、有测试），不要绕过它去直接发请求。
- 列表点进详情页必须先交接这一行的 `Book`：走 `AppNavHost` 里的 `openBook`（内部 `BookRepository.rememberSummary`），详情页靠它画首帧的封面/标题/作者/文库。新增列表入口时要接上同一个 `openBook`，否则新页面又回到「整屏转圈」。该交接**只是提示、不是缓存**：`BookRepository.detail()` 仍然每次都读站点。
- 发现页的左右划动由 `HorizontalPager`（`DiscoverScreen.kt`，页签即页）提供：页面跟手、松手后回弹或落到相邻页签、一甩最多一格。取数仍只在**落定**时触发（`pagerState.settledPage` → `selectTab` → `DiscoverTabs.kt` 的纯函数），所以半途拖回不取数、已读过的页签也不取数。每个页签各画一份 `DiscoverTabState`，相邻页预组合；改动手势时同时要保证竖向滚动与卡片点击都不受影响。
- 阅读器的系统状态栏是**遮罩的一部分**：`showMenu`（顶底栏是否在）为真就 `show(WindowInsetsCompat.Type.statusBars())`，为假就 `hide(...)`——单点唤出顶底栏时状态栏一起出现，顶底栏收起（含 5 秒自动收起）时一起消失。只动状态栏，导航栏始终不动（连它一起隐藏会进入全沉浸模式）；退出时在 `onDispose` 里无条件恢复。
- 顶栏的 `windowInsets` 用 `statusBarsIgnoringVisibility` 的高度（`animateDpAsState` 过渡）：顶底栏在时给状态栏留出位置，收起时归零。别直接用 `WindowInsets.statusBars`——系统 inset 要几百毫秒后才反映显示/隐藏，跟着它布局会让标题先按一种高度进场、再跳 138px。
- 阅读设置来自 DataStore，是异步的：**在 `settingsLoaded` 之前不要用默认 `ReaderSettings()` 画任何东西**（阅读底色用应用自己的背景色顶替、顶栏底栏干脆不显示），否则深色模式下会先闪一版默认米黄纸 + 默认蓝栏。首次把真实主题落上去要**直接生效**（`key(settingsLoaded)` 重建动画状态），只有用户在设置面板里换主题才渐变。
- 每个界面（Screen）都拆成「有状态外壳 + 无状态 Content」两半：`SettingsScreen`（取 `ViewModel`）与 `SettingsContent(state, actions)`（纯渲染）。**拆分的目的就是预览**——`@Preview` 拿不到 `ViewModel`，只有无状态的那半能在 Android Studio 的 Preview 面板里渲染。
- `@Preview` 分两类放置：叶子组件（`SettingsCard`、`SwitchRow`、`BookCard`……）的预览留在原文件末尾；**整屏预览放在独立的 `*Previews.kt`**（如 `SettingsScreenPreviews.kt`），以免预览用的假数据混进业务代码。
- 用 `@PreviewParameter` 提供多状态（未登录 / 已登录 / 诊断失败……），面板顶部的下拉框即可切换，无需改代码；用 `uiMode = UI_MODE_NIGHT_YES` 提供夜间配色，并且**固定 `dynamicColor = false`**——动态取色取自用户壁纸，用它预览会导致每台机器颜色都不同，无法用来判断对比度。
- `PreviewParameterProvider` 及其 `values` 不要声明为 `private`：预览工具通过反射解析，私有会编译通过但面板里报找不到。

