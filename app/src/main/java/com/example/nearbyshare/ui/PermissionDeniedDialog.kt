package com.example.nearbyshare.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun PermissionDeniedDialog(message: String) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        title = { Text("缺少相关权限") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                (context as? Activity)?.finish()
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
                (context as? Activity)?.finish()
            }) {
                Text("前去授予")
            }
        }
    )
}
