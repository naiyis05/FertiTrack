package com.fertitrack.app

import android.app.Application
import com.fertitrack.app.di.AppContainer

class FertiTrackApp : Application() {
    lateinit var contenedor: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = AppContainer(this)
    }
}
