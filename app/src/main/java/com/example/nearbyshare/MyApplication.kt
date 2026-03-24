package com.example.nearbyshare

import android.app.Application
import com.example.nearbyshare.services.BleAdvertiseService
import com.example.nearbyshare.services.BleScanService
import com.example.nearbyshare.services.ExportService
import com.example.nearbyshare.services.ServiceRegistry
import com.example.nearbyshare.services.SettingsService
import com.example.nearbyshare.services.StatisticsService
import com.example.nearbyshare.services.StorageService
import com.example.nearbyshare.services.TransferService
import com.example.nearbyshare.services.WifiP2pService

class MyApplication : Application() {
    companion object {
        private lateinit var instance: MyApplication

        fun get(): MyApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        ServiceRegistry.registerService(BleScanService::class.java, BleScanService(this))
        ServiceRegistry.registerService(BleAdvertiseService::class.java, BleAdvertiseService(this))
        ServiceRegistry.registerService(WifiP2pService::class.java, WifiP2pService(this))
        ServiceRegistry.registerService(TransferService::class.java, TransferService(this))
        ServiceRegistry.registerService(StorageService::class.java, StorageService(this))
        ServiceRegistry.registerService(ExportService::class.java, ExportService(this))
        ServiceRegistry.registerService(SettingsService::class.java, SettingsService(this))
        ServiceRegistry.registerService(StatisticsService::class.java, StatisticsService(this))
    }
}