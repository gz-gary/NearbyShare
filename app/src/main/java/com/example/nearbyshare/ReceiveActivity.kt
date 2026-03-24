package com.example.nearbyshare

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.example.nearbyshare.services.ServiceRegistry
import com.example.nearbyshare.services.TransferProgress
import com.example.nearbyshare.services.TransferService
import com.example.nearbyshare.services.WifiP2pService
import com.example.nearbyshare.ui.EndTransferButton
import com.example.nearbyshare.ui.TransferRecord
import com.example.nearbyshare.ui.TransferRecordList
import com.example.nearbyshare.ui.WifiP2pDisconnectedDialog
import com.example.nearbyshare.ui.theme.NearbyShareTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReceiveActivity : ComponentActivity() {
    companion object {
        const val TAG = "ReceiveActivity"
    }

    private sealed class DialogState {
        object Hidden : DialogState()
        data class Preparing(val stage: String = "") : DialogState()
        data class Receiving(val receivedBytes: Long, val totalBytes: Long, val speedBytesPerSec: Long) : DialogState()
        data class Done(val fileCount: Int, val totalBytes: Long, val elapsedMs: Long, val avgSpeedBytesPerSec: Long, val saveLocation: String) : DialogState()
        data class Error(val message: String) : DialogState()
    }

    private var dialogState by mutableStateOf<DialogState>(DialogState.Hidden)
    private var isWifiP2pConnected by mutableStateOf(true)
    private val transferRecords = mutableStateListOf<TransferRecord>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ServiceRegistry.getService(WifiP2pService::class.java).onDisconnected = {
            Log.d(TAG, "onDisconnected")
            isWifiP2pConnected = false
        }

        lifecycleScope.launch {
            ServiceRegistry.getService(TransferService::class.java).progressFlow.collectLatest { progress ->
                dialogState = when (progress) {
                    is TransferProgress.ReceivePreparing -> DialogState.Preparing(progress.stage)
                    is TransferProgress.ReceiveInProgress -> DialogState.Receiving(
                        progress.receivedBytes, progress.totalBytes, progress.speedBytesPerSec
                    )
                    is TransferProgress.ReceiveDone -> {
                        transferRecords.add(
                            TransferRecord(
                                fileCount = progress.fileCount,
                                totalBytes = progress.totalBytes,
                                avgSpeedBytesPerSec = progress.avgSpeedBytesPerSec,
                                endTime = System.currentTimeMillis()
                            )
                        )
                        DialogState.Done(
                            progress.fileCount, progress.totalBytes, progress.elapsedMs, progress.avgSpeedBytesPerSec, progress.saveLocation
                        )
                    }
                    is TransferProgress.ReceiveError -> DialogState.Error(progress.message)
                    else -> dialogState
                }
            }
        }

        setContent {
            NearbyShareTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("接收方") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                        contentDescription = "返回"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp)
                        ) {
                            if (transferRecords.isEmpty()) {
                                Text(
                                    text = "等待对方发送...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                TransferRecordList(
                                    records = transferRecords,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EndTransferButton()
                        }
                    }
                }

                if (!isWifiP2pConnected) {
                    WifiP2pDisconnectedDialog()
                }

                when (val state = dialogState) {
                    is DialogState.Preparing -> {
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                            confirmButton = {},
                            title = { Text("传输即将开始...") },
                            text = {
                                Text(state.stage.ifBlank { "正在等待对方发送..." })
                            }
                        )
                    }
                    is DialogState.Receiving -> {
                        val received = state.receivedBytes
                        val total = state.totalBytes
                        val speedMB = state.speedBytesPerSec / 1_048_576.0
                        val progress = if (total > 0) received.toFloat() / total else 0f
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                            confirmButton = {},
                            title = { Text("接收中") },
                            text = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("${formatBytes(received)} / ${formatBytes(total)}")
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text("速度：${"%.2f".format(speedMB)} MB/s")
                                }
                            }
                        )
                    }
                    is DialogState.Done -> {
                        val elapsedSec = state.elapsedMs / 1000.0
                        val avgMB = state.avgSpeedBytesPerSec / 1_048_576.0
                        AlertDialog(
                            onDismissRequest = { dialogState = DialogState.Hidden },
                            confirmButton = {
                                TextButton(onClick = { dialogState = DialogState.Hidden }) {
                                    Text("关闭")
                                }
                            },
                            title = { Text("接收完成") },
                            text = {
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("总大小：${formatBytes(state.totalBytes)}")
                                    Text("耗时：${"%.2f".format(elapsedSec)} 秒")
                                    Text("平均速度：${"%.2f".format(avgMB)} MB/s")
                                    Text("已保存至：${state.saveLocation}")
                                }
                            }
                        )
                    }
                    is DialogState.Error -> {
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                            confirmButton = {
                                TextButton(onClick = { finish() }) {
                                    Text("确认")
                                }
                            },
                            title = { Text("接收失败") },
                            text = {
                                Text(state.message)
                            }
                        )
                    }
                    DialogState.Hidden -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        ServiceRegistry.getService(WifiP2pService::class.java).removeGroupAsync()
        super.onDestroy()
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "${"%.2f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${"%.2f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1024 -> "${"%.2f".format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}