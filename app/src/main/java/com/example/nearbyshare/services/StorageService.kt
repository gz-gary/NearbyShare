package com.example.nearbyshare.services

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.example.nearbyshare.FileCache
import com.example.nearbyshare.FileCacheLine
import com.example.nearbyshare.LastSelectedFiles
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream
import androidx.core.net.toUri

object FileCacheSerializer : Serializer<FileCache> {
    override val defaultValue: FileCache = FileCache.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): FileCache {
        try {
            return FileCache.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: FileCache, output: OutputStream) {
        t.writeTo(output)
    }
}

object LastSelectedFilesSerializer : Serializer<LastSelectedFiles> {
    override val defaultValue: LastSelectedFiles = LastSelectedFiles.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): LastSelectedFiles {
        try {
            return LastSelectedFiles.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: LastSelectedFiles, output: OutputStream) {
        t.writeTo(output)
    }
}

val Context.fileCacheDataStore: DataStore<FileCache> by dataStore(
    fileName = "file_cache.pb",
    serializer = FileCacheSerializer
)

val Context.lastSelectedFilesDataStore: DataStore<LastSelectedFiles> by dataStore(
    fileName = "last_selected_files.pb",
    serializer = LastSelectedFilesSerializer
)

class StorageService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "StorageService"
    }

    private val dataStore: DataStore<FileCache>
        get() = context.fileCacheDataStore

    private val lastSelectedFilesDataStore: DataStore<LastSelectedFiles>
        get() = context.lastSelectedFilesDataStore

    suspend fun getFileCacheLine(key: String): FileCacheLine? {
        Log.d(TAG, "get key=$key")
        return dataStore.data.first().fileCacheMapMap[key]
    }

    suspend fun putFileCacheLine(key: String, line: FileCacheLine) {
        Log.d(TAG, "put key=$key cachedBytes=${line.cachedBytes} totalBytes=${line.totalBytes}")
        dataStore.updateData { currentCache ->
            currentCache.toBuilder()
                .putFileCacheMap(key, line)
                .build()
        }
    }

    suspend fun deleteFileCacheLine(key: String) {
        Log.d(TAG, "delete key=$key")
        dataStore.updateData { currentCache ->
            currentCache.toBuilder()
                .removeFileCacheMap(key)
                .build()
        }
    }

    suspend fun updateFileCacheLine(key: String, cachedBytes: Long) {
        Log.d(TAG, "updateCachedBytes key=$key cachedBytes=$cachedBytes")
        dataStore.updateData { currentCache ->
            val existingLine = currentCache.fileCacheMapMap[key]
            existingLine?.let {
                val newLine = it.toBuilder().setCachedBytes(cachedBytes).build()
                currentCache.toBuilder()
                    .putFileCacheMap(key, newLine)
                    .build()
            } ?: currentCache
        }
    }

    suspend fun putLastSelectedFiles(uris: List<Uri>) {
        Log.d(TAG, "putLastSelectedFiles uris=${uris.size}")
        lastSelectedFilesDataStore.updateData {
            LastSelectedFiles.newBuilder().addAllUris(uris.map { it.toString() }).build()
        }
    }

    suspend fun getLastSelectedFiles(): List<Uri> {
        Log.d(TAG, "getLastSelectedFiles")
        return lastSelectedFilesDataStore.data.first().urisList.map { it.toUri() }
    }

    suspend fun deleteLastSelectedFiles() {
        Log.d(TAG, "deleteLastSelectedFiles")
        lastSelectedFilesDataStore.updateData {
            LastSelectedFiles.newBuilder().clearUris().build()
        }
    }
}
