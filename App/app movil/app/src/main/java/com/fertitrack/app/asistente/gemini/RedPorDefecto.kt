package com.fertitrack.app.asistente.gemini

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * true si la red por defecto del teléfono tiene internet comprobado por Android. Es la red
 * por la que sale [ClienteGemini]. La del robot pedida con WifiNetworkSpecifier nunca es la
 * red por defecto; el AP unido a mano sin datos móviles sí, y no está validado.
 */
fun redPorDefectoConInternet(contexto: Context): Boolean {
    val conectividad = contexto.getSystemService(ConnectivityManager::class.java) ?: return true
    val capacidades = conectividad.getNetworkCapabilities(conectividad.activeNetwork) ?: return false
    return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
