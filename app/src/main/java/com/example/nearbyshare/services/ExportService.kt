package com.example.nearbyshare.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.nearbyshare.FileType
import java.io.File
import java.io.FileInputStream

data class ExportResult(
    val uri: Uri,
    val saveLocation: String
)

class ExportService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "ExportService"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun exportFile(cacheFile: File, originalName: String, mimeType: String): ExportResult? {
        val fileType = when {
            mimeType.startsWith("image/") -> FileType.IMAGE
            mimeType.startsWith("video/") -> FileType.VIDEO
            else -> FileType.DOCUMENT
        }

        val saveLocation = when (fileType) {
            FileType.IMAGE -> "Pictures"
            FileType.VIDEO -> "Movies"
            else -> "Downloads"
        }

        val relPath = when (fileType) {
            FileType.IMAGE -> "${Environment.DIRECTORY_PICTURES}/NearbyShare"
            FileType.VIDEO -> "${Environment.DIRECTORY_MOVIES}/NearbyShare"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/NearbyShare"
        }

        val collection = when (fileType) {
            FileType.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            FileType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val pendingKey = when (fileType) {
            FileType.IMAGE -> MediaStore.Images.Media.IS_PENDING
            FileType.VIDEO -> MediaStore.Video.Media.IS_PENDING
            else -> MediaStore.Downloads.IS_PENDING
        }

        val relPathKey = when (fileType) {
            FileType.IMAGE -> MediaStore.Images.Media.RELATIVE_PATH
            FileType.VIDEO -> MediaStore.Video.Media.RELATIVE_PATH
            else -> MediaStore.Downloads.RELATIVE_PATH
        }

        val displayNameKey = when (fileType) {
            FileType.IMAGE -> MediaStore.Images.Media.DISPLAY_NAME
            FileType.VIDEO -> MediaStore.Video.Media.DISPLAY_NAME
            else -> MediaStore.Downloads.DISPLAY_NAME
        }

        val values = ContentValues().apply {
            put(displayNameKey, originalName)
            put(relPathKey, relPath)
            put(pendingKey, 1)
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { fos ->
            FileInputStream(cacheFile).use { fis ->
                fis.copyTo(fos)
            }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(pendingKey, 0) }, null, null)
        Log.d(TAG, "exportFile $originalName -> $uri location=$saveLocation")
        return ExportResult(uri, saveLocation)
    }
}