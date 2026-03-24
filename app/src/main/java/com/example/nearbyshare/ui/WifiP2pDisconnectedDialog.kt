package com.example.nearbyshare.ui

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties

@Composable
fun WifiP2pDisconnectedDialog() {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {
            TextButton(onClick = { (context as? Activity)?.finish() }) {
                Text("确认")
            }
        },
        title = { Text("连接已断开") },
        text = {
            Text("与对方的连接已断开，可能的原因如下：\n- 对方手动结束了传输\n- 对方手动关闭了Wi-Fi\n- 你的设备与对方设备距离太远\n请检查后尝试重新连接。")
        }
    )
}