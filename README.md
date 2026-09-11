# Light Novel Reader

一个源于Wenku8轻小说网站的 Android 端轻小说阅读器。

**整体框架**：书源可插拔、UI 已成型、可跑通「登录 → 找书 → 看目录 → 阅读 → 记进度 → 离线缓存」的完整链路。

书源是[轻小说文库](https://www.wenku8.net/)，实现细节集中在一个抽换点，接第二个源不需要改任何界面代码。

**当前版本**：`v0.1-alpha`（首个预发布版）。GitHub Release 用标签 `v0.1-alpha` 发布，并勾选 **Pre-release**；应用内 `versionName = "0.1-alpha"`、`versionCode = 1`。

## 目录

- [1. 做这个项目的灵感](#1-做这个项目的灵感)
- [2. 快速开始](#2-快速开始)
- [3. 目录结构](#3-目录结构)
- [4. 分层与数据流](#4-分层与数据流)
- [5. 书源映射（wenku8）](#5-书源映射wenku8)
- [6. 抓取策略与边界](#6-抓取策略与边界)
- [7. 测试](#7-测试)
- [8. 已知限制（有意为之）](#8-已知限制有意为之)
- [9. 验证记录](#9-验证记录)
- [10. 许可与使用边界](#10-许可与使用边界)

---

## 1. 做这个项目的灵感

逛了下Github，感觉轻小说文库没几个好用的安卓端app，于是我斥点小资用AI（主要用的Deepseek v4.1 Flash，最新出的这个模型确实快）做一个类似的app，至少基本功能都有，不过有些功能还在开发中，欢迎在issue反馈或者提交PR，如果有可行的方案我会尝试。

---

## 2. 快速开始

```bash
.\gradlew.bat assembleDebug          # 构建 debug APK
.\gradlew.bat installDebug           # 安装到已连接设备/模拟器
.\gradlew.bat test                   # JVM 单元测试（94 个）
.\gradlew.bat connectedAndroidTest   # 设备上的存储/持久化测试（6 个）
```

| 组件 | 版本 |
|---|---|
| 应用自身（`versionName`） | 0.1-alpha（GitHub 标签 `v0.1-alpha`，预发布） |
| Gradle / AGP | 9.4.1 / 9.2.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk / minSdk | 36.1 / 26 |
| JVM toolchain | 21 |

依赖版本统一在 `gradle/libs.versions.toml`，不要在 `app/build.gradle.kts` 里写死；应用自身的版本号是例外，写在 `app/build.gradle.kts` 的 `defaultConfig`（当前 `0.1-alpha`）。

---

## 3. 目录结构

```
app/src/main/java/com/xempastissimo/lightnovelreader/
├─ App.kt                          Application，持有 AppContainer
├─ MainActivity.kt                 唯一 Activity：主题 + 注入 + NavHost
│
├─ core/                           与被抓站点无关的底层工具
│  ├─ html/Html.kt                 自研极简 DOM（容错分词 + 实体解码）
│  ├─ html/CssSelector.kt          选择器子集引擎
│  ├─ text/CharsetCodec.kt         charset 探测与解码（GBK 兜底）
│  ├─ text/TextCleaner.kt          文本清洗（单行化 / 分段）
│  └─ json/Json.kt                 自研 JSON 编解码（用于本地持久化）
│
├─ data/
│  ├─ network/HttpFetcher.kt       HttpURLConnection 封装：编码、Cookie、重试
│  ├─ network/CookieStore.kt       持久化 cookie jar（JSON + 锁）
│  ├─ network/RateLimiter.kt       串行限流 + 指数退避
│  ├─ source/BookSource.kt         书源接口（榜单/目录/详情/正文/在线书架；`bookSummary` 只取详情页元信息）
│  ├─ source/wenku8/               首个书源的全部实现
│  │  ├─ Wenku8Urls.kt             URL 模板 / 解析工具
│  │  ├─ Wenku8Selectors.kt        选择器回退链（改版只改这里）
│  │  ├─ Wenku8Parser.kt           HTML → 领域模型
│  │  └─ Wenku8Source.kt           BookSource 实现
│  └─ repo/                        对界面暴露的仓库层
│     ├─ BookRepository.kt         详情缓存 + 章节离线读写 + 全本下载
│     ├─ ShelfRepository.kt        本地书架 / 阅读进度 / 搜索历史 / 元信息合并
│     ├─ SettingsRepository.kt     DataStore 偏好设置
│     └─ ImageLoader.kt            自研图片加载（内存 LRU + 磁盘 + Referer）
│
├─ domain/model/Models.kt          Book / Volume / Chapter / ChapterContent / ReadingProgress
│
└─ ui/
   ├─ AppContainer.kt              手写 DI 根 + CompositionLocal
   ├─ ViewModelSupport.kt          ViewModel 工厂 + 错误 → 文案
   ├─ navigation/AppNavHost.kt     路由表 + 底部导航
   ├─ component/                   CoverImage / BookCard / BookRow / ShelfRow / 空态
   ├─ theme/                       纸张配色 + 阅读器专用配色
   └─ screen/
      ├─ discover/                 榜单与最近更新
      ├─ search/                   标题/作者搜索 + 历史
      ├─ detail/                   封面、元信息、卷章目录、缓存全本
      ├─ shelf/                    继续阅读（本地阅读记录）/ 书架（**只显示站点账号里的收藏**）/ 已缓存（本机已下载的章节）
      ├─ settings/                 账号、阅读外观（含顶底栏配色、音量键翻页与反转）、深色模式、存储、书源说明
      ├─ login/                    原生表单 + 浏览器登录（人机校验）+ 粘贴 Cookie
      └─ reader/                   翻页阅读器 + 目录 + 阅读设置面板 + 章节切换横幅提示
```

---

## 4. 分层与数据流

```
Compose Screen ──> ViewModel ──> Repository ──> BookSource(Wenku8) ──> HttpFetcher
                                     │                                     │
                                     ├─ ChapterCache（离线章节）            ├─ CookieStore
                                     ├─ ShelfRepository（书架/进度）        └─ RateLimiter
                                     └─ ImageLoader（封面/插图）
```

- **UI 不直接联网**：所有网络与缓存都经过仓库层，界面只消费 `StateFlow`。
- **`BookSource` 是唯一抽换点**：`AppContainer.bookSource` 换成别的实现即可接新站。
- **错误是显式类型**：`HttpFailure` 区分限流、会话失效、人机校验、网络故障，UI 直接给出对应文案。

---

## 5. 书源映射（wenku8）

下表是**在真实站点上核实过**的页面结构（登录后逐页用浏览器 DOM 探查），改版时按它对照 `Wenku8Selectors.kt`。

### 5.1 URL

| 用途 | 形式 |
|---|---|
| 详情页 | `/book/{aid}.htm` |
| 目录页 | `/novel/{cat}/{aid}/index.htm`（详情页「阅读 → 小说目录」按钮的链接） |
| 章节页 | `/novel/{cat}/{aid}/{cid}.htm` |
| 榜单 | `/modules/article/toplist.php?sort={allvisit,monthvisit,dayvisit,anime,postdate,goodnum,lastupdate}` |
| 完结目录 | `/modules/article/articlelist.php?fullflag=1` |
| 搜索 | `/modules/article/search.php?searchtype={articlename,author}&searchkey={GBK 编码}` | 与榜单同一套条目结构（结果行自带封面），因此走同一个解析器 |
| 在线书架 | `/modules/article/bookcase.php` |
| 书架分组 | `?classid=1` … `?classid=5`（连同默认组共 6 组）。**app 暂不呈现分组**，只读写默认组；页头的总数涵盖全部分组，所以计数是全账号的，只有列表是默认组的 |
| **移出一本** | `/modules/article/bookcase.php?delid={shelfId}` —— 页面每行的「移除」就是这个地址（`document.location` 写在 `javascript:` href 里）。**`shelfId` 不是书籍 id**，见 4.4「两个 id」 |
| **批量移出** | 提交页面自带的 `<form action="" method="post" id="checkform">`：`checkid[]`（每个勾选项的 `shelfId` 各出现一次）+ `newclassid=-1`（`-1`＝移出书架，`0`～`5`＝移到分组）+ 表单隐藏字段 `clsssid` + 提交按钮 `btnsubmit`。字段名全部从页面读出，不写死 |
| 加入书架 | `/modules/article/addbookcase.php?bid={aid}` —— 这里的 `bid` **就是书籍 id**，与移出时的同名字段含义不同；先取页面自身的 `a[href*=addbookcase]`，取不到才退回该地址 |
| 登录 | `/login.php?do=submit&jumpurl=...` |
| 书架上限 | 页头写着「您的书架可收藏 300 本」，由站点强制；`Wenku8Source.MAX_BOOKCASE_BOOKS` 只是页头读不到时的兜底 |

### 4.2 选择器

| 数据 | 定位方式 | 备注 |
|---|---|---|
| 正文容器 | `div#content` | 章节页与详情页共用 |
| **书籍目录链接** | 详情页「阅读」fieldset 内 `a[href$=index.htm]`，形如 `/novel/3/3988/index.htm` | **这是 `cat` 段的权威来源**：`cat` 只出现在 `/novel/{cat}/{aid}/…` 里，不能从 `/book/{aid}.htm` 推出 |
| 目录表 | `div#content table.css` | 卷标题在 `td.vcss`，章节在 `td.ccss > a` |
| 正文排版 | 裸文本 + `<br>` | **正文不在元素里**：`#content` 的直接文本节点用 `<br>` 分段 |
| 章节内插图 | `#content img[src]` | 插图章节单独成页 |
| 封面 | 详情页 `img[src*=image]`；无图时按分类段推导 | 形如 `http://img.wenku8.com/image/{cat}/{aid}/{aid}s.jpg`。**中段是书籍 id 本身**（不是 `aid/1000`），`cat` 只能取自 `/novel/{cat}/{aid}/…`（见上一行）；已在真站核实：`image/3/3988/3988s.jpg` 为 200，`image/3/3/3988s.jpg` 为 404 |
| 最近更新封面 | 首页「最近更新」行**不含 `<img>`** | 行内只有 `[文库] 《书名》 最新章节`（`/book/{aid}.htm` + `/novel/{cat}/{aid}/{cid}.htm`），因此封面由最新章节链接里的 `cat` 推导；若站点日后补上图片，`img[src*=image]` 优先 |
| 标签 | `span.hottext b` 中 `作品Tags：` 之后 | |
| 简介 | `内容简介：` 标签**紧邻的下一个兄弟块** | 标签自身只是占位 span，值是相邻的裸文本 |
| 最近章节 | `最近章节：` 标签的下一个兄弟块 | |
| 作者/文库/状态/更新 | 按 `小说作者：`、`文库分类：`、`文章状态：`、`最后更新：` 标签定位所在 `td` | 与简介**不在同一张表** |
| 榜单条目 | `a[href*=addbookcase]` 向上找含 `img` 的容器 | 榜单整页只有一个 `table.grid`，条目是其中的 `div`。**每个条目对同一本书有两个链接**：先是一个只包着封面的空文本链接，再是带书名的链接 —— 取第一个会把条目当成无标题而整条跳过（这就是搜索/榜单曾经只剩标题、没有封面的原因），所以按「有文本且不是 `我要阅读` 之类的动作词」来选书名链接 |
| 在线书架 | `a[href*=readbookcase]`（`?aid=&bid=[&cid=]`） | `cid` 出现时该链接是最新章节 |
| 书架条目行 | 同时含 `input[name^=checkid]` 与 `a[href*=readbookcase]` 的**最近祖先**（即 `<tr>`） | 两个条件缺一不可：只看 `readbookcase` 会停在标题所在的 `<td>`（**里面没有作者**，这就是书架作者一直为空的原因），只看复选框会停在复选框自己的 `<td>` |
| 书架页头 | `div.gridtop` 的文本 | `您的书架可收藏 300 本，已收藏 3 本，本组有 3 本。` —— 真实总数只在这里 |
| 书架批量操作 | `select[name=newclassid]` 所在 `<form>` | `checkid[]`＝各行复选框的 `value`，`newclassid=-1` 表示移出书架 |
| 分页 | `div.pages` | |

#### 在线书架没有封面（书架行的元信息要合并，不能覆盖）

`bookcase.php` 是一张 名称 / 作者 / 最新章节 / 书签 / 更新 / 操作 的表格，**行内没有 `<img>`**，也没有指向书籍自己页面的链接，所以 `parseBookcase` 只能给出书名、作者和最新章节：`coverUrl` 为 null、`文库分类`/`文章状态`/`最后更新` 全空。

后果有两条，都已修：

- **同步不能整行覆盖**。`ShelfRepository.replaceOnlineEntries` 早先用站点那一行直接替换本地行，于是「打开过详情页、已经有封面」的书会在下一次同步时丢掉封面、文库和日期。现在改为 `book.mergeInto(existing)`：站点有值的字段以站点为准（书名、最新章节），站点没提的字段保留本地已有的。
- **没打开过的书要补一次详情页**。`ShelfViewModel.backfillMissingMetadata` 在一次成功的书架同步之后，对「既没有封面、也没有文库分类和更新日期」的行各读一次 `BookSource.bookSummary`（只取 `/book/{aid}.htm`，**不读目录**），把结果落盘。它串行、受 `RateLimiter` 限速、每次最多 8 本、遇到第一个失败就停下（质询或掉登录会让后面每一本都以同样方式失败），并且会跳过本次会话里已经问过的书。
  判定条件是「详情页能补的字段**全都没有**」，而不是「没有封面」——站点确实有书没封面，只看封面会永远重试。

#### 两个 id（踩过两次的坑）

同一本书在站上有**两个不同的数字**，混用会让请求「看起来成功、其实什么都没发生」：

| 出现位置 | 名字 | 例 |
|---|---|---|
| `/book/{aid}.htm`、`/novel/{cat}/{aid}/{cid}.htm`、`addbookcase.php?bid={aid}` | **书籍 id** | `3988` |
| `readbookcase.php?aid={aid}&bid={bid}` 的 `bid`、行复选框的 `value`、`bookcase.php?delid={bid}` | **书架 id** | `13066825` |

app 内部一律用书籍 id（`Book.bookId`）。移出书架只接受**书架 id**，所以必须先用 `Wenku8Parser.parseBookcaseRowIds` 从页面读出配对，不能拿书籍 id 去拼 URL。

### 4.3 登录

- 表单字段：`username`、`password`、`usecookie`、`action=login`；提交到 `/login.php?do=submit`。
- **`usecookie` 的值是秒数**（`0` / `86400` / `2592000` / `315360000`），不是序号 —— 这点已在真站核实。
- 中文用户名/搜索词必须**按 GBK 编码**后再 URL 编码，否则服务端查不到（`Wenku8Urls.search` 已处理）。
- 站点会话 Cookie：`PHPSESSID`、`jieqiUserInfo`（含用户名/等级，`jieqiUserInfo` 存在即视为已登录）、`jieqiVisitInfo`。

---

## 6. 抓取策略与边界

- **串行 + 限速**：所有请求经过一个 `RateLimiter`，最小间隔 900ms，`429/403/5xx` 指数退避后重试，最多 3 次。
- **批量下载是串行的**：`BookRepository.downloadBook` 逐章下载并回报进度，不做并发抓取。
- **不做风控绕过**：站点对普通 HTTP 客户端返回 Cloudflare 质询（响应头 `cf-mitigated: challenge`）。
  应用**不会**伪造指纹或绕过校验，而是提供三条正规路径：
  1. 「使用浏览器登录」——在 WebView 中由用户本人完成人机校验与登录，再把会话 Cookie 导入应用；
     > 注意：WebView 默认 UA 含 `; wv)` 标记，会被站点的机器人防护直接拒绝并**永久转圈**。
     > 因此这里显式设置了普通 Chrome 的 UA（兼容性配置，非绕过手段）。
  2. 「手动粘贴 Cookie」——从已登录的浏览器复制 `PHPSESSID`/`jieqiUserInfo` 等；
  3. 「在系统浏览器中打开登录页」——登录后回到应用粘贴 Cookie，用于 WebView 被拒的场景。

### 6.1 两条传输通道与「浏览器内核」取数

本机与真机都实测到：**`HttpURLConnection` 一律 403，而应用自带的 WebView 内核能拿到页面**
（真机实测 `chars≈32827, signedIn=true, challengePage=false`）。现代 Cloudflare 校验的是
TLS/HTTP2 请求指纹，所以有效 Cookie 也不够。

因此网络层有两条通道：

| 通道 | 实现 | 用途 |
|---|---|---|
| 原生 | `HttpUrlConnectionFetcher` | 表单 POST（登录）、图片等二进制下载；快 |
| 浏览器内核 | `BrowserPageFetcher` + `BrowserBackedFetcher` | **所有 HTML 页面**（榜单、详情、目录、正文）；慢（每次新建并销毁一个 WebView），且需要应用在前台 |

`BrowserBackedFetcher` 会在拿到质询页（`<title>` 为 `Just a moment...`）时重试一次；仍被质询就
抛出 `HttpFailure.Challenge`，界面会引导用户去「浏览器登录」完成一次校验——校验通过后浏览器会话
有效，应用即可正常取数。

> 判定质询**不能靠体积**：真实质询页有 ~28KB，必须靠 `<title>` 或 `cf_chl_opt`/`challenge-platform`
> 特征（`BrowserBackedFetcher.isChallengePage`），否则会把质询页当正文解析，界面显示成空列表。
- **登录后回到「发现」页会自动重取**：`DiscoverViewModel` 记住上次取数时的会话状态，`ON_RESUME` 时**只在会话发生变化**（或上次因未登录而失败）才重新请求，因此登录 → 返回即可看到榜单与封面，普通切页不会多打请求。
- **内容边界**：离线缓存只供个人在已有访问权限内阅读，**不做导出/分享/镜像**功能，请在设置页保持知情。
- **本地会话文件的敏感性**：从 WebView 导入的 `jieqiUserInfo` 按站点设计**包含一个口令哈希**（`jieqiUserPassword=…`），会随其他 Cookie 一起写入应用私有的 `files/session/cookies.json`。它不会离开设备，但请在共享设备上用完「设置 → 账号 → 退出登录」清理。
- 图片走 `http://img.wenku8.com`（源站只提供 HTTP），因此 `network_security_config.xml` 只对该域名放开明文流量，其余仍强制 HTTPS。

---

## 7. 测试

### JVM 单元测试（`app/src/test`，139 个）

| 文件 | 覆盖 |
|---|---|
| `core/html/HtmlTest` | DOM 分词、选择器（含 `[href*=…]`）、实体解码、容错、`</br>` 不得截断文档树 |
| `core/json/JsonTest` | 编解码往返、嵌套、数组、转义、损坏输入返回 null |
| `core/text/CharsetCodecTest` | GBK 探测与解码、meta charset、段落清洗 |
| `data/network/CookieStoreTest` | domain/path/过期/`Max-Age`、持久化、原始 Cookie 导入、HTTP 日期解析 |
| `data/network/RateLimiterTest` | 限流间隔、退避序列、重试判定（假时钟） |
| `data/source/wenku8/Wenku8ParserTest` | 详情元信息、卷章目录、正文分块、插图与导航、登录墙识别、榜单/搜索条目（含封面取哪张）、书架、最近更新解析、封面推导、URL 工具 |
| `data/source/wenku8/Wenku8SourceEndToEndTest` | 假 HTTP 层下的完整取数：书架总数与批量移除表单、详情→目录→正文、`bookSummary` 只读详情页而不读目录 |
| `data/repo/ShelfRepositoryMergeTest` | 站点书架镜像与本地阅读记录的合并、增删与重载；同步**保留**站点页面没带的封面/文库/日期，`updateBook` 只补元信息、不动进度与缓存 |
| `data/repo/ChapterCacheOfflineBooksTest` | 「已缓存」的书单来自磁盘：章节数与占用、`.json.tmp` 与空文件不算数、非书籍 id 目录忽略 |
| `ui/screen/reader/ChapterTurnTest` | 越界滑动是否翻章、翻章方向的判定、章节切换分类逻辑 |
| `ui/screen/reader/VolumeKeyPageStepTest` | 音量键翻页的默认方向与「反转音量翻页」的互换、其他按键不参与 |

解析类测试使用**结构等价、文案中性**的 HTML 片段（与真实页面的标签/类名/嵌套一致），不保存站点正文。

### 设备测试（`app/src/androidTest`，8 个）

`StorageInstrumentedTest` 在真实 Android 运行时里验证依赖框架的部分：章节缓存读写往返、书架与进度持久化、**损坏文件降级为空库**、在线/本地条目合并、JSON 编解码。

> 这组测试确实抓到过一个只在设备上暴露的缺陷：`Json.Arr` 不是 `List`，导致 `strings()` 永远返回空列表。修正见 `core/json/Json.kt`。

---

## 8. 已知限制（有意为之）

| 限制 | 说明与后续方向 |
|---|---|
| **分页按字符预算** | 阅读器用「每页约 520 字」估算，而非真实文本测量。好处是确定性、不依赖布局回调、重新分页后阅读位置稳定。后续可换成 `TextMeasurer` 真实分页，只需改 `ReaderViewModel.paginate`。 |
| **模拟器上无法直连书源** | Cloudflare 会对模拟器出口 IP 直接发质询（`Cf-Mitigated: challenge`）。应用已改为**通过自带的浏览器内核取页面**（见 §5.1），但**全新会话仍需在 WebView 里人工完成一次校验**：质询页的 JS 需要真实浏览器环境执行，冷启动的 WebView 会停在 `Just a moment...`。请先走一次「设置 → 账号 → 使用浏览器登录」，之后应用即可正常取数。真机同理。 |
| **浏览器内核通道的限制** | 每次取页面都会新建并销毁一个 WebView，比原生通道慢得多；且需要应用处于前台（后台时页面读取会明确失败）。图片与表单提交仍走原生通道。 |
| **作者/榜单元信息不完整** | 榜单条目里的作者是可选字段，站点部分板块不提供。 |
| **繁体版未接入** | 站点支持 `?charset=big5`，URL 层已预留，未做界面开关。 |
| **无 WorkManager 后台下载** | 缓存全本在进程内串行执行，退到后台可能被系统暂停；后续可迁到前台服务/WorkManager（需新增依赖）。 |
| **离线打开仍要取一次目录** | 章节正文优先读本机缓存（`ChapterCache`，见 `BookRepository.content`），但**卷章目录只来自站点**：`BookRepository.detail` 是内存缓存、不落盘，所以书架「已缓存」里的书在完全离线时仍然打不开。后续可把 `BookDetail` 一并写进 `library/{bookId}/`，与章节同一套 JSON 编解码。 |
| **「已缓存」按磁盘目录统计** | 书单来自 `ChapterCache.offlineBooks()`（`library/{bookId}/*.json`），而不是书架行里的 `cachedChapterIds` 记录 —— 后者只在「缓存全本」完成时写入，在线阅读缓存的章节不会记进去。两者不一致时以磁盘为准。 |
| **无书源插件化** | 目前靠 `BookSource` 接口 + 手写实现；脚本化书源（规则引擎）刻意没做。 |
| **依赖全为本地缓存** | 本机当时无法访问 Maven Central，因此没有引入 okhttp/jsoup/coil/room；已有实现全部自研或使用框架 API。若网络可达，可按需替换（见下）。 |

### 如果后续想替换自研组件

| 自研 | 可替换为 | 影响面 |
|---|---|---|
| `core/html` + `core/html/CssSelector` | Jsoup | 只改 `Wenku8Parser` |
| `data/network/HttpFetcher` | OkHttp | 只改 `AppContainer` |
| `data/repo/ImageLoader` | Coil | `CoverImage` / `IllustrationPage` |
| `core/json` | kotlinx.serialization | `ShelfRepository` / `ChapterCache` |
| `ShelfRepository` 的 JSON 文件 | Room | 需加 KSP 插件 |

---

## 9. 验证记录

在一台 `Small_Phone`（API 36, x86_64）模拟器上：

- `assembleDebug` 通过；APK 安装后**冷启动无异常**（`logcat` 无 `FATAL`）。
- 底部四个页签（发现/书架/搜索/设置）均可正常进入并渲染：发现页显示榜单标签与状态、书架页显示「继续阅读/书架」、搜索页显示输入与历史、设置页显示账号/阅读外观/外观/存储/书源各分区。
- 未登录时访问需鉴权的接口，界面显示明确文案与「去登录」入口，而不是空白或崩溃。
- 设备端 8 个 instrumented 测试、JVM 91 个单元测试全部通过。

封面路径在真站上复核过（用真实 Chrome 登录后读取首页与榜单 DOM，再用应用自身的 UA/Referer 请求图片）：

- 首页「最近更新」行的结构是 `[文库] 《<a href="/book/{aid}.htm">书名</a>》 <a href="/novel/{cat}/{aid}/{cid}.htm">最新章节</a>`，**行内没有 `<img>`**；列表右侧的封面来自其他板块，形如 `http://img.wenku8.com/image/4/4265/4265s.jpg`。
- 依此推导出的封面全部返回 `200 image/jpeg`：`image/3/3475`、`image/4/4365`、`image/4/4344`、`image/4/4364`、`image/4/4265`、`image/3/3988`、`image/4/4363`、`image/3/3744`（与「发现 → 最近更新」首屏条目一一对应）。
- 反例：把中段当成 `aid/1000` 的旧写法 `image/3/3/3988s.jpg` 返回 `404`，所以旧代码里推导出来的封面永远只能看到占位字符。
- 榜单/完结目录/搜索页的条目容器里**自带** `<img src="…/image/{cat}/{aid}/{aid}s.jpg">`（`a[href*=addbookcase]` 向上找含 `img` 的 `div`）。
- 这些条目的第一个 `/book/` 链接**只包封面、没有文本**，第二个才带书名（容器文本里另有 `作者:…`）。旧代码取第一个链接后判定「标题为空」而跳过整条，于是 `parseRankingList` 一直返回空列表，界面静默退化成 `parseBookList`：只剩标题、没有封面也没有作者 —— 搜索页与榜单页都受影响，现按「有文本的书名链接」选取。
- 搜索结果页**没有任何 `/novel/…` 链接**，所以搜索结果的封面只能来自条目里的 `<img>`，不能像「最近更新」那样靠分类段推导。
- 以「重来」为例的搜索结果（4 条）与推导结果一致：`image/4/4151`、`image/3/3219`、`image/2/2951`、`image/2/2736`，与截图中的四本书一一对应。

---

## 10. 许可与使用边界

本项目是**阅读器框架**，不包含也不分发任何小说正文。本项目与[轻小说文库](https://www.wenku8.net/)的所有者无关，请遵守该站点的服务条款与当地法律，仅作个人阅读使用，请不要利用此项目进行任何有违道德或法律的行为！！！
