package com.xempastissimo.lightnovelreader.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.xempastissimo.lightnovelreader.data.network.HttpFailure

/**
 * Turns any failure into a message the UI can show.
 *
 * The network layer already distinguishes throttling, an expired session and a
 * browser challenge, so the screens never have to guess what went wrong.
 */
fun Throwable.toUserMessage(): String = when (this) {
    is HttpFailure.AuthRequired -> message ?: "需要登录后才能访问"
    is HttpFailure.Challenge -> "书源要求浏览器校验，请先在浏览器中完成验证后重试"
    is HttpFailure.Blocked -> message ?: "书源限流，请稍后再试"
    is HttpFailure.Status -> message ?: "书源返回异常状态"
    is HttpFailure.Network -> message ?: "网络不可用"
    else -> message ?: this::class.simpleName ?: "未知错误"
}

/**
 * Builds a view model from the [AppContainer] without a DI framework.
 *
 * The container lives on the [Application], which is reachable through
 * [CreationExtras.APPLICATION_KEY], so no view model needs a Context parameter.
 */
class AppViewModelFactory<T : ViewModel>(private val create: (AppContainer) -> T) :
    ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: CreationExtras): VM {
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("No Application in CreationExtras")
        val container = (application as? com.xempastissimo.lightnovelreader.App)?.container
            ?: error("The Application is not App; AppContainer is unavailable")
        return create(container) as VM
    }
}
