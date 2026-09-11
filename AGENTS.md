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

这个目录对其他应用不可见，包括文件管理器。只有本应用能读取这些文件。

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

