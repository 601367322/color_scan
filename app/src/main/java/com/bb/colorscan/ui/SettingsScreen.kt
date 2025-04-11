package com.bb.colorscan.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bb.colorscan.MainActivity
import com.bb.colorscan.viewmodel.SettingsViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 设置屏幕UI组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val monitorAudioPath by viewModel.monitorAudioPath.collectAsState()
    val countdownAudioPath by viewModel.countdownAudioPath.collectAsState()
    val countdownDuration by viewModel.countdownDuration.collectAsState()
    val targetRGB by viewModel.targetRgb.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val showOverlayPermissionDialog by viewModel.showOverlayPermissionDialog.collectAsState()
    
    val context = LocalContext.current
    
    // 文件选择器 - 监控音频
    val monitorAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将音频文件复制到应用私有目录
            val localPath = copyAudioFileToLocal(context, it, "monitor_audio")
            if (localPath != null) {
                viewModel.updateMonitorAudioPath(localPath)
                viewModel.saveMonitorAudioSettings() // 自动保存
                Toast.makeText(context, "已选择并保存音频文件", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存音频文件失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 文件选择器 - 倒计时音频
    val countdownAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将音频文件复制到应用私有目录
            val localPath = copyAudioFileToLocal(context, it, "countdown_audio")
            if (localPath != null) {
                viewModel.updateCountdownAudioPath(localPath)
                viewModel.saveCountdownAudioSettings() // 自动保存
                Toast.makeText(context, "已选择并保存音频文件", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存音频文件失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 权限请求对话框
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOverlayPermissionDialog() },
            title = { Text("需要权限") },
            text = { Text("颜色扫描功能需要\"在其他应用上显示\"的权限才能正常工作。请在设置中授予此权限。") },
            confirmButton = {
                Button(
                    onClick = { viewModel.requestOverlayPermission() }
                ) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissOverlayPermissionDialog() }
                ) {
                    Text("取消")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("颜色扫描设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // 录屏开关
            RecordingToggleCard(
                isRecording = isRecording,
                onToggleRecording = { shouldRecord -> 
                    if (shouldRecord) {
                        // 通过Activity启动录屏
                        (context as? MainActivity)?.checkPermissionAndStartCapture()
                    } else {
                        // 通过ViewModel直接停止服务
                        viewModel.stopScreenCapture()
                    }
                }
            )
            
            // 设置项标题
            Text(
                text = "监控设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // RGB颜色设置
            SettingItemAutoSave(
                title = "监控颜色RGB",
                value = targetRGB,
                onValueChange = { 
                    viewModel.updateTargetRgb(it)
                    viewModel.saveTargetRgbSettings()  // 自动保存
                },
                label = "RGB值 (例如: 255, 0, 0)"
            )
            
            // 监控音频设置
            AudioSettingItemAutoSave(
                title = "监控提示音频",
                value = monitorAudioPath,
                onValueChange = { 
                    viewModel.updateMonitorAudioPath(it)
                    viewModel.saveMonitorAudioSettings()  // 自动保存
                },
                label = "音频文件路径",
                onBrowse = { monitorAudioLauncher.launch("audio/*") }
            )
            
            // 倒计时设置标题
            Text(
                text = "倒计时设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // 倒计时时长设置
            SettingItemAutoSave(
                title = "倒计时时长",
                value = countdownDuration,
                onValueChange = { 
                    viewModel.updateCountdownDuration(it)
                    viewModel.saveCountdownDurationSettings()  // 自动保存
                },
                label = "分钟"
            )
            
            // 倒计时音频设置
            AudioSettingItemAutoSave(
                title = "倒计时提示音频",
                value = countdownAudioPath,
                onValueChange = { 
                    viewModel.updateCountdownAudioPath(it)
                    viewModel.saveCountdownAudioSettings()  // 自动保存
                },
                label = "音频文件路径",
                onBrowse = { countdownAudioLauncher.launch("audio/*") }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun RecordingToggleCard(
    isRecording: Boolean,
    onToggleRecording: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "屏幕颜色监控",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isRecording) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isRecording) "正在监控中" else "点击开关开始监控",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRecording) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = isRecording,
                onCheckedChange = onToggleRecording,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun SettingItemAutoSave(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 只显示标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 输入框
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
fun AudioSettingItemAutoSave(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onBrowse: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 只显示标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 输入框和浏览按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                
                // 浏览按钮
                FilledIconButton(
                    onClick = onBrowse,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "选择音频文件"
                    )
                }
            }
        }
    }
}

/**
 * 将音频文件从Uri复制到应用私有目录
 * @param context 上下文
 * @param uri 音频文件的Uri
 * @param prefix 文件名前缀
 * @return 本地文件路径，如果失败则返回null
 */
private fun copyAudioFileToLocal(context: Context, uri: Uri, prefix: String): String? {
    try {
        // 创建应用私有目录中的audio文件夹
        val audioDir = File(context.filesDir, "audio")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
        
        // 生成唯一的文件名
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = getFileExtensionFromUri(context, uri)
        val fileName = "${prefix}_${timestamp}.${extension}"
        val localFile = File(audioDir, fileName)
        
        // 复制文件
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(localFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        
        // 返回本地文件路径
        return localFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

/**
 * 从Uri获取文件扩展名
 */
private fun getFileExtensionFromUri(context: Context, uri: Uri): String {
    val mimeType = context.contentResolver.getType(uri)
    return when (mimeType) {
        "audio/mpeg" -> "mp3"
        "audio/wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/aac" -> "aac"
        else -> "mp3" // 默认扩展名
    }
} 