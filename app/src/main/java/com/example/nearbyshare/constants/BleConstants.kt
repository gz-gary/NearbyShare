package com.example.nearbyshare.constants

import java.util.UUID

/**
 * BLE相关常量
 */
object BleConstants {
    /**
     * GATT服务UUID
     */
    val SERVICE_UUID: UUID = UUID.fromString("6698F5F8-38CC-45F3-B134-CABD017E185A")

    /**
     * GATT Manifest特征UUID
     */
    val MANIFEST_CHARACTERISTIC_UUID: UUID = UUID.fromString("D25CB529-D7F7-4E62-897E-9CCB662FAE2F")

    /**
     * GATT Secret特征UUID
     */
    val SECRET_CHARACTERISTIC_UUID: UUID = UUID.fromString("C5B8A9F1-3D7E-4A2C-8F6D-1E9B0A5C3D2F")
}