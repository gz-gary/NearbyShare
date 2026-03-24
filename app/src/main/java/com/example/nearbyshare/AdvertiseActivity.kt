package com.example.nearbyshare

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nearbyshare.services.BleAdvertiseService
import com.example.nearbyshare.services.ServiceRegistry
import com.example.nearbyshare.services.SettingsService
import com.example.nearbyshare.services.TransferService
import com.example.nearbyshare.services.WifiP2pService
import com.example.nearbyshare.ui.ColorCircle
import com.example.nearbyshare.ui.UserBadge
import com.example.nearbyshare.ui.PermissionDeniedDialog
import com.example.nearbyshare.ui.PermissionRequestMask
import com.example.nearbyshare.ui.theme.NearbyShareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdvertiseActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AdvertiseActivity"

        val REQUIRED_PERMISSIONS = (BleAdvertiseService.REQUIRED_PERMISSIONS +
                                   WifiP2pService.REQUIRED_PERMISSIONS).distinct().toTypedArray()
    }

    private var serverPort: Int = 0
    private var hasNavigatedToReceiveActivity = false
    private val showPermissionDeniedDialog = mutableStateOf(false)
    private val showPermissionsMask = mutableStateOf(false)
    private val settingsService by lazy {
        ServiceRegistry.getService(SettingsService::class.java)
    }

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
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val groupInfo = ServiceRegistry.getService(WifiP2pService::class.java).createGroup()
                Log.d(TAG, "createGroup networkName=${groupInfo.networkName} passphrase=${groupInfo.passphrase}")

                val manifestBuilder = ManifestData.newBuilder()
                val userName = settingsService.getUserName()
                if (userName.isNotEmpty()) {
                    manifestBuilder.setName(userName)
                }
                val userColor = settingsService.getUserColor()
                manifestBuilder.setColor(userColor)
                val manifestData = manifestBuilder.build()

                val secretData = SecretData.newBuilder()
                    .setNetworkName(groupInfo.networkName)
                    .setPassphrase(groupInfo.passphrase)
                    .setPort(serverPort)
                    .build()

                Log.d(TAG, "manifestData.size=${manifestData.toByteArray().size} secretData.size=${secretData.toByteArray().size}")

                ServiceRegistry.getService(BleAdvertiseService::class.java).manifestData = manifestData
                ServiceRegistry.getService(BleAdvertiseService::class.java).secretData = secretData
                ServiceRegistry.getService(BleAdvertiseService::class.java).startBleOperations()

            } catch (e: Exception) {
                Log.e(TAG, "createGroup failed: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        serverPort = ServiceRegistry.getService(TransferService::class.java).serverPort
        Log.d(TAG, "onCreate serverPort=$serverPort")

        ServiceRegistry.getService(WifiP2pService::class.java).onNewClient = {
            hasNavigatedToReceiveActivity = true
            Log.d(TAG, "navigating to ReceiveActivity")
            startActivity(Intent(this, ReceiveActivity::class.java))
            finish()
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            val userName = settingsService.getUserName()
                            val userColor = settingsService.getUserColor()
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "可被发现为 ",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                UserBadge(
                                    name = userName,
                                    color = userColor
                                )
                            }
                        }
                    }
                }
                
                if (showPermissionDeniedDialog.value) {
                    PermissionDeniedDialog(message = "Nearby Share需要「附近的设备」权限才可以正常工作，请手动授予后重试")
                }

                if (showPermissionsMask.value) {
                    PermissionRequestMask(message = "Nearby Share需要「查找与连接附近的设备」权限才可以正常工作")
                }
            }
        }

        requestPermissions()
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
        Log.d(TAG, "onDestroy hasNavigatedToReceiveActivity=$hasNavigatedToReceiveActivity")
        ServiceRegistry.getService(BleAdvertiseService::class.java).stopBleOperations()
        if (!hasNavigatedToReceiveActivity) {
            ServiceRegistry.getService(WifiP2pService::class.java).removeGroupAsync()
        }
        super.onDestroy()
    }
}