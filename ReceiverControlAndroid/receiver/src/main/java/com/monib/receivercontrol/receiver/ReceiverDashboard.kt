package com.monib.receivercontrol.receiver

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun ReceiverDashboard(
    language: String,
    theme: String,
    serverOn: Boolean,
    pairingCode: String,
    receiverIp: String?,
    files: List<File>,
    onToggleServer: () -> Unit,
    onAccessibility: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onLanguage: (String) -> Unit,
    onTheme: (String) -> Unit,
    onRefreshAddress: () -> Unit
) {
    val ar = language == "ar"
    val colors = if (theme == "white") lightColorScheme(primary = Color(0xFF8064DB), background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF5F3FA), onSurface = Color(0xFF17151C), onSurfaceVariant = Color(0xFF5D5966))
    else darkColorScheme(primary = Color(0xFFB7A3FF), background = Color(0xFF111117), surface = Color(0xFF1D1C25), surfaceVariant = Color(0xFF2A2935), onSurface = Color(0xFFF0EFF7), onSurfaceVariant = Color(0xFFC5C1D2))
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = colors) {
            varPage(language, theme, serverOn, pairingCode, receiverIp, files, onToggleServer, onAccessibility, onRefreshFiles, onOpenFile, onShareFile, onLanguage, onTheme, onRefreshAddress)
        }
    }
}

@Composable
private fun varPage(
    language: String,
    theme: String,
    serverOn: Boolean,
    pairingCode: String,
    receiverIp: String?,
    files: List<File>,
    onToggleServer: () -> Unit,
    onAccessibility: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onLanguage: (String) -> Unit,
    onTheme: (String) -> Unit,
    onRefreshAddress: () -> Unit
) {
    val ar = language == "ar"
    var tab by remember { androidx.compose.runtime.mutableStateOf("home") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Android TV responsiveness", style = MaterialTheme.typography.titleLarge)
                    Text(if (ar) "تطبيق الرسيفر" else "Receiver companion", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { tab = "home" }) { Text(if (ar) "الحالة" else "Status") }
                    TextButton(onClick = { tab = "files"; onRefreshFiles() }) { Text(if (ar) "ملفاتي" else "My files") }
                }
            }
            if (tab == "files") {
                FileManager(files, language, onRefreshFiles, onOpenFile, onShareFile)
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(10.dp), shape = CircleShape, color = if (serverOn) Color(0xFF55C98B) else Color(0xFF9B98A5)) {}
                                Spacer(Modifier.width(9.dp))
                                Text(if (serverOn) (if (ar) "الخادم يعمل" else "Server running") else (if (ar) "الخادم متوقف" else "Server stopped"), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(if (ar) "شغّل الخادم ليكتشف الهاتف هذا الرسيفر ويتصل به عبر Wi-Fi." else "Start the server so your phone can discover and connect to this receiver over Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onToggleServer, modifier = Modifier.fillMaxWidth()) {
                                Text(if (serverOn) (if (ar) "إيقاف الخادم" else "Stop server") else (if (ar) "تشغيل خادم الاتصال" else "Start connection server"))
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (ar) "اربط الهاتف بهذا الرمز" else "Pair your phone with this code", style = MaterialTheme.typography.titleMedium)
                            Text(pairingCode.chunked(1).joinToString(" "), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                            Text(if (receiverIp != null) "IP: $receiverIp · Port ${LocalReceiverService.COMMAND_PORT}" else if (ar) "اتصل بشبكة Wi-Fi لعرض IP ورمز QR" else "Connect to Wi-Fi to show the IP and QR code", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val payload = remember(receiverIp, pairingCode) { ReceiverPairing.qrPayloadFrom(receiverIp, pairingCode) }
                            val qr = remember(payload) { payload?.let { ReceiverPairing.qrBitmap(it) } }
                            if (qr != null) Image(qr.asImageBitmap(), contentDescription = "Receiver pairing QR", modifier = Modifier.size(220.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(5.dp))
                            OutlinedButton(onClick = onRefreshAddress) { Text(if (ar) "تحديث عنوان الشبكة" else "Refresh network address") }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (ar) "التحكم بالأزرار" else "System button controls", style = MaterialTheme.typography.titleMedium)
                            Text(if (ar) "لأزرار الرئيسية والرجوع وقفل الشاشة، فعّل خدمة التحكم المساعد. التحكم بالصوت يعمل دونها." else "Enable the accessibility service for Home, Back, and screen lock. Volume controls work without it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) { Text(if (ar) "إعداد خدمة التحكم المساعد" else "Configure accessibility control") }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (ar) "المظهر واللغة" else "Appearance and language", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = theme == "purple", onClick = { onTheme("purple") }, label = { Text(if (ar) "بنفسجي رمادي" else "Purple gray") })
                                FilterChip(selected = theme == "white", onClick = { onTheme("white") }, label = { Text(if (ar) "أبيض" else "White") })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = ar, onClick = { onLanguage("ar") }, label = { Text("العربية") })
                                FilterChip(selected = !ar, onClick = { onLanguage("en") }, label = { Text("English") })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileManager(files: List<File>, language: String, onRefresh: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit) {
    val ar = language == "ar"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (ar) "مدير الملفات" else "File manager", style = MaterialTheme.typography.titleLarge)
                Text(if (ar) "الملفات المستلمة من هاتفك" else "Files received from your phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRefresh) { Text(if (ar) "تحديث" else "Refresh") }
        }
        Divider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { file ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall)
                        Text("${formatFileSize(file.length())} · ${file.extension.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onOpen(file) }, modifier = Modifier.weight(1f)) { Text(if (ar) "فتح" else "Open") }
                            TextButton(onClick = { onShare(file) }, modifier = Modifier.weight(1f)) { Text(if (ar) "مشاركة" else "Share") }
                        }
                    }
                }
            }
            if (files.isEmpty()) Text(if (ar) "لا توجد ملفات مستلمة بعد." else "No received files yet.", modifier = Modifier.padding(top = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(bytes / 1024.0)
}
