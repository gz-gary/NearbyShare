package com.example.nearbyshare.services

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore.Files.FileColumns
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.nearbyshare.FileCacheLine
import com.example.nearbyshare.FileHeader
import com.example.nearbyshare.FileType
import com.example.nearbyshare.HelloRequest
import com.example.nearbyshare.HelloResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

sealed class TransferProgress {
    data class SendPreparing(val stage: String = "") : TransferProgress()
    data class ReceivePreparing(val stage: String = "") : TransferProgress()
    data class ReceiveInProgress(
        val fileIndex: Int,
        val fileName: String,
        val receivedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : TransferProgress()
    data class ReceiveDone(
        val fileCount: Int,
        val totalBytes: Long,
        val elapsedMs: Long,
        val avgSpeedBytesPerSec: Long,
        val saveLocation: String
    ) : TransferProgress()
    data class ReceiveError(val message: String) : TransferProgress()
    data class SendInProgress(
        val fileIndex: Int,
        val fileName: String,
        val sentBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : TransferProgress()
    data class SendDone(
        val fileCount: Int,
        val totalBytes: Long,
        val elapsedMs: Long,
        val avgSpeedBytesPerSec: Long
    ) : TransferProgress()
    data class SendError(val message: String) : TransferProgress()
}

class TransferService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "TransferService"
        private const val CACHE_DIR = "file_cache"
    }

    private val storageService by lazy {
        ServiceRegistry.getService(StorageService::class.java)
    }
    private val exportService by lazy {
        ServiceRegistry.getService(ExportService::class.java)
    }
    private val statisticsService by lazy {
        ServiceRegistry.getService(StatisticsService::class.java)
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _progressFlow = MutableSharedFlow<TransferProgress>(
        extraBufferCapacity = 64
    )
    val progressFlow: SharedFlow<TransferProgress> = _progressFlow.asSharedFlow()

    val serverPort: Int
        get() = serverSocket?.localPort ?: 0

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startServer() {
        serverSocket = ServerSocket(0)
        Log.d(TAG, "startServer serverPort=$serverPort")
        serverJob = ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (coroutineContext.isActive) {
                    val clientSocket = serverSocket?.accept()
                    clientSocket?.let { socket ->
                        handleClientConnection(socket)
                    }
                    yield()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ServerSocket error: ${e.message}")
            }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        Log.d(TAG, "stopServer done")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        _progressFlow.tryEmit(TransferProgress.ReceivePreparing("传输即将开始..."))
        
        try {
            val dataInput = DataInputStream(socket.getInputStream())
            val dataOutput = DataOutputStream(socket.getOutputStream())

            val helloRequest = HelloRequest.parseFrom(dataInput.readProtoBytes())
            Log.d(TAG, "handleClientConnection fileCount=${helloRequest.fileCount} fingerprints=${helloRequest.fingerprintsList}")

            val fileCacheMap = mutableMapOf<String, FileCacheLine>()
            for (fingerprint in helloRequest.fingerprintsList) {
                storageService.getFileCacheLine(fingerprint)?.let { line ->
                    fileCacheMap[fingerprint] = line
                }
            }

            val helloResponse = HelloResponse.newBuilder()
                .putAllFileCacheMap(fileCacheMap)
                .build()
            dataOutput.writeProtoBytes(helloResponse.toByteArray())
            Log.d(TAG, "handleClientConnection helloResponse size=${fileCacheMap.size}")

            val cacheDir = File(context.cacheDir, CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            var totalBytesAll = 0L
            val startTime = SystemClock.elapsedRealtime()
            val statisticsId = statisticsService.createStatistics()
            val receivedFiles = mutableListOf<ReceivedFile>()

            for (i in 0 until helloRequest.fileCount) {
                val header = FileHeader.parseFrom(dataInput.readProtoBytes())
                val totalBytes = header.totalBytes
                val remainingBytes = header.size
                val fileName = header.name.ifBlank { "file_${System.currentTimeMillis()}_$i" }
                val fingerprint = header.fingerprint
                Log.d(TAG, "handleClientConnection i=$i type=${header.type} totalBytes=$totalBytes remainingBytes=$remainingBytes name=$fileName fingerprint=$fingerprint")

                val existingCache = fileCacheMap[fingerprint]
                val existingCachedBytes = existingCache?.cachedBytes ?: 0L
                val cacheFile = if (existingCache?.hasCachePath() == true) {
                    File(existingCache.cachePath)
                } else {
                    File(cacheDir, "${System.currentTimeMillis()}_$i")
                }

                if (!cacheFile.exists()) {
                    cacheFile.createNewFile()
                } else if (existingCachedBytes > 0 && cacheFile.length() > existingCachedBytes) {
                    RandomAccessFile(cacheFile, "rw").use { raf ->
                        raf.setLength(existingCachedBytes)
                    }
                }

                storageService.putFileCacheLine(
                    fingerprint,
                    FileCacheLine.newBuilder()
                        .setCachedBytes(existingCachedBytes)
                        .setTotalBytes(totalBytes)
                        .setCachePath(cacheFile.absolutePath)
                        .build()
                )

                val chunkSize = 65536
                val chunk = ByteArray(chunkSize)
                var remaining = remainingBytes
                var fileReceivedBytes = existingCachedBytes
                var lastReportTime = SystemClock.elapsedRealtime()
                var lastReportBytes = existingCachedBytes
                var lastWriteDbTime = lastReportTime

                FileOutputStream(cacheFile, true).use { fos ->
                    while (remaining > 0) {
                        val toRead = minOf(chunkSize.toLong(), remaining).toInt()
                        val bytesRead = dataInput.read(chunk, 0, toRead)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            fos.write(chunk, 0, bytesRead)
                            remaining -= bytesRead
                            totalBytesAll += bytesRead
                            fileReceivedBytes += bytesRead
                            val now = SystemClock.elapsedRealtime()
                            val elapsed = now - lastReportTime
                            if (elapsed >= 200) {
                                val speed = ((fileReceivedBytes - lastReportBytes) * 1000L) / elapsed
                                _progressFlow.tryEmit(
                                    TransferProgress.ReceiveInProgress(i, fileName, fileReceivedBytes, totalBytes, speed)
                                )
                                statisticsService.writeStatistics(statisticsId, lastReportTime, now, fileReceivedBytes - lastReportBytes)
                                lastReportTime = now
                                lastReportBytes = fileReceivedBytes
                            }
                            val elapsedSinceWriteDb = now - lastWriteDbTime
                            if (elapsedSinceWriteDb >= 500) {
                                storageService.updateFileCacheLine(fingerprint, fileReceivedBytes)
                                lastWriteDbTime = now
                            }
                        }
                    }
                }

                val finalNow = SystemClock.elapsedRealtime()
                val finalElapsed = finalNow - lastReportTime
                if (finalElapsed > 0 && fileReceivedBytes > lastReportBytes) {
                    val speed = ((fileReceivedBytes - lastReportBytes) * 1000L) / finalElapsed
                    _progressFlow.tryEmit(
                        TransferProgress.ReceiveInProgress(i, fileName, fileReceivedBytes, totalBytes, speed)
                    )
                    statisticsService.writeStatistics(statisticsId, lastReportTime, finalNow, fileReceivedBytes - lastReportBytes)
                }

                storageService.updateFileCacheLine(fingerprint, fileReceivedBytes)

                val mimeType = when (header.type) {
                    FileType.IMAGE -> "image/*"
                    FileType.VIDEO -> "video/*"
                    else -> "application/octet-stream"
                }
                receivedFiles.add(ReceivedFile(cacheFile, fileName, mimeType))
            }

            // 导出过程相当耗时。而且此处的平均速度应该只包括网络传输+首次落盘的速度
            val totalElapsed = SystemClock.elapsedRealtime() - startTime

            val exportPaths = mutableSetOf<String>()
            for (received in receivedFiles) {
                val exportResult = exportService.exportFile(received.cacheFile, received.fileName, received.mimeType)
                    ?: throw Exception("exportFile failed for ${received.fileName}")
                Log.d(TAG, "handleClientConnection exportFile ${received.fileName} uri=${exportResult.uri}")
                exportPaths.add(exportResult.saveLocation)
            }

            for (fingerprint in helloRequest.fingerprintsList) {
                storageService.deleteFileCacheLine(fingerprint)
            }
            cacheDir.deleteRecursively()

            val avgSpeed = if (totalElapsed > 0) (totalBytesAll * 1000L) / totalElapsed else totalBytesAll
            val saveLocation = exportPaths.joinToString(", ")
            _progressFlow.tryEmit(
                TransferProgress.ReceiveDone(helloRequest.fileCount, totalBytesAll, totalElapsed, avgSpeed, saveLocation)
            )

            statisticsService.saveStatistics(statisticsId)

            dataInput.read()

        } catch (e: Exception) {
            Log.e(TAG, "handleClientConnection error ${e.message}")
            _progressFlow.tryEmit(TransferProgress.ReceiveError(e.message ?: "Unknown error"))
        } finally {
            socket.close()
            Log.d(TAG, "handleClientConnection closed")
        }
    }

    suspend fun sendFiles(targetAddress: String, targetPort: Int, uris: List<Uri>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _progressFlow.tryEmit(TransferProgress.SendPreparing("正在获取文件信息..."))

            val entries = uris.mapNotNull { uri ->
                val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return@mapNotNull null
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val fileType = when {
                    mimeType.startsWith("image/") -> FileType.IMAGE
                    mimeType.startsWith("video/") -> FileType.VIDEO
                    else -> FileType.DOCUMENT
                }
                var fileName = ""
                var modifyTime = 0L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, FileColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        fileName = cursor.getString(0) ?: ""
                        modifyTime = cursor.getLong(1)
                    }
                }
                val fingerprint = generateFingerprint(fileName, size, modifyTime)
                FileEntry(uri, size, fileType, fileName, fingerprint)
            }

            val helloRequest = HelloRequest.newBuilder()
                .setFileCount(entries.size)
                .setTotalBytes(entries.sumOf { it.size })
                .addAllFingerprints(entries.map { it.fingerprint })
                .build()

            val startTime = SystemClock.elapsedRealtime()
            val chunkSize = 65536

            Socket(targetAddress, targetPort).use { socket ->
                _progressFlow.tryEmit(TransferProgress.SendPreparing("传输即将开始..."))

                val dataOutput = DataOutputStream(socket.getOutputStream())
                val dataInput = DataInputStream(socket.getInputStream())

                dataOutput.writeProtoBytes(helloRequest.toByteArray())
                Log.d(TAG, "sendFiles helloRequest sent")

                val helloResponse = HelloResponse.parseFrom(dataInput.readProtoBytes())
                val cacheMap = helloResponse.fileCacheMapMap
                Log.d(TAG, "sendFiles helloResponse cacheMap=${cacheMap.size}")

                val entriesToSend = entries.map { entry ->
                    val cacheLine = cacheMap[entry.fingerprint]
                    val cachedBytes = cacheLine?.cachedBytes ?: 0L
                    val remainingBytes = entry.size - cachedBytes
                    Log.d(TAG, "sendFiles ${entry.name} cached=$cachedBytes remaining=$remainingBytes")
                    SendEntry(entry.uri, remainingBytes, entry.type, entry.name, entry.fingerprint, cachedBytes)
                }

                for ((index, sendEntry) in entriesToSend.withIndex()) {
                    val header = FileHeader.newBuilder()
                        .setType(sendEntry.type)
                        .setSize(sendEntry.remainingBytes)
                        .setName(sendEntry.name)
                        .setFingerprint(sendEntry.fingerprint)
                        .setTotalBytes(sendEntry.totalBytes)
                        .build()

                    dataOutput.writeProtoBytes(header.toByteArray())

                    var fileSentBytes = sendEntry.cachedBytes
                    var lastReportTime = SystemClock.elapsedRealtime()
                    var lastReportBytes = sendEntry.cachedBytes
                    context.contentResolver.openInputStream(sendEntry.uri)?.use { fis ->
                        if (sendEntry.cachedBytes > 0) {
                            fis.skip(sendEntry.cachedBytes)
                        }
                        val chunk = ByteArray(chunkSize)
                        var bytesRead: Int
                        while (fis.read(chunk).also { bytesRead = it } != -1) {
                            dataOutput.write(chunk, 0, bytesRead)
                            fileSentBytes += bytesRead

                            val now = SystemClock.elapsedRealtime()
                            val elapsed = now - lastReportTime
                            if (elapsed >= 200) {
                                val speed = ((fileSentBytes - lastReportBytes) * 1000L) / elapsed
                                _progressFlow.tryEmit(
                                    TransferProgress.SendInProgress(index, sendEntry.name, fileSentBytes, sendEntry.totalBytes, speed)
                                )
                                lastReportTime = now
                                lastReportBytes = fileSentBytes
                            }
                        }
                    }
                }
                dataOutput.flush()

                val totalBytesToSend = entriesToSend.sumOf { it.remainingBytes }
                val totalElapsed = SystemClock.elapsedRealtime() - startTime
                val avgSpeed = if (totalElapsed > 0) (totalBytesToSend * 1000L) / totalElapsed else totalBytesToSend
                Log.d(TAG, "sendFiles done totalBytesToSend=$totalBytesToSend elapsed=$totalElapsed")

                _progressFlow.tryEmit(TransferProgress.SendDone(entries.size, totalBytesToSend, totalElapsed, avgSpeed))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFiles error ${e.message}", e)
            _progressFlow.tryEmit(TransferProgress.SendError(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    private fun generateFingerprint(fileName: String, size: Long, modifyTime: Long): String {
        return "$fileName-$size-$modifyTime"
    }




    private data class FileEntry(
        val uri: Uri,
        val size: Long,
        val type: FileType,
        val name: String,
        val fingerprint: String
    )

    private data class ReceivedFile(
        val cacheFile: File,
        val fileName: String,
        val mimeType: String
    )

    private data class SendEntry(
        val uri: Uri,
        val remainingBytes: Long,
        val type: FileType,
        val name: String,
        val fingerprint: String,
        val cachedBytes: Long
    ) {
        val totalBytes: Long get() = remainingBytes + cachedBytes
    }
}

fun DataInputStream.readProtoBytes(): ByteArray {
    return ByteArray(readInt()).also {
        readFully(it)
    }
}

fun DataOutputStream.writeProtoBytes(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
    flush()
}
