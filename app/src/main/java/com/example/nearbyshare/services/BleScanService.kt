package com.example.nearbyshare.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.nearbyshare.ManifestData
import com.example.nearbyshare.SecretData
import com.example.nearbyshare.constants.BleConstants
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BleScanService(private val context: Context) : ApplicationService() {
    companion object {
        const val TAG = "BleScanService"

        val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothLeScanner: BluetoothLeScanner = bluetoothManager.adapter.bluetoothLeScanner

    private var bleScanCallback: ScanCallback? = null

    private enum class DeviceState {
        PENDING,
        KNOWN
    }
    private val deviceStates = mutableMapOf<String, DeviceState>()

    private val activeGatts = mutableMapOf<String, BluetoothGatt>()

    private val pendingReadSecretContinuation = mutableMapOf<String, CancellableContinuation<SecretData?>>()

    private var isScanning = false
    private val isAutoMode by lazy {
        ServiceRegistry.getService(SettingsService::class.java).isAutoConnectEnabled()
    }

    interface BleScanCallback {
        fun onDeviceFound(device: BluetoothDevice, manifestData: ManifestData)
    }

    private var callback: BleScanCallback? = null
    fun registerBleScanCallback(callback: BleScanCallback) {
        this.callback = callback
    }

    fun cleanup() {
        callback = null
        stopBleScan()
        clearAllPendingContinuation()
        closeAllGatts()
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        deviceStates.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address

                val state = deviceStates[address]
                if (state == DeviceState.PENDING || state == DeviceState.KNOWN) {
                    Log.d(TAG, "onScanResult ignore device address=$address state=$state")
                    return
                }

                if (isAutoMode && deviceStates.isNotEmpty()) {
                    Log.d(TAG, "onScanResult ignore in auto mode address=$address deviceStates.size=${deviceStates.size}")
                    return
                }

                Log.i(TAG, "onScanResult found device address=$address name=${device.name} rssi=${result.rssi}")

                deviceStates[address] = DeviceState.PENDING
                connectToDevice(device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "onScanFailed errorCode=$errorCode")
            }
        }

        Log.d(TAG, "startBleScan")
        bluetoothLeScanner.startScan(listOf(filter), settings, bleScanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        Log.d(TAG, "connectToDevice address=$address")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "onConnectionStateChange CONNECTED address=$address")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "onConnectionStateChange DISCONNECTED address=$address")
                        activeGatts.remove(address)
                        gatt.close()

                        val pendingContinuation = pendingReadSecretContinuation.remove(address)
                        if (pendingContinuation != null && pendingContinuation.isActive) {
                            Log.d(TAG, "onConnectionStateChange DISCONNECTED resume null pending continuation address=$address")
                            pendingContinuation.resume(null)
                        }

                        if (deviceStates[address] == DeviceState.PENDING) {
                            deviceStates.remove(address)
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onServicesDiscovered status=$status address=${gatt.device.address}")
                    gatt.disconnect()
                    return
                }

                Log.d(TAG, "onServicesDiscovered address=${gatt.device.address}")

                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "onServicesDiscovered service not found uuid=${BleConstants.SERVICE_UUID}")
                    gatt.disconnect()
                    return
                }

                val characteristic = service.getCharacteristic(BleConstants.MANIFEST_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "onServicesDiscovered characteristic not found uuid=${BleConstants.MANIFEST_CHARACTERISTIC_UUID}")
                    gatt.disconnect()
                    return
                }

                Log.d(TAG, "onServicesDiscovered requestMtu 517")
                gatt.requestMtu(517)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onMtuChanged failed status=$status")
                    gatt.disconnect()
                    return
                }

                Log.d(TAG, "onMtuChanged mtu=$mtu")

                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "onMtuChanged service not found uuid=${BleConstants.SERVICE_UUID}")
                    gatt.disconnect()
                    return
                }

                val characteristic = service.getCharacteristic(BleConstants.MANIFEST_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "onMtuChanged characteristic not found uuid=${BleConstants.MANIFEST_CHARACTERISTIC_UUID}")
                    gatt.disconnect()
                    return
                }

                Log.d(TAG, "onMtuChanged readCharacteristic uuid=${BleConstants.MANIFEST_CHARACTERISTIC_UUID}")
                gatt.readCharacteristic(characteristic)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "onCharacteristicRead status=$status")
                    gatt.disconnect()
                    return
                }

                val address = gatt.device.address
                Log.d(TAG, "onCharacteristicRead address=$address length=${value.size} uuid=${characteristic.uuid}")

                when (characteristic.uuid) {
                    BleConstants.MANIFEST_CHARACTERISTIC_UUID -> {
                        try {
                            val manifestData = ManifestData.parseFrom(value)
                            callback?.onDeviceFound(gatt.device, manifestData)

                            deviceStates[address] = DeviceState.KNOWN
                            Log.i(TAG, "onCharacteristicRead manifest marked KNOWN address=$address")

                        } catch (e: Exception) {
                            Log.e(TAG, "onCharacteristicRead manifest deserialize failed", e)
                        }
                    }
                    BleConstants.SECRET_CHARACTERISTIC_UUID -> {
                        val continuation = pendingReadSecretContinuation.remove(address)
                        if (continuation != null && continuation.isActive) {
                            try {
                                val secretData = SecretData.parseFrom(value)
                                Log.d(TAG, "onCharacteristicRead secret resume address=$address")
                                continuation.resume(secretData)
                            } catch (e: Exception) {
                                Log.e(TAG, "onCharacteristicRead secret deserialize failed", e)
                                continuation.resume(null)
                            }
                        }
                        gatt.disconnect()
                    }
                    else -> {
                        Log.w(TAG, "onCharacteristicRead unknown uuid=${characteristic.uuid}")
                        gatt.disconnect()
                    }
                }
            }
        }

        val gatt = device.connectGatt(context, false, gattCallback)
        activeGatts[address] = gatt
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (isScanning && bleScanCallback != null) {
            Log.d(TAG, "stopBleScan")
            bluetoothLeScanner.stopScan(bleScanCallback)
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readSecretFromDevice(device: BluetoothDevice): SecretData? = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "readSecretFromDevice address=${device.address}")

        val address = device.address
        pendingReadSecretContinuation[address] = continuation

        val existingGatt = activeGatts[address]
        if (existingGatt != null) {
            Log.d(TAG, "readSecretFromDevice use existing gatt address=$address")
            val service = existingGatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "readSecretFromDevice service not found uuid=${BleConstants.SERVICE_UUID}")
                pendingReadSecretContinuation.remove(address)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val characteristic = service.getCharacteristic(BleConstants.SECRET_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.w(TAG, "readSecretFromDevice characteristic not found uuid=${BleConstants.SECRET_CHARACTERISTIC_UUID}")
                pendingReadSecretContinuation.remove(address)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            existingGatt.readCharacteristic(characteristic)
        } else {
            Log.d(TAG, "readSecretFromDevice no existing gatt address=$address")
            pendingReadSecretContinuation.remove(address)
            continuation.resume(null)
        }

        continuation.invokeOnCancellation {
            Log.d(TAG, "readSecretFromDevice invokeOnCancellation address=$address")
            pendingReadSecretContinuation.remove(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeAllGatts() {
        Log.d(TAG, "closeAllGatts count=${activeGatts.size}")
        activeGatts.values.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "closeAllGatts exception", e)
            }
        }
        activeGatts.clear()
    }

    private fun clearAllPendingContinuation() {
        pendingReadSecretContinuation.values.forEach { continuation ->
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
        pendingReadSecretContinuation.clear()
    }
}