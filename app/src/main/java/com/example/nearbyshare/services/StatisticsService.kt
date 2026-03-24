package com.example.nearbyshare.services

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException

class StatisticsService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "StatisticsService"
        private const val STATISTICS_DIR = "statistics"
    }

    private val exportService by lazy {
        ServiceRegistry.getService(ExportService::class.java)
    }

    private val openWriters = mutableMapOf<Long, FileWriter>()

    fun createStatistics(): Long {
        val id = System.currentTimeMillis()
        val dir = File(context.filesDir, STATISTICS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "statistics_$id.txt")
        return try {
            val writer = FileWriter(file, true)
            openWriters[id] = writer
            Log.d(TAG, "createStatistics id=$id file=${file.absolutePath}")
            id
        } catch (e: IOException) {
            Log.e(TAG, "createStatistics error ${e.message}")
            -1
        }
    }

    fun writeStatistics(id: Long, startTime: Long, endTime: Long, dataTransferred: Long) {
        val writer = openWriters[id]
        if (writer == null) {
            Log.e(TAG, "writeStatistics id=$id not found")
            return
        }
        try {
            writer.write("$startTime,$endTime,$dataTransferred\n")
            writer.flush()
            Log.d(TAG, "writeStatistics id=$id $startTime,$endTime,$dataTransferred")
        } catch (e: IOException) {
            Log.e(TAG, "writeStatistics error ${e.message}")
        }
    }

    fun saveStatistics(id: Long) {
        val writer = openWriters.remove(id)
        if (writer == null) {
            Log.e(TAG, "saveStatistics id=$id not found")
            return
        }
        try {
            writer.close()
            Log.d(TAG, "saveStatistics id=$id")
        } catch (e: IOException) {
            Log.e(TAG, "saveStatistics error ${e.message}")
        }
    }

    fun cleanStatistics() {
        val dir = File(context.filesDir, STATISTICS_DIR)
        if (!dir.exists()) {
            Log.d(TAG, "cleanStatistics dir not exists")
            return
        }
        val files = dir.listFiles { file -> file.extension == "txt" }
        files?.forEach { file ->
            file.delete()
            Log.d(TAG, "cleanStatistics deleted ${file.name}")
        }
        Log.d(TAG, "cleanStatistics done")
    }

    fun exportStatistics(): Boolean {
        val dir = File(context.filesDir, STATISTICS_DIR)
        if (!dir.exists()) {
            Log.d(TAG, "exportStatistics dir not exists")
            return false
        }
        val files = dir.listFiles { file -> file.extension == "txt" }
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "exportStatistics no files to export")
            return false
        }
        var success = true
        files.forEach { file ->
            try {
                val uri = exportService.exportFile(file, file.name, "text/plain")
                if (uri == null) {
                    Log.e(TAG, "exportStatistics failed for ${file.name}")
                    success = false
                } else {
                    Log.d(TAG, "exportStatistics ${file.name} -> $uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportStatistics error ${e.message}")
                success = false
            }
        }
        return success
    }
}
