package com.example.nearbyshare

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.nearbyshare.services.ServiceRegistry
import com.example.nearbyshare.services.SettingsService
import com.example.nearbyshare.services.StatisticsService
import com.example.nearbyshare.ui.ColorCircleSelector
import com.example.nearbyshare.ui.theme.NearbyShareTheme

class SettingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private val settingsService by lazy {
        ServiceRegistry.getService(SettingsService::class.java)
    }

    private val statisticsService by lazy {
        ServiceRegistry.getService(StatisticsService::class.java)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NearbyShareTheme {
                val options = listOf(
                    SettingsService.STRATEGY_AUTO to "自动连接首个发现的设备",
                    SettingsService.STRATEGY_MANUAL to "显示所有发现的设备，手动连接"
                )

                var selectedStrategy by remember {
                    mutableStateOf(settingsService.getConnectionStrategy())
                }

                var userName by remember {
                    mutableStateOf(settingsService.getUserName())
                }

                var userColor by remember {
                    mutableStateOf(settingsService.getUserColor())
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("设置") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "个人信息",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "名字",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(56.dp)
                            )
                            OutlinedTextField(
                                value = userName,
                                onValueChange = { newValue ->
                                    if (newValue.length <= SettingsService.MAX_NAME_LENGTH) {
                                        userName = newValue
                                        settingsService.setUserName(newValue)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("") }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "颜色",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(56.dp)
                            )
                            ColorCircleSelector(
                                selectedColor = userColor,
                                onColorSelected = { color ->
                                    userColor = color
                                    settingsService.setUserColor(color)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "发现设备后的连接策略",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(modifier = Modifier.selectableGroup()) {
                            options.forEach { (value, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .selectable(
                                            selected = (selectedStrategy == value),
                                            onClick = {
                                                selectedStrategy = value
                                                settingsService.setConnectionStrategy(value)
                                                Log.d(TAG, "strategy changed to $value")
                                            },
                                            role = Role.RadioButton
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (selectedStrategy == value),
                                        onClick = null
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "统计记录",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable {
                                    statisticsService.cleanStatistics()
                                    Toast.makeText(this@SettingsActivity, "统计记录已清空", Toast.LENGTH_SHORT).show()
                                    Log.d(TAG, "statistics cleared")
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "清空统计记录",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable {
                                    statisticsService.exportStatistics()
                                    Toast.makeText(this@SettingsActivity, "统计记录已导出", Toast.LENGTH_SHORT).show()
                                    Log.d(TAG, "statistics exported")
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "导出统计记录",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
