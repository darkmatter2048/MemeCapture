package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.model.AppTheme
import com.example.service.FloatingWindowService
import com.example.ui.components.EmojiDetailDialog
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ScreenCaptureManager
import com.example.viewmodel.EmojiViewModel
import com.example.viewmodel.MainTab
import java.lang.Exception

class MainActivity : ComponentActivity() {

    private val viewModel: EmojiViewModel by viewModels()
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    private val floatingBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Logic moved to Service for reliability
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                viewModel.startServiceExplicitly(this, result.resultCode, result.data)
            } else {
                Toast.makeText(this, "未授予截屏权限，无法开启服务", Toast.LENGTH_SHORT).show()
            }
        }

        // Register Floating Service Action BroadcastReceiver
        val filter = IntentFilter().apply {
            addAction(FloatingWindowService.ACTION_TRIGGER_CAPTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingBroadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingBroadcastReceiver, filter)
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            MyApplicationTheme(
                appTheme = state.settings.theme,
                colorTheme = state.settings.colorTheme
            ) {
                MainAppScreen(
                    viewModel = viewModel,
                    onRequestProjection = {
                        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动时及从系统设置返回后，重新检测悬浮窗权限并更新弹窗状态
        viewModel.refreshOverlayPermissionStatus(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenCaptureManager.stop()
        try {
            unregisterReceiver(floatingBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: EmojiViewModel,
    onRequestProjection: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Display toasts if triggered
    LaunchedEffect(state.messageToast) {
        state.messageToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToastMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.currentTab) {
                            MainTab.DASHBOARD -> "表情大盗"
                            MainTab.GALLERY -> "表情库"
                            MainTab.SETTINGS -> "设置"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            val isDark = when (state.settings.theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            NavigationBar(
                containerColor = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFFF8F0)
            ) {
                NavigationBarItem(
                    selected = state.currentTab == MainTab.DASHBOARD,
                    onClick = { viewModel.selectTab(MainTab.DASHBOARD) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "首页") },
                    label = { Text("控制台", fontSize = 11.sp) }
                )

                NavigationBarItem(
                    selected = state.currentTab == MainTab.GALLERY,
                    onClick = { viewModel.selectTab(MainTab.GALLERY) },
                    icon = { Icon(Icons.Default.GridView, contentDescription = "表情库") },
                    label = { Text("表情库 (${state.emojis.size})", fontSize = 11.sp) }
                )

                NavigationBarItem(
                    selected = state.currentTab == MainTab.SETTINGS,
                    onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置", fontSize = 11.sp) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state.currentTab) {
                MainTab.DASHBOARD -> HomeScreen(
                    state = state,
                    onToggleService = {
                        if (state.isServiceRunning) {
                            viewModel.toggleService(context) // Stop service
                        } else {
                            onRequestProjection() // Request permission first
                        }
                    }
                )
                MainTab.GALLERY -> GalleryScreen(
                    state = state,
                    onSelectEmoji = { viewModel.openDetail(it) },
                    onDeleteEmoji = { viewModel.deleteEmoji(it) },
                    onClearAll = { viewModel.clearAllEmojis() }
                )
                MainTab.SETTINGS -> SettingsScreen(
                    state = state,
                    onUpdateSettings = { viewModel.updateSettings(it) }
                )
            }

            // Detail View Modal Dialog
            state.selectedEmojiForDetail?.let { emoji ->
                EmojiDetailDialog(
                    emoji = emoji,
                    onDismiss = { viewModel.closeDetail() }
                )
            }

            // 悬浮窗权限引导弹窗
            if (state.showOverlayPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissOverlayPermissionDialog() },
                    title = { Text("需要悬浮窗权限") },
                    text = { Text("请前往系统设置授予“显示在其他应用上层”权限。") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.openOverlayPermissionSettings(context) }) {
                            Text("去授权")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissOverlayPermissionDialog() }) {
                            Text("稍后")
                        }
                    }
                )
            }
        }
    }
}
