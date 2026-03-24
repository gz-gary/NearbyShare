package com.example.nearbyshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nearbyshare.services.SettingsService

val COLOR_VALUES = mapOf(
    SettingsService.COLOR_YELLOW to Color(0xFFFFEB3B),
    SettingsService.COLOR_GREEN to Color(0xFF4CAF50),
    SettingsService.COLOR_PURPLE to Color(0xFF9C27B0),
    SettingsService.COLOR_ORANGE to Color(0xFFFF9800),
    SettingsService.COLOR_BLUE to Color(0xFF2196F3)
)

@Composable
fun ColorCircle(
    color: Int?,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val colorValue = color?.let { COLOR_VALUES[it] } ?: return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colorValue)
    )
}

@Composable
fun ColorCircleSelector(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        COLOR_VALUES.forEach { (colorValue, _) ->
            val isSelected = selectedColor == colorValue
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(colorValue) },
                contentAlignment = Alignment.Center
            ) {
                ColorCircle(
                    color = colorValue,
                    size = 24.dp
                )
            }
        }
    }
}

@Composable
fun DeviceCard(
    name: String?,
    color: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (color != null) {
                ColorCircle(color = color, size = 24.dp)
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = name?.takeIf { it.isNotEmpty() } ?: "未知用户",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun UserBadge(
    name: String?,
    color: Int?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            ColorCircle(color = color, size = 20.dp)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = name?.takeIf { it.isNotEmpty() } ?: "未知用户",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
