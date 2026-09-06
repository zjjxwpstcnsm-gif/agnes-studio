package com.ppailab.agnesstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ppailab.agnesstudio.ui.AgnesStudioApp
import com.ppailab.agnesstudio.ui.AppViewModel
import com.ppailab.agnesstudio.ui.theme.AgnesStudioTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel> {
        AppViewModel.Factory((application as AgnesStudioApplication).graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.appSettings.collectAsStateWithLifecycle()
            AgnesStudioTheme(settings.darkMode) {
                AgnesStudioApp(viewModel)
            }
        }
    }
}
