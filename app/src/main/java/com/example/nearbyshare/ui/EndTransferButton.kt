package com.example.nearbyshare.ui

import android.app.Activity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun EndTransferButton() {
    val context = LocalContext.current
    OutlinedButton(
         onClick = { (context as? Activity)?.finish() },
         modifier = Modifier
             .fillMaxWidth()
             .navigationBarsPadding(),
         colors = ButtonDefaults.outlinedButtonColors(
             contentColor = MaterialTheme.colorScheme.error
         )
     ) {
         Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
             Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
             Spacer(modifier = Modifier.width(8.dp))
             Text("结束传输")
         }
     }
}
