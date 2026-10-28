package com.monib.receivercontrol.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.monib.receivercontrol.controller.widgets.ReferenceDpadView
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun loc(language: String, arabic: String, english: String): String = if (language == "ar") arabic else english

private val Purple = Color(0xFF8064DB)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB7A3FF),
    onPrimary = Color(0xFF211345),
    secondary = Color(0xFFAC9BCB),
    background = Color(0xFF111117),
    surface = Color(0xFF1D1C25),
    surfaceVariant = Color(0xFF2A2935),
    onSurface = Color(0xFFF0EFF7),
    onSurfaceVariant = Color(0xFFC5C1D2),
    outline = Color(0xFF514E5D)
)
private val WhiteScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Color(0xFF65558F),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF5F3FA),
    onSurface = Color(0xFF17151C),
    onSurfaceVariant = Color(0xFF5D5966),
    outline = Color(0xFFE3E0EA)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverControlApp(
    state: ControllerState,
    onTab: (String) -> Unit,
    onDiscover: () -> Unit,
    onPairPrompt: (ReceiverDevice) -> Unit,
    onPair: (String, String) -> Unit,
    onPairingProtocol: (String) -> Unit,
    onStartAndroidTvPairing: (String) -> Unit,
    onQrScan: (String) -> Unit,
    onScannerVisibility: (Boolean) -> Unit,
    onManualHost: (String) -> Unit,
    onPairCodeInput: (String) -> Unit,
    onSendKey: (String) -> Unit,
    onAdbHost: (String) -> Unit,
    onAdbPort: (String) -> Unit,
    onAdbConnect: () -> Unit,
    onAdbCommand: (String) -> Unit,
    onRunAdbCommand: (String) -> Unit,
    onEditFavorite: (Int, String, String) -> Unit,
    onRunFavorite: (Int) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (ReceiverApp) -> Unit,
    onUninstallApp: (ReceiverApp) -> Unit,
    onSendFile: (android.net.Uri) -> Unit,
    onRefreshScreen: () -> Unit,
    onScreenLiveChange: (Boolean) -> Unit,
    onPip: () -> Unit,
    onWebsite: () -> Unit,
    onDismissWebsiteBanner: () -> Unit,
    onTheme: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onCustomJson: () -> Unit,
    onCustomJsonText: (String) -> Unit,
    onSelectProfile: (SavedReceiver) -> Unit,
    onDeleteProfile: (SavedReceiver) -> Unit,
    onSaveMacro: (String?, String, List<String>) -> Unit,
    onDeleteMacro: (String) -> Unit,
    onRunMacro: (RemoteMacro) -> Unit,
    onCastUrl: (String) -> Unit
) {
    val isArabic = state.language == "ar"
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onScannerVisibility(true)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onSendFile) }
    var uninstallTarget by remember { mutableStateOf<ReceiverApp?>(null) }
    val latestSuccess by rememberUpdatedState(state.success)
    LaunchedEffect(state.screenLive, state.paired?.host, state.tab, state.pictureInPicture) {
        if (!state.screenLive) return@LaunchedEffect
        if (state.tab != "media" || state.paired == null) {
            onScreenLiveChange(false)
            return@LaunchedEffect
        }
        while (true) {
            onRefreshScreen()
            delay(2500)
            if (latestSuccess == false) {
                onScreenLiveChange(false)
                break
            }
        }
    }

    MaterialTheme(colorScheme = if (state.theme == "white") WhiteScheme else DarkScheme) {
        CompositionDirection(isArabic) {
            if (state.pictureInPicture && state.screenPreview != null) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = state.screenPreview.asImageBitmap(),
                        contentDescription = loc(state.language, "شاشة الرسيفر", "Receiver screen"),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
            val selectedTab = when (state.tab) {
                "macros" -> "remote"
                "screen" -> "media"
                else -> state.tab
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text("Android remote", style = MaterialTheme.typography.titleLarge) },
                        actions = {
                            TextButton(onClick = { onTab("devices") }) {
                                Text(state.paired?.name ?: loc(state.language, "ربط", "Connect"), maxLines = 1)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf(
                            Triple("remote", "⌖", loc(state.language, "ريموت", "Remote")),
                            Triple("devices", "▣", loc(state.language, "أجهزة", "Devices")),
                            Triple("apps", "▦", loc(state.language, "تطبيقات", "Apps")),
                            Triple("media", "♫", loc(state.language, "وسائط", "Media")),
                            Triple("settings", "⚙", loc(state.language, "إعدادات", "Settings"))
                        ).forEach { (tab, glyph, label) ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { onTab(tab) },
                                icon = { Text(glyph, style = MaterialTheme.typography.titleMedium) },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                        if (!state.websiteBannerDismissed) {
                            Card(
                                onClick = onWebsite,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(loc(state.language, "موقع التطبيق", "App website"), style = MaterialTheme.typography.titleSmall)
                                        Text(loc(state.language, "الفكرة وطريقة التحميل", "About and downloads"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = onDismissWebsiteBanner) { Text("×", style = MaterialTheme.typography.titleLarge) }
                                }
                            }
                        }
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                val color = when (state.success) { true -> Color(0xFF55C98B); false -> Color(0xFFE47B7B); null -> MaterialTheme.colorScheme.primary }
                                Surface(Modifier.size(9.dp), color = color, shape = CircleShape) {}
                                Spacer(Modifier.width(10.dp))
                                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (state.tab) {
                                "devices" -> DevicesPage(state, onDiscover, onPairPrompt, onManualHost, onPairCodeInput, onPair, onPairingProtocol, onStartAndroidTvPairing, onSelectProfile, onDeleteProfile, {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) onScannerVisibility(true)
                                    else cameraPermission.launch(Manifest.permission.CAMERA)
                                })
                                "macros" -> MacrosPage(state, onSaveMacro, onDeleteMacro, onRunMacro)
                                "apps" -> AppsPage(state, onRefreshApps, onLaunchApp, { uninstallTarget = it })
                                "media" -> MediaPage(state, onRefreshScreen, onScreenLiveChange, onPip, onCastUrl) { openFile.launch(arrayOf("*/*")) }
                                "screen" -> ScreenPage(state, onRefreshScreen, onPip)
                                "settings" -> SettingsPage(state, onTheme, onLanguage, onAdbHost, onAdbPort, onAdbConnect, onAdbCommand, onRunAdbCommand, onEditFavorite, onRunFavorite, onCustomJson, onCustomJsonText)
                                else -> RemotePage(state, onSendKey, onTab, onLaunchApp)
                            }
                        }
                    }
                }
            if (state.showQrScanner) QrScannerDialog(state.language, onDismiss = { onScannerVisibility(false) }, onScanned = onQrScan)
            uninstallTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { uninstallTarget = null },
                    title = { Text(loc(state.language, "إلغاء تثبيت التطبيق؟", "Uninstall app?")) },
                    text = { Text("${loc(state.language, "سيُطلب تأكيد الحذف على الرسيفر:", "The receiver will ask you to confirm removal:")}\n${target.packageName}") },
                    confirmButton = { TextButton(onClick = { onUninstallApp(target); uninstallTarget = null }) { Text(loc(state.language, "حذف", "Uninstall")) } },
                    dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text(loc(state.language, "إلغاء", "Cancel")) } }
                )
            }
            }
        }
    }
}

@Composable
private fun CompositionDirection(isArabic: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides if (isArabic) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
        content = content
    )
}

@Composable
private fun RemotePage(
    state: ControllerState,
    onSendKey: (String) -> Unit,
    onTab: (String) -> Unit,
    onLaunchApp: (ReceiverApp) -> Unit
) {
    val ar = state.language
    var mode by rememberSaveable { mutableStateOf("touchpad") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.apps.isEmpty()) {
                AssistChip(onClick = { onTab("apps") }, label = { Text(loc(ar, "تطبيقات سريعة", "Quick apps")) })
            } else {
                state.apps.take(8).forEach { app ->
                    AssistChip(onClick = { onLaunchApp(app) }, label = { Text(app.name, maxLines = 1) })
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().height(310.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(30.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    FilterChip(selected = mode == "touchpad", onClick = { mode = "touchpad" }, label = { Text(loc(ar, "لوحة لمس", "Touchpad")) })
                    FilterChip(selected = mode == "dpad", onClick = { mode = "dpad" }, label = { Text("D-Pad") })
                    FilterChip(selected = mode == "numbers", onClick = { mode = "numbers" }, label = { Text(loc(ar, "أرقام", "Number pad")) })
                }
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    when (mode) {
                        "dpad" -> ReferenceDpad(onSendKey)
                        "numbers" -> NumberPadContent(ar, onSendKey)
                        else -> TouchPadContent(ar, onSendKey)
                    }
                }
            }
        }

        RemoteControlCluster(ar, onSendKey) { mode = "numbers" }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = { onTab("macros") }, modifier = Modifier.weight(1f)) {
                Text(loc(ar, "اختصارات", "Shortcuts"))
            }
            OutlinedButton(onClick = { onTab("screen") }, modifier = Modifier.weight(1f)) {
                Text(loc(ar, "معاينة الشاشة", "Screen preview"))
            }
        }
    }
}

@Composable
private fun RemoteControlCluster(language: String, onSendKey: (String) -> Unit, onShowNumbers: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        VerticalRemotePill(loc(language, "صوت", "VOL"), "+", "VOLUME_UP", "−", "VOLUME_DOWN", onSendKey)
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteActionKey("▦") { onSendKey("RECENTS") }
                RemoteKey("⌂", "HOME", onSendKey, compact = true, modifier = Modifier.size(52.dp))
                RemoteActionKey("123", onShowNumbers)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteKey("◖", "MUTE", onSendKey, compact = true, modifier = Modifier.size(52.dp))
                RemoteKey("⏻", "POWER", onSendKey, compact = true, tint = Color(0xFFD95362), modifier = Modifier.size(56.dp))
                RemoteKey("↶", "BACK", onSendKey, compact = true, modifier = Modifier.size(52.dp))
            }
        }
        VerticalRemotePill(loc(language, "قناة", "CH"), "▲", "CHANNEL_UP", "▼", "CHANNEL_DOWN", onSendKey)
    }
}

@Composable
private fun VerticalRemotePill(
    title: String,
    topLabel: String,
    topKey: String,
    bottomLabel: String,
    bottomKey: String,
    onSendKey: (String) -> Unit
) {
    Card(
        Modifier.width(58.dp).height(174.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            RemoteActionKey(topLabel) { onSendKey(topKey) }
            Spacer(Modifier.weight(1f))
            RemoteActionKey(bottomLabel) { onSendKey(bottomKey) }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RemoteActionKey(label: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.52f, stiffness = 520f), label = "remote-action")
    val scope = rememberCoroutineScope()
    ElevatedButton(
        onClick = { pressed = true; onClick(); scope.launch { delay(110); pressed = false } },
        modifier = Modifier.size(52.dp).scale(scale),
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
        colors = androidx.compose.material3.ButtonDefaults.elevatedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
private fun ReferenceDpad(onSendKey: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val listener = remember(onSendKey) {
        object : ReferenceDpadView.DpadListener {
            override fun onUp() = onSendKey("UP")
            override fun onDown() = onSendKey("DOWN")
            override fun onLeft() = onSendKey("LEFT")
            override fun onRight() = onSendKey("RIGHT")
            override fun onCenter() = onSendKey("ENTER")
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ReferenceDpadView(context).apply {
                this.listener = listener
                setPalette(
                    colors.surface.toArgb(), colors.surfaceVariant.toArgb(), colors.onSurface.toArgb(),
                    colors.primary.toArgb(), colors.primaryContainer.toArgb(), colors.onPrimaryContainer.toArgb(),
                    colors.outline.toArgb()
                )
            }
        },
        update = { view ->
            view.listener = listener
            view.setPalette(
                colors.surface.toArgb(), colors.surfaceVariant.toArgb(), colors.onSurface.toArgb(),
                colors.primary.toArgb(), colors.primaryContainer.toArgb(), colors.onPrimaryContainer.toArgb(),
                colors.outline.toArgb()
            )
        }
    )
}

@Composable
private fun NumberPadContent(language: String, onSendKey: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    val key = when (label) {
                        "⌫" -> "DELETE"
                        "OK" -> "ENTER"
                        else -> "DIGIT_$label"
                    }
                    RemoteKey(label, key, onSendKey, large = true, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TouchPadContent(language: String, onSendKey: (String) -> Unit) {
    var dragDistance by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(23.dp))
            .pointerInput(onSendKey) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDragEnd = {
                        val dx = total.x; val dy = total.y
                        if (kotlin.math.abs(dx) < 36f && kotlin.math.abs(dy) < 36f) onSendKey("ENTER")
                        else if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) onSendKey(if (dx > 0) "RIGHT" else "LEFT")
                        else onSendKey(if (dy > 0) "DOWN" else "UP")
                        dragDistance = Offset.Zero
                    },
                    onDragCancel = { dragDistance = Offset.Zero },
                    onDrag = { change, amount -> change.consume(); total += amount; dragDistance = total }
                )
            }
            .pointerInput(onSendKey) { detectTapGestures(onTap = { onSendKey("ENTER") }) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("⌖", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                if (dragDistance == Offset.Zero) loc(language, "اسحب للتنقل · اضغط للاختيار", "Swipe to navigate · tap to select") else loc(language, "إرسال حركة اللمس", "Sending touch movement"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun RemoteKey(
    label: String,
    key: String,
    onSend: (String) -> Unit,
    compact: Boolean = false,
    selected: Boolean = false,
    large: Boolean = false,
    tint: Color? = null,
    modifier: Modifier? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.52f, stiffness = 520f), label = "remote-button")
    val scope = rememberCoroutineScope()
    val color = tint ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ElevatedButton(
        onClick = { pressed = true; onSend(key); scope.launch { delay(110); pressed = false } },
        modifier = Modifier.scale(scale).then(modifier ?: if (compact) Modifier.size(58.dp) else Modifier.width(95.dp).height(62.dp)),
        shape = if (compact) CircleShape else RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.ButtonDefaults.elevatedButtonColors(
            containerColor = color,
            contentColor = if (selected || tint != null) Color.White else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(label, style = when { large -> MaterialTheme.typography.titleMedium; compact -> MaterialTheme.typography.labelLarge; else -> MaterialTheme.typography.labelMedium }, maxLines = 1)
    }
}

@Composable
private fun DevicesPage(
    state: ControllerState,
    onDiscover: () -> Unit,
    onPairPrompt: (ReceiverDevice) -> Unit,
    onManualHost: (String) -> Unit,
    onCode: (String) -> Unit,
    onPair: (String, String) -> Unit,
    onPairingProtocol: (String) -> Unit,
    onStartAndroidTvPairing: (String) -> Unit,
    onSelectProfile: (SavedReceiver) -> Unit,
    onDeleteProfile: (SavedReceiver) -> Unit,
    onScan: () -> Unit
) {
    val ar = state.language
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDiscover, modifier = Modifier.weight(1f), enabled = !state.discovering) {
                if (state.discovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("⌕  ${loc(ar, "بحث Wi-Fi", "Search Wi-Fi")}")
            }
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("▦  ${loc(ar, "مسح QR", "Scan QR")}") }
        }
        Text(loc(ar, "يبحث عن أجهزة Android TV المتوافقة مباشرة، وعن تطبيق الرسيفر المرافق للميزات الإضافية.", "Finds compatible Android TVs directly and the companion receiver app for advanced features."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.paired?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(loc(ar, "مقترن", "Paired"), color = Color(0xFF55C98B))
                    Text("${it.name}  ·  ${it.host}")
                }
            }
        }
        if (state.profiles.isNotEmpty()) {
            Text(loc(ar, "ملفات الرسيفرات المحفوظة", "Saved receiver profiles"), style = MaterialTheme.typography.titleSmall)
            state.profiles.forEach { profile ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleSmall)
                            Text(profile.host, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onSelectProfile(profile) }) { Text(loc(ar, "تحديد", "Select")) }
                        TextButton(onClick = { onDeleteProfile(profile) }) { Text("×") }
                    }
                }
            }
        }
        state.devices.forEach { device ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text(device.host, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (device.protocol == ReceiverDevice.ANDROID_TV_REMOTE) loc(ar, "Android TV مباشر · لا يحتاج التطبيق المرافق", "Android TV direct · no companion app")
                            else loc(ar, "تطبيق الرسيفر · ميزات الملفات والتطبيقات", "Receiver app · files and apps"),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = { onPairPrompt(device) }) { Text(loc(ar, "استخدم", "Use")) }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loc(ar, "طريقة الاتصال", "Connection method"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.pairingProtocol == ReceiverDevice.ANDROID_TV_REMOTE,
                        onClick = { onPairingProtocol(ReceiverDevice.ANDROID_TV_REMOTE) },
                        label = { Text(loc(ar, "Android TV مباشر", "Android TV direct")) }
                    )
                    FilterChip(
                        selected = state.pairingProtocol == ReceiverDevice.RECEIVER_CONTROL,
                        onClick = { onPairingProtocol(ReceiverDevice.RECEIVER_CONTROL) },
                        label = { Text(loc(ar, "تطبيق الرسيفر", "Receiver app")) }
                    )
                }
                OutlinedTextField(value = state.pairingHost, onValueChange = onManualHost, label = { Text("IP") }, placeholder = { Text("192.168.1.20") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (state.pairingProtocol == ReceiverDevice.ANDROID_TV_REMOTE) {
                    Text(loc(ar, "لا يحتاج هذا المسار تثبيت تطبيق على التلفاز. اختر جهازاً مكتشفاً أو أدخل IP، ثم وافق على الاقتران في التلفاز.", "This path needs no TV app. Choose a discovered device or enter its IP, then approve pairing on the TV."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    val nativeServiceFound = state.devices.any { it.host == state.pairingHost && it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }
                    val companionServiceFound = state.devices.any { it.host == state.pairingHost && it.protocol == ReceiverDevice.RECEIVER_CONTROL }
                    if (state.pairingHost.isNotBlank() && companionServiceFound && !nativeServiceFound) {
                        Text(
                            loc(ar, "هذا الجهاز اكتُشف كتطبيق رسيفر فقط. استخدم «تطبيق الرسيفر»؛ الاتصال المباشر يحتاج خدمة Android TV Remote على الجهاز.", "This device was found as a receiver companion only. Use Receiver app; direct pairing requires Android TV Remote on the device."),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!state.androidTvPairingStarted) {
                        Button(onClick = { onStartAndroidTvPairing(state.pairingHost) }, modifier = Modifier.fillMaxWidth(), enabled = state.pairingHost.isNotBlank()) {
                            Text(loc(ar, "ابدأ الاقتران على التلفاز", "Start pairing on TV"))
                        }
                    } else {
                        OutlinedTextField(
                            value = state.pairingCodeInput,
                            onValueChange = onCode,
                            label = { Text(loc(ar, "رمز التلفاز", "TV pairing code")) },
                            placeholder = { Text("123456") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(onClick = { onPair(state.pairingHost, state.pairingCodeInput) }, modifier = Modifier.fillMaxWidth(), enabled = state.androidTvAwaitingCode && state.pairingCodeInput.length == 6) {
                            Text(loc(ar, "تأكيد رمز التلفاز", "Submit TV code"))
                        }
                    }
                } else {
                    Text(loc(ar, "يجب تشغيل Android TV responsiveness على الرسيفر. أدخل الكود المعروض هناك.", "Start Android TV responsiveness on the receiver, then enter its displayed code."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = state.pairingCodeInput, onValueChange = onCode, label = { Text(loc(ar, "كود من 7 أرقام", "7-digit code")) }, placeholder = { Text("1234567") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { onPair(state.pairingHost, state.pairingCodeInput) }, modifier = Modifier.fillMaxWidth(), enabled = state.pairingHost.isNotBlank() && state.pairingCodeInput.length == 7) {
                        Text(loc(ar, "اتصال بالتطبيق المرافق", "Connect to companion app"))
                    }
                }
            }
        }
        if (state.paired == null && state.devices.isEmpty()) Text(loc(ar, "لا توجد أجهزة مكتشفة حالياً.", "No devices discovered yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MacrosPage(
    state: ControllerState,
    onSave: (String?, String, List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (RemoteMacro) -> Unit
) {
    val ar = state.language
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var keysText by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(loc(ar, "اختصارات التحكم", "Remote macros"), style = MaterialTheme.typography.titleLarge)
        Text(loc(ar, "أنشئ تسلسلاً من الأزرار ونفّذه بضغطة واحدة. مثال: HOME, LEFT, ENTER.", "Create a sequence of remote buttons and run it with one tap. Example: HOME, LEFT, ENTER."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (editingId == null) loc(ar, "اختصار جديد", "New macro") else loc(ar, "تعديل الاختصار", "Edit macro"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(loc(ar, "الاسم", "Name")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = keysText, onValueChange = { keysText = it }, label = { Text(loc(ar, "الأزرار بالترتيب", "Keys in order")) }, placeholder = { Text("HOME, LEFT, ENTER") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val keys = keysText.split(Regex("[,\\s]+" )).filter(String::isNotBlank).map { it.uppercase() }
                        onSave(editingId, name, keys)
                        editingId = null; name = ""; keysText = ""
                    }, modifier = Modifier.weight(1f)) { Text(loc(ar, "حفظ", "Save")) }
                    if (editingId != null) OutlinedButton(onClick = { editingId = null; name = ""; keysText = "" }) { Text(loc(ar, "إلغاء", "Cancel")) }
                }
            }
        }
        state.macros.forEach { macro ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(macro.name, style = MaterialTheme.typography.titleSmall)
                    Text(macro.keys.joinToString(" → "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRun(macro) }, modifier = Modifier.weight(1f)) { Text("▶  ${loc(ar, "تشغيل", "Run")}") }
                        TextButton(onClick = { editingId = macro.id; name = macro.name; keysText = macro.keys.joinToString(", ") }) { Text(loc(ar, "تعديل", "Edit")) }
                        TextButton(onClick = { onDelete(macro.id) }) { Text("×  ${loc(ar, "حذف", "Delete")}") }
                    }
                }
            }
        }
        if (state.macros.isEmpty()) Text(loc(ar, "لم تُنشأ اختصارات بعد.", "No macros saved yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppsPage(state: ControllerState, onRefresh: () -> Unit, onLaunch: (ReceiverApp) -> Unit, onUninstall: (ReceiverApp) -> Unit) {
    val ar = state.language
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(loc(ar, "تطبيقات الرسيفر", "Receiver apps"), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRefresh, enabled = !state.loadingApps) { if (state.loadingApps) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(loc(ar, "تحديث", "Refresh")) }
        }
        Text(loc(ar, "يمكن فتح التطبيقات وحذفها. الحذف عبر تطبيق الرسيفر يحتاج تأكيداً على الجهاز.", "Launch or uninstall apps. Uninstalling through the receiver app requires confirmation on that device."), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.apps.forEach { app ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onLaunch(app) }, modifier = Modifier.weight(1f)) { Text("▶  ${loc(ar, "فتح", "Open")}") }
                            TextButton(onClick = { onUninstall(app) }, modifier = Modifier.weight(1f)) { Text("⌫  ${loc(ar, "إلغاء التثبيت", "Uninstall")}") }
                        }
                    }
                }
            }
            if (!state.loadingApps && state.apps.isEmpty()) Text(loc(ar, "اضغط تحديث لجلب التطبيقات من الرسيفر.", "Tap Refresh to load apps from the receiver."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaPage(state: ControllerState, onRefreshScreen: () -> Unit, onScreenLiveChange: (Boolean) -> Unit, onPip: () -> Unit, onCastUrl: (String) -> Unit, onChoose: () -> Unit) {
    val ar = state.language
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loc(ar, "عرض شاشة الرسيفر", "Receiver screen preview"), style = MaterialTheme.typography.titleMedium)
                Text(loc(ar, "حدّث المعاينة يدويًا عند الحاجة، ويمكن فتحها في نافذة عائمة.", "Refresh the preview manually when needed, or open it in a floating window."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (state.screenPreview != null) Image(state.screenPreview.asImageBitmap(), contentDescription = loc(ar, "شاشة الرسيفر", "Receiver screen"), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    if (state.screenBusy) CircularProgressIndicator() else Text(loc(ar, "اربط تطبيق الرسيفر وفعّل خدمة التحكم المساعد", "Pair the receiver app and enable its Accessibility service"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshScreen, modifier = Modifier.weight(1f), enabled = !state.screenBusy && !state.screenLive) { Text(loc(ar, "لقطة الآن", "Capture now")) }
                    Button(onClick = onPip, modifier = Modifier.weight(1f), enabled = state.screenPreview != null) { Text(loc(ar, "نافذة عائمة", "Floating window")) }
                }
                Button(
                    onClick = { onScreenLiveChange(!state.screenLive) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.paired != null && !state.screenBusy
                ) {
                    Text(if (state.screenLive) loc(ar, "إيقاف العرض المباشر", "Stop live preview") else loc(ar, "بدء العرض المباشر", "Start live preview"))
                }
            }
        }
        Text(loc(ar, "إرسال ملفات ووسائط", "Send files and media"), style = MaterialTheme.typography.titleLarge)
        Text(loc(ar, "اختر صورة أو فيديو أو ملفاً. الإرسال يعمل عبر ADB TCP أو عبر تطبيق Android TV responsiveness المقترن.", "Choose an image, video, or file. Transfer uses ADB TCP or the paired Android TV responsiveness app."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("⇧  ${loc(ar, "اختيار ملف للإرسال", "Choose file to send")}") }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loc(ar, "فتح رابط وسائط على الرسيفر", "Open media URL on receiver"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = mediaUrl, onValueChange = { mediaUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTP / HTTPS") })
                OutlinedButton(onClick = { onCastUrl(mediaUrl) }, modifier = Modifier.fillMaxWidth()) { Text(loc(ar, "تشغيل الرابط", "Open URL")) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loc(ar, "وجهة النقل", "Transfer destination"), style = MaterialTheme.typography.titleSmall)
                Text(if (state.adbHost.isNotBlank()) "ADB · ${state.adbHost}:${state.adbPort}" else state.paired?.let { "${it.name} · ${it.host}" } ?: loc(ar, "لا يوجد جهاز متصل", "No receiver connected"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(loc(ar, "حد الحجم عند النقل عبر تطبيق الرسيفر: 512 ميغابايت للملف الواحد.", "File size limit through the receiver app: 512 MB per file."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScreenPage(state: ControllerState, onRefresh: () -> Unit, onPip: () -> Unit) {
    val ar = state.language
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(loc(ar, "عرض شاشة الرسيفر", "Receiver screen preview"), style = MaterialTheme.typography.titleLarge)
        Text(loc(ar, "تصل اللقطة عبر اتصال التطبيقين المحلي؛ فعّل خدمة التحكم المساعد على الرسيفر. النافذة العائمة تستخدم وضع صورة داخل صورة.", "The snapshot comes over the paired app connection. Enable Accessibility on the receiver. Floating preview uses picture-in-picture."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.screenPreview != null) {
            Image(state.screenPreview.asImageBitmap(), contentDescription = loc(ar, "شاشة الرسيفر", "Receiver screen"), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text(loc(ar, "تحديث الآن", "Refresh now")) }
                Button(onClick = onPip, modifier = Modifier.weight(1f)) { Text(loc(ar, "نافذة عائمة", "Floating window")) }
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                if (state.screenBusy) CircularProgressIndicator() else Text(loc(ar, "اربط تطبيق الرسيفر وفعّل خدمة التحكم المساعد", "Pair the receiver app and enable its Accessibility service"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !state.screenBusy) { Text(loc(ar, "التقاط أول صورة", "Capture first frame")) }
        }
    }
}

@Composable
private fun SettingsPage(
    state: ControllerState,
    onTheme: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onAdbHost: (String) -> Unit,
    onAdbPort: (String) -> Unit,
    onAdbConnect: () -> Unit,
    onAdbCommand: (String) -> Unit,
    onRunAdbCommand: (String) -> Unit,
    onEditFavorite: (Int, String, String) -> Unit,
    onRunFavorite: (Int) -> Unit,
    onCustomJson: () -> Unit,
    onCustomJsonText: (String) -> Unit
) {
    val ar = state.language
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(loc(ar, "الإعدادات", "Settings"), style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("◐  ${loc(ar, "مظهر التطبيق", "Appearance")}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.theme == "purple", onClick = { onTheme("purple") }, label = { Text(loc(ar, "بنفسجي رمادي", "Purple gray")) })
                    FilterChip(selected = state.theme == "white", onClick = { onTheme("white") }, label = { Text(loc(ar, "أبيض كامل", "Pure white")) })
                }
                Divider()
                Text("文  ${loc(ar, "اللغة", "Language")}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ar == "ar", onClick = { onLanguage("ar") }, label = { Text("العربية") })
                    FilterChip(selected = ar == "en", onClick = { onLanguage("en") }, label = { Text("English") })
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loc(ar, "الاتصال العادي", "Standard connection"), style = MaterialTheme.typography.titleMedium)
                Text(state.paired?.let { "${it.name} · ${it.host}" } ?: loc(ar, "يمكن ربط الرسيفر بمسح QR أو كود 7 أرقام.", "Pair the receiver by scanning its QR code or entering its 7-digit code."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Divider()
                Text(loc(ar, "إرسال JSON مخصص", "Custom JSON sender"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = state.customJson, onValueChange = onCustomJsonText, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("JSON") })
                OutlinedButton(onClick = onCustomJson, modifier = Modifier.fillMaxWidth()) { Text(loc(ar, "إرسال إلى الرسيفر", "Send to receiver")) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text("⚠  ${loc(ar, "إعدادات متقدمة · ADB", "Advanced settings · ADB")}  ${if (advanced) "▲" else "▼"}")
                }
                if (!advanced) Text(loc(ar, "مخفية افتراضياً. افتحها فقط إذا كنت تعرف أوامر ADB.", "Hidden by default. Open only if you understand ADB commands."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (advanced) {
                    Text(loc(ar, "ADB يمنح أوامرك صلاحيات shell على الرسيفر بعد موافقة بصمة المفتاح. اتصال هذا التطبيق يدعم ADB TCP التقليدي فقط؛ لا يدعم شاشة الاقتران اللاسلكي ذات المنفذ المؤقت.", "ADB commands run with the receiver's shell privileges after you approve its key. This app supports classic ADB TCP only; it does not support the newer wireless-debugging pairing port."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = state.adbHost, onValueChange = onAdbHost, label = { Text("IP") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = state.adbPort, onValueChange = onAdbPort, label = { Text(loc(ar, "المنفذ", "Port")) }, modifier = Modifier.width(104.dp), singleLine = true)
                    }
                    Button(onClick = onAdbConnect, modifier = Modifier.fillMaxWidth()) { Text(if (state.adbConnected) loc(ar, "إعادة الاتصال بـ ADB", "Reconnect ADB") else loc(ar, "اتصال ADB", "Connect ADB")) }
                    OutlinedTextField(
                        value = state.adbCommand,
                        onValueChange = onAdbCommand,
                        label = { Text(loc(ar, "أمر ADB", "ADB command")) },
                        placeholder = { Text("input keyevent 3") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    OutlinedButton(onClick = { onRunAdbCommand(state.adbCommand) }, modifier = Modifier.fillMaxWidth()) { Text(loc(ar, "تنفيذ الأمر", "Run command")) }
                    Text(loc(ar, "الأوامر المفضلة (10 قابلة للتعديل)", "Favorite commands (10 editable slots)"), style = MaterialTheme.typography.titleSmall)
                    state.favorites.forEachIndexed { index, favorite ->
                        FavoriteEditor(index, favorite, ar, { name, command -> onEditFavorite(index, name, command) }, { onRunFavorite(index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteEditor(index: Int, favorite: FavoriteCommand, language: String, onChange: (String, String) -> Unit, onRun: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${index + 1}. ${loc(language, "مفضل", "Favorite")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(value = favorite.name, onValueChange = { onChange(it, favorite.command) }, label = { Text(loc(language, "الاسم", "Name")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = favorite.command, onValueChange = { onChange(favorite.name, it) }, label = { Text("ADB") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("▶  ${loc(language, "تنفيذ", "Run")}") }
        }
    }
}

@Composable
private fun QrScannerDialog(language: String, onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).height(520.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                CameraQrPreview(onScanned)
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.52f)).padding(16.dp)) {
                    Text(loc(language, "وجّه الكاميرا إلى رمز QR في الرسيفر", "Point the camera at the receiver QR"), color = Color.White)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(loc(language, "إغلاق", "Close"), color = Color.White) }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoded = remember { AtomicBoolean(false) }
    val lastFrame = remember { AtomicLong(0) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            cameraProvider = provider
                            val preview = Preview.Builder().setTargetResolution(Size(1280, 720)).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analysis.setAnalyzer(executor) { image ->
                                val now = System.currentTimeMillis()
                                if (!decoded.get() && now - lastFrame.get() > 350) {
                                    lastFrame.set(now)
                                    decodeQr(image)?.let { content ->
                                        if (decoded.compareAndSet(false, true)) android.os.Handler(android.os.Looper.getMainLooper()).post { onScanned(content) }
                                    }
                                }
                                image.close()
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            cameraError = e.message ?: "Camera unavailable"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )
        cameraError?.let { Text(it, Modifier.align(Alignment.Center).padding(20.dp), color = Color.White) }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            executor.shutdown()
        }
    }
}

private fun decodeQr(image: ImageProxy): String? {
    if (image.format != ImageFormat.YUV_420_888) return null
    return try {
        val plane = image.planes[0]
        val sourceBuffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val bytes = ByteArray(width * height)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) for (x in 0 until width) {
            bytes[y * width + x] = sourceBuffer.get(y * rowStride + x * pixelStride)
        }
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply { put(DecodeHintType.TRY_HARDER, true) }
        var rotated = bytes
        var rotatedWidth = width
        var rotatedHeight = height
        repeat(4) { turn ->
            try {
                val source = PlanarYUVLuminanceSource(rotated, rotatedWidth, rotatedHeight, 0, 0, rotatedWidth, rotatedHeight, false)
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: Exception) {
                if (turn < 3) {
                    val next = ByteArray(rotated.size)
                    val nextWidth = rotatedHeight
                    val nextHeight = rotatedWidth
                    for (y in 0 until rotatedHeight) for (x in 0 until rotatedWidth) {
                        val newX = rotatedHeight - 1 - y
                        val newY = x
                        next[newY * nextWidth + newX] = rotated[y * rotatedWidth + x]
                    }
                    rotated = next
                    rotatedWidth = nextWidth
                    rotatedHeight = nextHeight
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
