package com.monib.receivercontrol.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var notificationPermission: ActivityResultLauncher<String>
    private var serverOn by androidx.compose.runtime.mutableStateOf(false)
    private var language by androidx.compose.runtime.mutableStateOf("ar")
    private var theme by androidx.compose.runtime.mutableStateOf("purple")
    private var files by androidx.compose.runtime.mutableStateOf<List<File>>(emptyList())
    private var pairingCode by androidx.compose.runtime.mutableStateOf("")
    private var receiverIp by androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(LocalReceiverService.PREFS, MODE_PRIVATE)
        language = preferences.getString("language", "ar") ?: "ar"
        theme = preferences.getString("theme", "purple") ?: "purple"
        pairingCode = ReceiverPairing.getOrCreateCode(this)
        receiverIp = ReceiverPairing.lanIpv4Address()
        refreshFiles()
        notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startReceiverServer()
        }
        setContent {
            ReceiverDashboard(
                language = language,
                theme = theme,
                serverOn = serverOn,
                pairingCode = pairingCode,
                receiverIp = receiverIp,
                files = files,
                onToggleServer = ::toggleServer,
                onAccessibility = { ReceiverAccessibilityService.openSettings(this) },
                onRefreshFiles = ::refreshFiles,
                onOpenFile = ::openFile,
                onShareFile = ::shareFile,
                onLanguage = {
                    language = it
                    preferences.edit().putString("language", it).apply()
                },
                onTheme = {
                    theme = it
                    preferences.edit().putString("theme", it).apply()
                },
                onRefreshAddress = { receiverIp = ReceiverPairing.lanIpv4Address() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serverOn = LocalReceiverService.running
        pairingCode = ReceiverPairing.getOrCreateCode(this)
        receiverIp = ReceiverPairing.lanIpv4Address()
        refreshFiles()
    }

    private fun toggleServer() {
        if (serverOn) {
            LocalReceiverService.stop(this)
            serverOn = false
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startReceiverServer()
    }

    private fun startReceiverServer() {
        LocalReceiverService.start(this)
        serverOn = true
        pairingCode = ReceiverPairing.getOrCreateCode(this)
    }

    private fun refreshFiles() {
        val folder = File(getExternalFilesDir("Download") ?: filesDir, "ReceiverControl")
        files = folder.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()
    }

    private fun openFile(file: File) {
        val intent = Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, file.absolutePath)
        startActivity(intent)
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val mime = contentResolver.getType(uri) ?: mimeFor(file)
            val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, if (language == "ar") "مشاركة الملف" else "Share file"))
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}
