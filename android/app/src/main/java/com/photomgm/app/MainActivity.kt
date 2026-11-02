// app/MainActivity.kt —— 入口：权限 + Scaffold + SnackbarHost + 导航
// ★ 2026-10-07 UI 重建：结构与原实现等价，重新书写。
package com.photomgm.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photomgm.app.theme.PhotoMgmTheme
import com.photomgm.app.ui.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoMgmTheme {
                val vm: AppViewModel = viewModel()
                // 媒体读取权限：API33+ 用 READ_MEDIA_IMAGES，旧版用 READ_EXTERNAL_STORAGE
                val mediaPerm = if (Build.VERSION.SDK_INT >= 33)
                    Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) { launcher.launch(mediaPerm) }

                val snackbarHostState = remember { SnackbarHostState() }
                val state by vm.state.collectAsState()

                // ★ HCI：message → Snackbar 统一反馈入口（核心 UiState.message 不变，仅消费展示）
                state.message?.let { msg ->
                    LaunchedEffect(msg) {
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { inner ->
                    AppNav(vm, Modifier.padding(inner))
                }
            }
        }
    }
}
