package com.ppailab.agnesstudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ppailab.agnesstudio.ui.AgnesStudioApp
import com.ppailab.agnesstudio.ui.AppViewModel
import com.ppailab.agnesstudio.ui.theme.AgnesStudioTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
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

    override fun onStart() {
        super.onStart()
        // Only a visible Activity starts a new foreground service. Application
        // startup may have been caused by a background recovery worker.
        (application as AgnesStudioApplication).graph.generationQueue.startFromUser()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
