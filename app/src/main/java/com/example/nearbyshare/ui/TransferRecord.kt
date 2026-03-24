package com.example.nearbyshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TransferRecord(
    val fileCount: Int,
    val totalBytes: Long,
    val avgSpeedBytesPerSec: Long,
    val endTime: Long
) {
    val endTimeFormatted: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(endTime))

    val totalSizeFormatted: String
        get() = formatBytes(totalBytes)

    val avgSpeedFormatted: String
        get() = "${"%.2f".format(avgSpeedBytesPerSec / 1_048_576.0)} MB/s"
}

@Composable
fun TransferRecordList(
    records: List<TransferRecord>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(records, key = { it.endTime }) { record ->
            TransferRecordItem(record = record)
        }
    }
}

@Composable
private fun TransferRecordItem(
    record: TransferRecord,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${record.fileCount} 个文件",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = record.endTimeFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = record.totalSizeFormatted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = record.avgSpeedFormatted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "${"%.2f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${"%.2f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1024 -> "${"%.2f".format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}
