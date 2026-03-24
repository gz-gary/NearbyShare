package com.example.nearbyshare

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.nearbyshare.services.StorageService
import com.example.nearbyshare.services.TransferProgress
import com.example.nearbyshare.services.TransferService
import com.example.nearbyshare.services.WifiP2pService
import com.example.nearbyshare.ui.ColorCircle
import com.example.nearbyshare.ui.UserBadge
import com.example.nearbyshare.ui.EndTransferButton
import com.example.nearbyshare.ui.TransferRecord
import com.example.nearbyshare.ui.TransferRecordList
import com.example.nearbyshare.ui.WifiP2pDisconnectedDialog
import com.example.nearbyshare.ui.theme.NearbyShareTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SendActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SendActivity"
    }

    private var groupOwnerAddress: String? = null
    private var remotePort: Int = 0
    private var remoteName: String? = null
    private var remoteColor: Int? = null

    private lateinit var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var pickFiles: ActivityResultLauncher<Array<String>>

    private val selectedFilesCallback = ActivityResultCallback<List<Uri>> { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "takePersistableUriPermission failed for $uri")
                }
            }
            Log.d(TAG, "selectedFilesCallback uris.size=${uris.size}")
            Toast.makeText(this, "选择了 ${uris.size} 个文件，正在发送…", Toast.LENGTH_SHORT).show()
            sendFiles(uris)
        }
    }

    private val storageService by lazy {
        ServiceRegistry.getService(StorageService::class.java)
    }

    private sealed class DialogState {
        object Hidden : DialogState()
        data class Preparing(val stage: String = "") : DialogState()
        data class Sending(val sentBytes: Long, val totalBytes: Long, val speedBytesPerSec: Long) : DialogState()
        data class Done(val fileCount: Int, val totalBytes: Long, val elapsedMs: Long, val avgSpeedBytesPerSec: Long) : DialogState()
        data class Error(val message: String) : DialogState()
    }

    private var dialogState by mutableStateOf<DialogState>(DialogState.Hidden)
    private var lastSelectedFilesCount by mutableIntStateOf(0)
    private var isWifiP2pConnected by mutableStateOf(true)
    private val transferRecords = mutableStateListOf<TransferRecord>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        groupOwnerAddress = intent.getStringExtra(ScanActivity.EXTRA_GROUP_OWNER_ADDRESS)
        remotePort = intent.getIntExtra(ScanActivity.EXTRA_REMOTE_PORT, 0)
        remoteName = intent.getStringExtra(ScanActivity.EXTRA_REMOTE_NAME)
        remoteColor = if (intent.hasExtra(ScanActivity.EXTRA_REMOTE_COLOR)) intent.getIntExtra(ScanActivity.EXTRA_REMOTE_COLOR, -1).takeIf { it != -1 } else null
        val displayName = remoteName?.takeIf { it.isNotEmpty() } ?: "未知用户"
        Log.d(TAG, "onCreate groupOwnerAddress=$groupOwnerAddress remotePort=$remotePort remoteName=$displayName")

        ServiceRegistry.getService(WifiP2pService::class.java).onDisconnected = {
            Log.d(TAG, "onDisconnected")
            isWifiP2pConnected = false
        }

        lifecycleScope.launch {
            ServiceRegistry.getService(TransferService::class.java).progressFlow.collectLatest { progress ->
                when (progress) {
                    is TransferProgress.SendPreparing -> {
                        dialogState = DialogState.Preparing(progress.stage)
                    }
                    is TransferProgress.SendInProgress -> {
                        dialogState = DialogState.Sending(progress.sentBytes, progress.totalBytes, progress.speedBytesPerSec)
                    }
                    is TransferProgress.SendDone -> {
                        dialogState = DialogState.Done(progress.fileCount, progress.totalBytes, progress.elapsedMs, progress.avgSpeedBytesPerSec)
                        transferRecords.add(
                            TransferRecord(
                                fileCount = progress.fileCount,
                                totalBytes = progress.totalBytes,
                                avgSpeedBytesPerSec = progress.avgSpeedBytesPerSec,
                                endTime = System.currentTimeMillis()
                            )
                        )
                    }
                    is TransferProgress.SendError -> {
                        dialogState = DialogState.Error(progress.message)
                    }
                    else -> {}
                }
            }
        }

        pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), selectedFilesCallback)
        pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), selectedFilesCallback)

        lifecycleScope.launch {
            val lastSelectedFiles = storageService.getLastSelectedFiles()
            lastSelectedFilesCount = lastSelectedFiles.size
        }

        setContent {
            NearbyShareTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("发送方") },
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "正在向 ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            UserBadge(
                                name = remoteName,
                                color = remoteColor
                            )
                            Text(
                                text = " 发送文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp)
                        ) {
                            if (transferRecords.isEmpty()) {
                                Text(
                                    text = "无传输历史",
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
                            if (lastSelectedFilesCount > 0) {
                                OutlinedButton(
                                    onClick = { sendLastSelectedFiles() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("选择之前未发送完的 $lastSelectedFilesCount 个文件")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { selectImages() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("发送照片")
                                }

                                Button(
                                    onClick = { selectFiles() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("发送文件")
                                }
                            }

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
                            title = { Text("准备中...") },
                            text = {
                                Text(state.stage.ifBlank { "正在准备发送..." })
                            }
                        )
                    }
                    is DialogState.Sending -> {
                        val sent = state.sentBytes
                        val total = state.totalBytes
                        val speedMB = state.speedBytesPerSec / 1_048_576.0
                        val progress = if (total > 0) sent.toFloat() / total else 0f
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                            confirmButton = {},
                            title = { Text("发送中") },
                            text = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("${formatBytes(sent)} / ${formatBytes(total)}")
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
                            title = { Text("发送完成") },
                            text = {
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("总大小：${formatBytes(state.totalBytes)}")
                                    Text("耗时：${"%.2f".format(elapsedSec)} 秒")
                                    Text("平均速度：${"%.2f".format(avgMB)} MB/s")
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
                            title = { Text("发送失败") },
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

    private fun sendFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            val address = groupOwnerAddress ?: return@launch

            storageService.deleteLastSelectedFiles()
            lastSelectedFilesCount = 0

            storageService.putLastSelectedFiles(uris)

            val result = ServiceRegistry.getService(TransferService::class.java)
                .sendFiles(address, remotePort, uris)

            if (result.isSuccess) {
                storageService.deleteLastSelectedFiles()
                lastSelectedFilesCount = 0
            }
        }
    }

    private fun selectImages() {
        val pickMediaRequest = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            .build()
        pickMedia.launch(pickMediaRequest)
    }

    private fun selectFiles() {
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun sendLastSelectedFiles() {
        lifecycleScope.launch {
            val lastSelectedFiles = storageService.getLastSelectedFiles()
            if (lastSelectedFiles.isNotEmpty()) {
                sendFiles(lastSelectedFiles)
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