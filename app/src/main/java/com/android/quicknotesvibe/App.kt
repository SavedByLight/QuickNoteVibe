package com.android.quicknotesvibe

import android.app.Application

class App : Application() {
    companion object {
        lateinit var context: Application
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = this
    }
}
