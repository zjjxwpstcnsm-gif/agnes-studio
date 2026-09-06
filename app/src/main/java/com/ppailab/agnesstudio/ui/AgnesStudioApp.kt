package com.ppailab.agnesstudio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class MainTab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.Outlined.ChatBubbleOutline),
    IMAGE("图片", Icons.Outlined.Image),
    VIDEO("视频", Icons.Outlined.Movie),
    QUEUE("队列", Icons.Outlined.Queue),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
fun AgnesStudioApp(viewModel: AppViewModel) {
    var selectedTab by remember { mutableStateOf(MainTab.CHAT) }
    val snackbarHostState = remember { SnackbarHostState() }
    val keySaved by viewModel.keySaved.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val activeJobs = jobs.count { it.status !in setOf(
        com.ppailab.agnesstudio.model.JobStatus.SUCCEEDED,
        com.ppailab.agnesstudio.model.JobStatus.FAILED,
        com.ppailab.agnesstudio.model.JobStatus.CANCELLED,
    ) }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            snackbarHostState.showSnackbar(notice.message)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!keyboardVisible) {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Column {
                                    Icon(tab.icon, contentDescription = tab.label)
                                    if (tab == MainTab.QUEUE && activeJobs > 0) {
                                        Text(
                                            text = activeJobs.coerceAtMost(99).toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!keySaved && selectedTab != MainTab.SETTINGS) {
                MissingKeyBanner(onOpenSettings = { selectedTab = MainTab.SETTINGS })
            }
            Box(Modifier.fillMaxSize()) {
                when (selectedTab) {
                    MainTab.CHAT -> ChatScreen(viewModel)
                    MainTab.IMAGE -> ImageScreen(viewModel, onOpenQueue = { selectedTab = MainTab.QUEUE })
                    MainTab.VIDEO -> VideoScreen(viewModel, onOpenQueue = { selectedTab = MainTab.QUEUE })
                    MainTab.QUEUE -> QueueScreen(viewModel)
                    MainTab.SETTINGS -> SettingsScreen(viewModel)
                }
            }
        }
    }
}
