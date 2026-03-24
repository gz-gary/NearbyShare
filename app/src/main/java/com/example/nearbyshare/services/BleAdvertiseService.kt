package com.example.nearbyshare.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.nearbyshare.ManifestData
import com.example.nearbyshare.SecretData
import com.example.nearbyshare.constants.BleConstants

class BleAdvertiseService(private val context: Context) : ApplicationService() {

    companion object {
        const val TAG = "BleAdvertiseService"

        val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var bluetoothGattServer: BluetoothGattServer
    private var bleAdvertiseCallback: AdvertiseCallback? = null

    private var isAdvertising = false
    private var isGattServerRunning = false

    var manifestData: ManifestData? = null
    var secretData: SecretData? = null

    @SuppressLint("MissingPermission")
    fun startBleOperations() {
        val adapter = bluetoothManager.adapter

        if (!adapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "startBleOperations not supported")
            return
        }

        bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser

        startGattServer()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Log.d(TAG, "startAdvertising")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        bleAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "onStartSuccess mode=${settingsInEffect.mode} txPower=${settingsInEffect.txPowerLevel}")
                isAdvertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "onStartFailure errorCode=$errorCode")
                isAdvertising = false
            }
        }

        bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, bleAdvertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        Log.d(TAG, "startGattServer")

        val gattCallback = object : BluetoothGattServerCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange device=${device?.address} status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "onConnectionStateChange CONNECTED device=${device?.address}")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "onConnectionStateChange DISCONNECTED device=${device?.address}")
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                Log.d(TAG, "onCharacteristicReadRequest device=${device?.address} requestId=$requestId offset=$offset uuid=${characteristic?.uuid}")

                when (characteristic?.uuid) {
                    BleConstants.MANIFEST_CHARACTERISTIC_UUID -> {
                        val dataToSend = manifestData?.toByteArray() ?: byteArrayOf()
                        Log.d(TAG, "onCharacteristicReadRequest manifest size=${dataToSend.size}")
                        bluetoothGattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            dataToSend
                        )
                    }
                    BleConstants.SECRET_CHARACTERISTIC_UUID -> {
                        if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                            val secretBytes = secretData?.toByteArray() ?: byteArrayOf()
                            Log.d(TAG, "onCharacteristicReadRequest secret size=${secretBytes.size}")
                            bluetoothGattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                secretBytes
                            )
                        } else {
                            Log.w(TAG, "onCharacteristicReadRequest device not bonded")
                            bluetoothGattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                                offset,
                                null
                            )
                        }
                    }
                    else -> {
                        Log.w(TAG, "onCharacteristicReadRequest unknown uuid=${characteristic?.uuid}")
                        bluetoothGattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            0,
                            null
                        )
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor?
            ) {
                Log.d(TAG, "onDescriptorReadRequest device=${device?.address} uuid=${descriptor?.uuid}")
                bluetoothGattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    descriptor?.value
                )
            }
        }

        bluetoothGattServer = bluetoothManager.openGattServer(context, gattCallback)

        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val manifestCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MANIFEST_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val secretCharacteristic = BluetoothGattCharacteristic(
            BleConstants.SECRET_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        )

        service.addCharacteristic(manifestCharacteristic)
        service.addCharacteristic(secretCharacteristic)

        bluetoothGattServer.addService(service)

        isGattServerRunning = true
        Log.i(TAG, "startGattServer serviceUuid=${BleConstants.SERVICE_UUID}")
    }

    @SuppressLint("MissingPermission")
    fun stopBleOperations() {
        Log.d(TAG, "stopBleOperations")
        stopAdvertising()
        stopGattServer()
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising && bleAdvertiseCallback != null) {
            Log.d(TAG, "stopAdvertising")
            bluetoothLeAdvertiser.stopAdvertising(bleAdvertiseCallback)
            isAdvertising = false
            Log.i(TAG, "stopAdvertising done")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        if (isGattServerRunning) {
            Log.d(TAG, "stopGattServer")
            bluetoothGattServer.close()
            isGattServerRunning = false
            Log.i(TAG, "stopGattServer done")
        }
    }
}
