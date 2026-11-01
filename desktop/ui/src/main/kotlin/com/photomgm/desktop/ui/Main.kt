package com.photomgm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment

/**
 * U0 冒烟入口：只验证「Compose Desktop 能在此机器上起窗、能渲染中文、能重组、能正常退出」。
 *
 * 这不是产品界面（三栏工作区在 U2~U4 实现）。它的价值是把版本矩阵、Skiko 原生库、
 * 中文字体、高 DPI 四个风险点用一次真实运行钉死，而不是靠读文档推断。
 *
 * 用法：
 *   ./gradlew :ui:run                      正常窗口（手动关闭）
 *   ./gradlew :ui:run --args="--smoke"     自动渲染 1.5s 后退出，供脚本断言
 */
fun main(args: Array<String>) {
    val smoke = args.contains("--smoke")

    println("[SMOKE] headless=${GraphicsEnvironment.isHeadless()}")
    runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val dev = ge.defaultScreenDevice
        println("[SMOKE] screen=${dev.displayMode.width}x${dev.displayMode.height} scale=${dev.defaultConfiguration.defaultTransform.scaleX}")
    }

    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "巡查照片分类 · Photo-Mgm 2.0.0",
        ) {
            var clicks by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { println("[SMOKE] composed") }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Photo-Mgm 桌面版", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "中文渲染检查：巡查日志 · 桩号 K138+700 · 台账照片 · 99_未分类",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { clicks++ }) { Text("重组测试（已点 $clicks 次）") }
                    }
                }
            }

            if (smoke) {
                LaunchedEffect(Unit) {
                    delay(1_500)
                    println("[SMOKE] window alive after 1.5s, closing")
                    exitApplication()
                }
            }
        }
    }
    println("[SMOKE] exited cleanly")
}
