package com.fertitrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fertitrack.app.ui.FertiTrackPantalla
import com.fertitrack.app.ui.MainViewModel
import com.fertitrack.app.ui.tema.FertiTrackTema

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FertiTrackTema {
                val estado by viewModel.ui.collectAsStateWithLifecycle()
                FertiTrackPantalla(estado = estado, acciones = viewModel)
            }
        }
    }

    /** El sondeo solo corre con la app visible (regla 6: nada de consultas en segundo plano). */
    override fun onStart() {
        super.onStart()
        viewModel.alPrimerPlano()
    }

    override fun onStop() {
        viewModel.alSegundoPlano()
        super.onStop()
    }
}
