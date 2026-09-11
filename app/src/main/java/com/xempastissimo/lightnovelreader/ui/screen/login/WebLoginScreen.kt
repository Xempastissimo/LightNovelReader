package com.xempastissimo.lightnovelreader.ui.screen.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Urls
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer

/** Host part of a URL, used as the cookie jar domain. */
private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore('/')

/**
 * Interactive sign-in for sources protected by a browser challenge.
 *
 * The site answers plain HTTP clients with a Cloudflare challenge
 * (`cf-mitigated: challenge`). That is bot protection, and working around it in
 * code is out of scope — the user completes the check in a real browser engine
 * and the resulting session cookies are imported into the app's cookie jar.
 *
 * Two details matter for this to work at all:
 * - the WebView's stock user agent contains a `; wv)` token that Cloudflare
 *   rejects, so a normal mobile Chrome identity is used instead;
 * - the import button sits *below* the page, because the session only exists
 *   after the user has actually logged in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(Wenku8Urls.LOGIN) }
    var pageTitle by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("第 1 步：在下方页面完成登录与人机校验") }
    var statusIsError by remember { mutableStateOf(false) }
    var manualCookie by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    /** Copies every cookie the WebView holds for the source into the app's jar. */
    fun importCookies(): Int {
        val manager = CookieManager.getInstance()
        manager.flush()
        var imported = 0
        for (url in listOf(
            Wenku8Urls.BASE,
            "${Wenku8Urls.SCHEME}wenku8.net",
            "${Wenku8Urls.SCHEME}www.wenku8.com",
        )) {
            val header = manager.getCookie(url)
            if (header.isNullOrBlank()) continue
            container.cookieStore.importRawCookieHeader(header, hostOf(url))
            imported += header.split(';').count { it.contains('=') }
        }
        return imported
    }

    fun finishWithMessage(message: String, isError: Boolean) {
        status = message
        statusIsError = isError
        if (!isError) onDone()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("浏览器登录 / 人机校验") },
            navigationIcon = {
                IconButton(onClick = onDone) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新页面")
                }
            },
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (statusIsError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                if (pageTitle.isNotBlank()) {
                    Text(
                        text = "页面：$pageTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // The stock WebView UA carries a `; wv)` token that the site's
                    // bot protection rejects; a plain mobile Chrome identity works.
                    settings.userAgentString = MOBILE_CHROME_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            currentUrl = url ?: currentUrl
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            currentUrl = url ?: currentUrl
                            pageTitle = view?.title.orEmpty()
                            if (isChallengeTitle(pageTitle)) {
                                status = "第 1 步：站点正在校验浏览器，请等待自动完成（若长时间不动，点右上角刷新）"
                            } else if (url?.contains("login.php") == true) {
                                status = "第 1 步：请在这个页面里输入账号密码登录"
                            } else {
                                status = "第 1 步：已完成页面加载。登录成功后点最下方按钮导入会话"
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            // Keep everything inside the WebView so cookies stay here.
                            return false
                        }
                    }
                    loadUrl(Wenku8Urls.LOGIN)
                    webView = this
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "第 2 步：确认页面已经登录成功（能看到「退出登录」或用户名），再导入会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val count = importCookies()
                        val loggedIn = container.bookSource.isLoggedIn()
                        when {
                            loggedIn -> finishWithMessage("已导入 $count 项会话 Cookie，登录状态正常", false)
                            count > 0 -> finishWithMessage(
                                "已导入 $count 项 Cookie，但没有识别到登录态（缺少 jieqiUserInfo）。" +
                                    "说明页面里还没登录成功，请在下方页面完成登录后再试。",
                                true,
                            )

                            else -> finishWithMessage(
                                "没有读取到任何 Cookie。请先在下方页面完成登录，再点此按钮。",
                                true,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导入会话并返回")
                }
                OutlinedButton(
                    onClick = { showManual = !showManual },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (showManual) "收起手动输入" else "手动粘贴 Cookie")
                }
            }

            OutlinedButton(
                onClick = {
                    // Last resort: let the user sign in with their everyday browser,
                    // then paste that session back here.
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(Wenku8Urls.LOGIN),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }.onFailure {
                        finishWithMessage("没有可用的浏览器，请在其它设备登录后使用「手动粘贴 Cookie」", true)
                    }
                    showManual = true
                    status = "已在系统浏览器中打开登录页。登录成功后，从浏览器复制 " +
                        "www.wenku8.net 的 Cookie，粘贴到下面的输入框中。"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("在系统浏览器中打开登录页（登录后回来粘贴 Cookie）")
            }

            OutlinedButton(
                onClick = {
                    currentUrl = Wenku8Urls.BOOKCASE
                    status = "正在打开站点的「我的书架」页面，用于确认该页面在浏览器里是否可访问"
                    webView?.loadUrl(Wenku8Urls.BOOKCASE)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开站点的「我的书架」页面")
            }

            if (showManual) {
                Text(
                    text = "从已登录的浏览器复制 www.wenku8.net 的 Cookie（形如 PHPSESSID=…; jieqiUserInfo=…）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = manualCookie,
                    onValueChange = { manualCookie = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cookie") },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Button(
                    onClick = {
                        container.cookieStore.importRawCookieHeader(manualCookie)
                        if (container.bookSource.isLoggedIn()) {
                            finishWithMessage("已导入并识别到登录态", false)
                        } else {
                            finishWithMessage(
                                "已导入，但未识别到登录态；请确认 Cookie 里包含 jieqiUserInfo。",
                                true,
                            )
                        }
                    },
                    enabled = manualCookie.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("使用这段 Cookie")
                }
            }
        }
    }
}

/** Cloudflare's interstitial titles vary; match the common ones. */
private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return lower.contains("just a moment") ||
        lower.contains("attention required") ||
        lower.contains("checking your browser") ||
        title.contains("安全验证") ||
        title.contains("请稍候")
}

private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36"
