package com.zeni.voiceai

import android.app.Application

/**
 * Zeni Voice AI Application class.
 */
class ZeniApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        lateinit var instance: ZeniApplication
            private set
    }
}
