package com.fertitrack.app.vigilancia

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Vigilancia en segundo plano: la enciende el usuario y se recuerda entre aperturas. */
interface Vigilancia {
    val activa: StateFlow<Boolean>

    fun activar(activa: Boolean)

    /** Con la app a la vista: si estaba activa y el servicio no corre (p. ej. tras reiniciar el teléfono), lo arranca. */
    fun reanudarSiCorresponde()
}

/** Arranca y detiene [ServicioVigilancia]; la elección se guarda en las preferencias de la app. */
class VigilanciaAndroid(contexto: Context) : Vigilancia {
    private val app = contexto.applicationContext
    private val preferencias = app.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
    private val _activa = MutableStateFlow(preferencias.getBoolean(CLAVE_ACTIVA, false))
    override val activa: StateFlow<Boolean> = _activa.asStateFlow()

    override fun activar(activa: Boolean) {
        preferencias.edit { putBoolean(CLAVE_ACTIVA, activa) }
        _activa.value = activa
        if (activa) arrancar() else app.stopService(Intent(app, ServicioVigilancia::class.java))
    }

    override fun reanudarSiCorresponde() {
        if (_activa.value) arrancar()
    }

    private fun arrancar() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, ServicioVigilancia::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) si la app no está a la
            // vista: se reintenta la próxima vez que se abra.
        }
    }

    private companion object {
        const val PREFERENCIAS = "fertitrack"
        const val CLAVE_ACTIVA = "vigilancia_activa"
    }
}
