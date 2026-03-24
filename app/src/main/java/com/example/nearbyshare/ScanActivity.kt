package com.example.nearbyshare

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nearbyshare.services.BleScanService
import com.example.nearbyshare.services.ServiceRegistry
import com.example.nearbyshare.services.SettingsService
import com.example.nearbyshare.services.WifiP2pService
import com.example.nearbyshare.ui.ColorCircle
import com.example.nearbyshare.ui.DeviceCard
import com.example.nearbyshare.ui.UserBadge
import com.example.nearbyshare.ui.PermissionDeniedDialog
import com.example.nearbyshare.ui.PermissionRequestMask
import com.example.nearbyshare.ui.theme.NearbyShareTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ScanActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScanActivity"
        const val EXTRA_GROUP_OWNER_ADDRESS = "extra_group_owner_address"
        const val EXTRA_REMOTE_PORT = "extra_remote_port"
        const val EXTRA_REMOTE_NAME = "extra_remote_name"
        const val EXTRA_REMOTE_COLOR = "extra_remote_color"

        val REQUIRED_PERMISSIONS = (BleScanService.REQUIRED_PERMISSIONS +
                WifiP2pService.REQUIRED_PERMISSIONS).distinct().toTypedArray()
    }

    private val devicesList = mutableStateListOf<Pair<BluetoothDevice, ManifestData>>()
    private val showPermissionDeniedDialog = mutableStateOf(false)
    private val showPermissionsMask = mutableStateOf(false)
    private val isConnecting = mutableStateOf(false)
    private val autoStatus = mutableStateOf("寻找设备中")
    private val connectingDeviceInfo = mutableStateOf<ManifestData?>(null)

    private val callback = object : BleScanService.BleScanCallback {
        override fun onDeviceFound(device: BluetoothDevice, manifestData: ManifestData) {
            runOnUiThread {
                val exists = devicesList.any { it.first.address == device.address }
                if (!exists) {
                    devicesList.add(Pair(device, manifestData))
                    Log.d(TAG, "onDeviceFound added address=${device.address} name=${manifestData.name} size=${devicesList.size}")

                    if (isAutoMode && !hasNavigatedToSendActivity && !isAutoConnectStarted) {
                        isAutoConnectStarted = true
                        connectToDevice(device, manifestData)
                    }
                }
            }
        }
    }

    private var hasNavigatedToSendActivity = false
    private val isAutoMode by lazy {
        ServiceRegistry.getService(SettingsService::class.java).isAutoConnectEnabled()
    }
    private var isAutoConnectStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        showPermissionsMask.value = false
        val allGranted = REQUIRED_PERMISSIONS.all { permissions[it] == true }
        
        if (allGranted) {
            onPermissionsAllGranted()
        } else {
            val deniedPermissions = REQUIRED_PERMISSIONS.filter { permissions[it] != true }
            Log.w(TAG, "permissionLauncher denied=$deniedPermissions")
            showPermissionDeniedDialog.value = true
        }
    }

    private fun onPermissionsAllGranted() {
        Log.i(TAG, "onPermissionsAllGranted")
        ServiceRegistry.getService(BleScanService::class.java).registerBleScanCallback(callback)
        ServiceRegistry.getService(BleScanService::class.java).startBleScan()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                                        imageVector = Icons.Default.Close,
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
                    if (isAutoMode) {
                        AutoModeContent(
                            status = autoStatus.value,
                            deviceInfo = connectingDeviceInfo.value,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        ManualModeContent(
                            devices = devicesList,
                            modifier = Modifier.padding(innerPadding),
                            onDeviceClick = { device, manifestData ->
                                connectToDevice(device, manifestData)
                            }
                        )
                    }
                }

                if (showPermissionDeniedDialog.value) {
                    PermissionDeniedDialog(message = "Nearby Share需要「附近的设备」权限才可以正常工作，请手动授予后重试")
                }

                if (isConnecting.value && !isAutoMode) {
                    ConnectingDialog()
                }

                if (showPermissionsMask.value) {
                    PermissionRequestMask(message = "Nearby Share需要「查找与连接附近的设备」权限才可以正常工作")
                }
            }
        }

        requestPermissions()
    }

    private fun connectToDevice(
        device: BluetoothDevice,
        manifestData: ManifestData
    ) {
        autoStatus.value = "正在连接"
        connectingDeviceInfo.value = manifestData
        isConnecting.value = true
        Log.d(TAG, "connectToDevice name=${manifestData.name} isAutoMode=$isAutoMode")

        lifecycleScope.launch {
            val secretData = withTimeoutOrNull(10000L) {
                ServiceRegistry.getService(BleScanService::class.java).readSecretFromDevice(device)
            }

            if (secretData == null) {
                Log.e(TAG, "connectToDevice readSecret failed or timeout")
                autoStatus.value = "寻找设备中"
                isConnecting.value = false
                connectingDeviceInfo.value = null
                Toast.makeText(this@ScanActivity, "连接失败: 无法获取设备信息", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Log.d(TAG, "connectToDevice readSecret success networkName=${secretData.networkName} port=${secretData.port}")

            try {
                val connectionInfo = ServiceRegistry.getService(WifiP2pService::class.java).connect(
                    secretData.networkName,
                    secretData.passphrase
                )
                Log.d(TAG, "connectToDevice wifiP2pConnect success groupOwnerAddress=${connectionInfo.groupOwnerAddress}")
                val intent = Intent(this@ScanActivity, SendActivity::class.java).apply {
                    putExtra(EXTRA_GROUP_OWNER_ADDRESS, connectionInfo.groupOwnerAddress)
                    putExtra(EXTRA_REMOTE_PORT, secretData.port)
                    putExtra(EXTRA_REMOTE_NAME, manifestData.name)
                    putExtra(EXTRA_REMOTE_COLOR, manifestData.color)
                }
                hasNavigatedToSendActivity = true
                Log.d(TAG, "connectToDevice navigating to SendActivity")
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "connectToDevice wifiP2pConnect failed: ${e.message}")
                autoStatus.value = "寻找设备中"
                isConnecting.value = false
                connectingDeviceInfo.value = null
                Toast.makeText(this@ScanActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestPermissions() {
        val allGranted = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            onPermissionsAllGranted()
            return
        }
        Log.d(TAG, "requestPermissions show mask")
        showPermissionsMask.value = true
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy hasNavigatedToSendActivity=$hasNavigatedToSendActivity")
        ServiceRegistry.getService(BleScanService::class.java).cleanup()
        super.onDestroy()
    }
}

@Composable
private fun AutoModeContent(
    status: String,
    deviceInfo: ManifestData?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium
                )
                if (deviceInfo != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    UserBadge(
                        name = deviceInfo.name,
                        color = deviceInfo.color
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualModeContent(
    devices: List<Pair<BluetoothDevice, ManifestData>>,
    modifier: Modifier = Modifier,
    onDeviceClick: (BluetoothDevice, ManifestData) -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "点击连接要发送的设备",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn {
            items(devices) { (device, manifestData) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onDeviceClick(device, manifestData) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    DeviceCard(
                        name = manifestData.name,
                        color = manifestData.color,
                        onClick = { onDeviceClick(device, manifestData) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectingDialog() {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("正在连接") },
        text = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
        },
        confirmButton = {}
    )
}
