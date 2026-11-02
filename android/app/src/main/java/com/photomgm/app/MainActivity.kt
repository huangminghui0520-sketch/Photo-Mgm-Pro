// app/MainActivity.kt —— 入口：权限 + Scaffold + SnackbarHost + 导航
// ★ 2026-10-07 UI 重建：结构与原实现等价，重新书写。
package com.photomgm.app

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
                // ★ P0（2026-11-02）：媒体权限必须**连位置权限一起**申请。
                //   只申请 READ_MEDIA_IMAGES 时，Android 10+ 读取媒体会剥离 EXIF GPS（值区清零），
                //   表现为「照片本来就没有定位」——真机上 Photo.latitude 恒 null。
                //   清单随 SDK 版本变化（33/29 两个分水岭），交给纯函数 MediaPermissions 决定。
                val plan = remember { MediaPermissions.plan(Build.VERSION.SDK_INT) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted -> vm.onMediaPermissionsResult(granted, Build.VERSION.SDK_INT) }
                LaunchedEffect(Unit) { launcher.launch(plan.all.toTypedArray()) }

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
