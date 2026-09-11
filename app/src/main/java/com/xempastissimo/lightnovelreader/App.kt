package com.xempastissimo.lightnovelreader

import android.app.Application
import com.xempastissimo.lightnovelreader.ui.AppContainer

/**
 * Application entry point.
 *
 * Owns the single [AppContainer] (manual dependency injection). Screens and
 * view models obtain it from [com.xempastissimo.lightnovelreader.ui.LocalAppContainer].
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
