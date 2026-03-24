package com.example.nearbyshare.services

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiP2pService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "WifiP2pService"

        val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private var wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel =
        wifiP2pManager.initialize(context, context.mainLooper, null)
    private val wifiP2pBroadcastReceiver = WifiP2pBroadcastReceiver(this)

    data class GroupInfo(
        val networkName: String,
        val passphrase: String,
    )

    data class ConnectionInfo(
        val groupOwnerAddress: String,
        val isGroupOwner: Boolean,
        val groupFormed: Boolean
    )

    init {
        context.registerReceiver(wifiP2pBroadcastReceiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        })
    }

    fun cleanup() {
        Log.d(TAG, "cleanup")
        removeGroupAsync()
        context.unregisterReceiver(wifiP2pBroadcastReceiver)
    }

    suspend fun createGroup(): GroupInfo {
        return withTimeout(5 * 1000L) {
            runCatching {
                removeGroupImpl()
            }
            createGroupImpl()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun createGroupImpl(): GroupInfo = suspendCancellableCoroutine { continuation ->
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "createGroupImpl onSuccess")
                pendingCreateGroupCallback = { groupInfo ->
                    continuation.resume(groupInfo)
                }
                continuation.invokeOnCancellation {
                    pendingCreateGroupCallback = null
                }
            }

            override fun onFailure(reason: Int) {
                val errorMsg = "createGroupImpl onFailure reason=$reason"
                Log.e(TAG, errorMsg)
                continuation.resumeWithException(Exception(errorMsg))
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun connect(networkName: String, passphrase: String): ConnectionInfo {
        return withTimeout(6 * 1000L) {
            runCatching {
                removeGroupImpl()
            }
            val groupInfo = connectImpl(networkName, passphrase)
            val p2pIpAssigned = waitForP2pIpAssigned(groupInfo.`interface`)
            if (!p2pIpAssigned) {
                throw Exception("p2pIpAssigned is false")
            }
            ConnectionInfo("192.168.49.1", false, true)
        }
    }

    @Suppress("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectImpl(networkName: String, passphrase: String): WifiP2pGroup = suspendCancellableCoroutine { continuation ->
        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .setDeviceAddress(MacAddress.fromString("02:00:00:00:00:00"))
            .build()

        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connectImpl onSuccess")
                pendingConnectCallback = { groupInfo ->
                    continuation.resume(groupInfo)
                }
                continuation.invokeOnCancellation {
                    pendingConnectCallback = null
                }
            }

            override fun onFailure(reason: Int) {
                val errorMsg = "connectImpl onFailure reason=$reason"
                Log.e(TAG, errorMsg)
                continuation.resumeWithException(Exception(errorMsg))
            }
        })
    }

    fun removeGroupAsync() {
        Log.d(TAG, "removeGroupAsync")
        wifiP2pManager.removeGroup(channel, null)
    }

    suspend fun removeGroup() {
        withTimeout(5 * 1000L) {
            removeGroupImpl()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun removeGroupImpl() = suspendCancellableCoroutine { continuation ->
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroupImpl onSuccess")
                continuation.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                val errorMsg = "removeGroupImpl onFailure reason=$reason"
                Log.e(TAG, errorMsg)
                continuation.resumeWithException(Exception(errorMsg))
            }
        })
    }

    private suspend fun waitForP2pIpAssigned(interfaceName: String, timeoutMillis: Long = 2 * 1000L): Boolean {
        return withTimeoutOrNull(timeoutMillis) {
            while (true) {
                runCatching {
                    val p2pInterface = NetworkInterface.getByName(interfaceName)
                    val addrs = p2pInterface.inetAddresses
                    if (addrs.asSequence().any { addr -> addr is Inet4Address }) {
                        return@withTimeoutOrNull true
                    }
                }
                delay(200)
            }
            false
        } ?: false
    }

    var onNewClient: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    private var pendingCreateGroupCallback: ((GroupInfo) -> Unit)? = null
    private var pendingConnectCallback: ((WifiP2pGroup) -> Unit)? = null
    private var isConnectedAsGroupOwner = false
    private var isConnectedAsClient = false

    @Suppress("MissingPermission")
    fun onConnectionChanged() {
        Log.d(TAG, "onConnectionChanged")
        wifiP2pManager.requestConnectionInfo(channel) { info ->
            if (info == null) return@requestConnectionInfo
            if (!info.groupFormed) return@requestConnectionInfo

            if (info.isGroupOwner) {
                wifiP2pManager.requestGroupInfo(channel) { groupInfo ->
                    if (groupInfo == null) return@requestGroupInfo
                    val networkName = groupInfo.networkName
                    val passphrase = groupInfo.passphrase
                    pendingCreateGroupCallback?.invoke(GroupInfo(networkName, passphrase))
                    pendingCreateGroupCallback = null
                    if (groupInfo.clientList.isNotEmpty()) {
                        Log.d(TAG, "onNewClient")
                        isConnectedAsGroupOwner = true
                        onNewClient?.invoke()
                        onNewClient = null
                    } else {
                        onDisconnectAsGroupOwner()
                    }
                }
            } else {
                wifiP2pManager.requestGroupInfo(channel) { groupInfo ->
                    if (groupInfo == null) return@requestGroupInfo
                    pendingConnectCallback?.let {
                        it.invoke(groupInfo)
                        isConnectedAsClient = true
                    }
                    pendingConnectCallback = null
                }
            }
        }
    }

    fun onDisconnectAsGroupOwner() {
        if (!isConnectedAsGroupOwner) return
        Log.d(TAG, "onDisconnectAsGroupOwner")
        isConnectedAsGroupOwner = false
        onDisconnected?.invoke()
        onDisconnected = null
    }

    fun onDisconnectAsClient() {
        if (!isConnectedAsClient) return
        Log.d(TAG, "onDisconnectAsClient")
        isConnectedAsClient = false
        onDisconnected?.invoke()
        onDisconnected = null
    }
}

class WifiP2pBroadcastReceiver(
    private val wifiP2pService: WifiP2pService
) : BroadcastReceiver() {
    companion object {
        private const val TAG = "WifiP2pBroadcastReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive intent.action=${intent?.action}")
        if (intent == null) return
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    wifiP2pService.onConnectionChanged()
                } else {
                    wifiP2pService.onDisconnectAsClient()
                }
            }
        }
    }
}
