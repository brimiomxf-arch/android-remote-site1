package com.monib.receivercontrol.controller

import android.app.PictureInPictureParams
import android.content.ContentResolver
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

data class FavoriteCommand(val name: String, val command: String)
data class ReceiverApp(val name: String, val packageName: String)
data class SavedReceiver(
    val name: String,
    val host: String,
    val port: Int,
    val code: String,
    val protocol: String = ReceiverDevice.RECEIVER_CONTROL
)
data class RemoteMacro(val id: String, val name: String, val keys: List<String>)
data class ControllerState(
    val tab: String = "remote",
    val theme: String = "purple",
    val language: String = "ar",
    val devices: List<ReceiverDevice> = emptyList(),
    val discovering: Boolean = false,
    val paired: ReceiverDevice? = null,
    val pairCode: String = "",
    val profiles: List<SavedReceiver> = emptyList(),
    val macros: List<RemoteMacro> = emptyList(),
    val pairingHost: String = "",
    val pairingRequested: Boolean = false,
    val pairingCodeInput: String = "",
    val pairingProtocol: String = ReceiverDevice.RECEIVER_CONTROL,
    val androidTvPairingStarted: Boolean = false,
    val androidTvAwaitingCode: Boolean = false,
    val urtvHost: String = "",
    val urtvConnecting: Boolean = false,
    val urtvError: String? = null,
    val adbHost: String = "",
    val adbPort: String = "5555",
    val adbConnected: Boolean = false,
    val adbCommand: String = "",
    val customJson: String = "{\"action\":\"INFO\"}",
    val favorites: List<FavoriteCommand> = defaultFavorites(),
    val apps: List<ReceiverApp> = emptyList(),
    val loadingApps: Boolean = false,
    val status: String = loc("ar", "لم يتم الاتصال بأي رسيفر", "No receiver connected"),
    val success: Boolean? = null,
    val screenPreview: Bitmap? = null,
    val screenBusy: Boolean = false,
    val screenLive: Boolean = false,
    val pictureInPicture: Boolean = false,
    val websiteBannerDismissed: Boolean = false,
    val showQrScanner: Boolean = false
)

private fun defaultFavorites(language: String = "ar") = listOf(
    FavoriteCommand(loc(language, "تشغيل/إيقاف الشاشة", "Screen power"), "input keyevent 26"),
    FavoriteCommand(loc(language, "الرئيسية", "Home"), "input keyevent 3"),
    FavoriteCommand(loc(language, "رجوع", "Back"), "input keyevent 4"),
    FavoriteCommand(loc(language, "يسار", "Left"), "input keyevent 21"),
    FavoriteCommand(loc(language, "يمين", "Right"), "input keyevent 22"),
    FavoriteCommand(loc(language, "أعلى", "Up"), "input keyevent 19"),
    FavoriteCommand(loc(language, "أسفل", "Down"), "input keyevent 20"),
    FavoriteCommand(loc(language, "موافق", "Enter"), "input keyevent 66"),
    FavoriteCommand(loc(language, "رفع الصوت", "Volume up"), "input keyevent 24"),
    FavoriteCommand(loc(language, "خفض الصوت", "Volume down"), "input keyevent 25")
)

class MainActivity : ComponentActivity() {
    private val adb by lazy { AdbShellClient() }
    private val androidTvRemote by lazy { AndroidTvRemoteClient(applicationContext) }
    private val gson = Gson()
    private var ui by androidx.compose.runtime.mutableStateOf(ControllerState())
    private val prefs by lazy { getSharedPreferences("controller", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = loadSavedState()
        setContent {
            ReceiverControlApp(
                state = ui,
                onTab = { ui = ui.copy(tab = it) },
                onDiscover = ::discoverDevices,
                onPairPrompt = ::selectDiscoveredDevice,
                onPair = ::pairDevice,
                onPairingProtocol = ::changePairingProtocol,
                onStartAndroidTvPairing = ::startAndroidTvPairing,
                onQrScan = ::handleQrScan,
                onScannerVisibility = { ui = ui.copy(showQrScanner = it) },
                onManualHost = { ui = ui.copy(pairingHost = it) },
                onUrTvHost = { input ->
                    val host = input.mapNotNull { character ->
                        val digit = Character.digit(character, 10)
                        when {
                            digit in 0..9 -> ('0'.code + digit).toChar()
                            character == '.' -> '.'
                            else -> null
                        }
                    }.joinToString("").take(15)
                    ui = ui.copy(urtvHost = host, urtvError = null)
                },
                onConnectUrTv = ::connectUrTv,
                onPairCodeInput = { input ->
                    val code = if (ui.pairingProtocol == ReceiverDevice.ANDROID_TV_REMOTE) {
                        input.mapNotNull { character ->
                            val digit = Character.digit(character, 10)
                            when {
                                digit in 0..9 -> ('0'.code + digit).toChar()
                                character.uppercaseChar() in 'A'..'F' -> character.uppercaseChar()
                                else -> null
                            }
                        }.joinToString("").take(6)
                    } else {
                        input.mapNotNull { character ->
                            val digit = Character.digit(character, 10)
                            if (digit in 0..9) ('0'.code + digit).toChar() else null
                        }.joinToString("").take(7)
                    }
                    ui = ui.copy(pairingCodeInput = code)
                },
                onSendKey = ::sendRemoteKey,
                onAdbHost = { ui = ui.copy(adbHost = it) },
                onAdbPort = { ui = ui.copy(adbPort = it.filter(Char::isDigit).take(5)) },
                onAdbConnect = ::connectAdb,
                onAdbCommand = { ui = ui.copy(adbCommand = it) },
                onRunAdbCommand = ::runAdbCommand,
                onEditFavorite = ::editFavorite,
                onRunFavorite = { index -> ui.favorites.getOrNull(index)?.command?.let(::runAdbCommand) },
                onRefreshApps = ::refreshApps,
                onLaunchApp = ::launchApp,
                onUninstallApp = ::uninstallApp,
                onSendFile = ::sendFile,
                onRefreshScreen = ::refreshScreen,
                onScreenLiveChange = { ui = ui.copy(screenLive = it) },
                onPip = ::enterPictureInPicture,
                onWebsite = { startActivity(Intent(this, WebsiteActivity::class.java)) },
                onDismissWebsiteBanner = {
                    prefs.edit().putBoolean("website.banner.dismissed", true).apply()
                    ui = ui.copy(websiteBannerDismissed = true)
                },
                onTheme = ::changeTheme,
                onLanguage = ::changeLanguage,
                onCustomJson = { sendCustomJson(ui.customJson) },
                onCustomJsonText = { ui = ui.copy(customJson = it) },
                onSelectProfile = ::selectProfile,
                onDeleteProfile = ::deleteProfile,
                onSaveMacro = ::saveMacro,
                onDeleteMacro = ::deleteMacro,
                onRunMacro = ::runMacro,
                onCastUrl = ::castUrl
            )
        }
        ui.paired?.takeIf { it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }?.let { connectSavedAndroidTv(it.host) }
    }

    override fun onDestroy() {
        androidTvRemote.close()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ui.tab in setOf("screen", "media") && ui.screenPreview != null) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui = ui.copy(pictureInPicture = isInPictureInPictureMode)
    }

    private fun loadSavedState(): ControllerState {
        val host = prefs.getString("pair.host", "").orEmpty()
        val code = prefs.getString("pair.code", "").orEmpty()
        val savedName = prefs.getString("pair.name", "").orEmpty()
        val port = prefs.getInt("pair.port", LanClient.COMMAND_PORT)
        val protocol = prefs.getString("pair.protocol", ReceiverDevice.RECEIVER_CONTROL).orEmpty()
        val language = prefs.getString("language", "ar").orEmpty()
        val profiles = runCatching { gson.fromJson(prefs.getString("profiles", null), Array<SavedReceiver>::class.java)?.toList() }
            .getOrNull().orEmpty().map { it.copy(protocol = it.protocol.ifBlank { ReceiverDevice.RECEIVER_CONTROL }) }
        val macros = runCatching { gson.fromJson(prefs.getString("macros", null), Array<RemoteMacro>::class.java)?.toList() }.getOrNull().orEmpty()
        val savedFavorites = runCatching {
            gson.fromJson(prefs.getString("adb.favorites", null), Array<FavoriteCommand>::class.java)?.toList()
        }.getOrNull()?.takeIf { it.size == 10 } ?: defaultFavorites(language)
        return ControllerState(
            theme = prefs.getString("theme", "purple").orEmpty(),
            language = language,
            paired = if (host.isNotBlank() && (protocol == ReceiverDevice.ANDROID_TV_REMOTE || code.length == 7)) {
                ReceiverDevice(savedName.ifBlank { "رسيفر محفوظ" }, host, if (protocol == ReceiverDevice.ANDROID_TV_REMOTE) 6466 else port, protocol)
            } else null,
            pairCode = if (protocol == ReceiverDevice.ANDROID_TV_REMOTE) "" else code,
            websiteBannerDismissed = prefs.getBoolean("website.banner.dismissed", false),
            profiles = profiles,
            macros = macros,
            adbHost = prefs.getString("adb.host", "").orEmpty(),
            adbPort = prefs.getInt("adb.port", 5555).toString(),
            urtvHost = prefs.getString("urtv.host", "").orEmpty(),
            favorites = savedFavorites
        )
    }

    private fun connectUrTv() {
        if (ui.urtvConnecting) return
        val host = ui.urtvHost.trim()
        prefs.edit().putString("urtv.host", host).apply()
        ui = ui.copy(
            urtvConnecting = true,
            urtvError = null,
            status = loc(ui.language, "جارٍ البحث عن ريموت UrTV على التلفاز…", "Looking for the UrTV remote on the TV…"),
            success = null
        )
        lifecycleScope.launch {
            try {
                val url = UrTvPhoneDiscovery.findPageUrl(host)
                if (url == null) {
                    val message = loc(
                        ui.language,
                        "لم أجد خدمة UrTV على هذا العنوان. افتح على التلفاز UrTV ← «تحكم من الجوال» ← «تشغيل»، واترك الشاشة مفتوحة، ثم أعد المحاولة.",
                        "No UrTV phone service was found. On the TV, open UrTV → Phone control → Start, leave that screen open, then try again."
                    )
                    ui = ui.copy(urtvConnecting = false, urtvError = message, status = message, success = false)
                    return@launch
                }
                ui = ui.copy(
                    urtvConnecting = false,
                    status = loc(ui.language, "تم العثور على ريموت UrTV. أكمل الاقتران الذي يعرضه التلفاز.", "UrTV remote found. Complete the pairing shown by the TV."),
                    success = true
                )
                startActivity(Intent(this@MainActivity, UrTvRemoteActivity::class.java).putExtra(UrTvRemoteActivity.EXTRA_URL, url))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                val message = error.message ?: loc(ui.language, "أدخل عنوان IP محليًا صالحًا.", "Enter a valid local IP address.")
                ui = ui.copy(urtvConnecting = false, urtvError = message, status = message, success = false)
            } catch (error: Exception) {
                val message = loc(ui.language, "تعذر البحث عن ريموت UrTV. تأكد من اتصال Wi-Fi وأعد المحاولة.", "Could not search for the UrTV remote. Check Wi-Fi and try again.")
                ui = ui.copy(urtvConnecting = false, urtvError = message, status = message, success = false)
            }
        }
    }

    private fun selectDiscoveredDevice(device: ReceiverDevice) {
        ui = ui.copy(
            pairingHost = device.host,
            pairingProtocol = device.protocol,
            pairingRequested = true,
            pairingCodeInput = "",
            androidTvPairingStarted = false,
            androidTvAwaitingCode = false,
            status = if (device.protocol == ReceiverDevice.ANDROID_TV_REMOTE) {
                loc(ui.language, "تم اختيار ${device.name}. ابدأ اقتران Android TV المباشر.", "Selected ${device.name}. Start Android TV pairing.")
            } else {
                loc(ui.language, "أدخل كود تطبيق الرسيفر ذي 7 أرقام.", "Enter the receiver app's 7-digit code.")
            },
            success = null
        )
    }

    private fun changePairingProtocol(protocol: String) {
        if (protocol !in setOf(ReceiverDevice.RECEIVER_CONTROL, ReceiverDevice.ANDROID_TV_REMOTE)) return
        ui = ui.copy(
            pairingProtocol = protocol,
            pairingCodeInput = "",
            androidTvPairingStarted = false,
            androidTvAwaitingCode = false,
            status = loc(ui.language, "اختر الجهاز وأكمل خطوات الاقتران الظاهرة.", "Select a device and follow its pairing steps."),
            success = null
        )
    }

    private fun startAndroidTvPairing(host: String) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            ui = ui.copy(status = loc(ui.language, "أدخل عنوان IP للتلفاز أو اختر جهازاً مكتشفاً.", "Enter the TV's IP address or choose a discovered device."), success = false)
            return
        }
        ui = ui.copy(
            pairingHost = cleanHost,
            pairingProtocol = ReceiverDevice.ANDROID_TV_REMOTE,
            pairingCodeInput = "",
            androidTvPairingStarted = true,
            androidTvAwaitingCode = false,
            status = loc(ui.language, "جارٍ بدء الاقتران. وافق على الطلب في التلفاز وانتظر الرمز.", "Starting pairing. Approve the request on the TV and wait for its code."),
            success = null
        )
        androidTvRemote.startPairing(cleanHost, androidTvListener(cleanHost))
    }

    private fun submitAndroidTvPairingCode(code: String) {
        val cleanCode = code.trim().uppercase()
        if (!cleanCode.matches(Regex("[0-9A-F]{6}"))) {
            ui = ui.copy(status = loc(ui.language, "أدخل رمز التلفاز المكوّن من 6 خانات (0-9 أو A-F).", "Enter the TV's 6-character code (0-9 or A-F)."), success = false)
            return
        }
        try {
            androidTvRemote.providePairingCode(cleanCode)
            ui = ui.copy(androidTvAwaitingCode = false, status = loc(ui.language, "تم إرسال الرمز، جارٍ التحقق والاتصال…", "Code sent. Verifying and connecting…"), success = null)
        } catch (error: Exception) {
            ui = ui.copy(androidTvPairingStarted = false, androidTvAwaitingCode = false, status = error.message ?: loc(ui.language, "تعذر إرسال رمز التلفاز.", "Could not submit the TV code."), success = false)
        }
    }

    private fun connectSavedAndroidTv(host: String) {
        ui = ui.copy(status = loc(ui.language, "جارٍ إعادة الاتصال بالتلفاز المحفوظ…", "Reconnecting to the saved TV…"), success = null)
        androidTvRemote.reconnect(host, androidTvListener(host))
    }

    private fun androidTvListener(host: String) = object : AndroidTvRemoteClient.Listener {
        override fun onPairingCodeRequested() = runOnUiThread {
            if (ui.pairingHost == host) {
                ui = ui.copy(
                    androidTvAwaitingCode = true,
                    status = loc(ui.language, "افتح شاشة التلفاز، وافق على الاقتران، ثم أدخل الرمز ذي 6 خانات.", "Approve pairing on the TV, then enter the 6-character code shown there."),
                    success = null
                )
            }
        }

        override fun onPairingAccepted() = runOnUiThread {
            ui = ui.copy(status = loc(ui.language, "قَبِل التلفاز الرمز. جارٍ فتح قناة التحكم…", "The TV accepted the code. Opening the remote session…"), success = null)
        }

        override fun onConnected() = runOnUiThread { saveAndroidTvConnection(host) }

        override fun onDisconnected() = runOnUiThread {
            if (ui.paired?.host == host && ui.paired?.protocol == ReceiverDevice.ANDROID_TV_REMOTE) {
                ui = ui.copy(status = loc(ui.language, "انقطع اتصال التلفاز. أعد الاتصال أو أعد الاقتران.", "The TV connection dropped. Reconnect or pair again."), success = false)
            }
        }

        override fun onError(message: String) = runOnUiThread {
            val refused = message.contains("ECONNREFUSED", ignoreCase = true) ||
                message.contains("failed to connect", ignoreCase = true)
            val companionOnly = ui.devices.any { it.host == host && it.protocol == ReceiverDevice.RECEIVER_CONTROL } &&
                ui.devices.none { it.host == host && it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }
            val userMessage = when {
                refused && companionOnly -> loc(
                    ui.language,
                    "هذا الجهاز ظاهر كتطبيق رسيفر، لكنه رفض اتصال Android TV المباشر على المنفذ 6467. اختر «تطبيق الرسيفر» وأدخل الكود ذي 7 أرقام. لا يعمل الاتصال المباشر إلا إذا كان Android TV Remote متاحًا على الجهاز.",
                    "This device was found as a receiver companion, but it refused direct Android TV Remote on port 6467. Choose Receiver app and enter its 7-digit code. Direct pairing requires Android TV Remote on the device."
                )
                refused -> loc(
                    ui.language,
                    "رفض الجهاز اتصال Android TV المباشر على المنفذ 6467. تأكد أن الجهاز يدعم خدمة Android TV Remote؛ إذا كان جهاز رسيفر عاديًا فاستخدم تطبيق الرسيفر أو ADB.",
                    "The device refused Android TV Remote on port 6467. Confirm it supports Android TV Remote; for a regular receiver, use the companion app or ADB."
                )
                else -> message.ifBlank { loc(ui.language, "تعذر الاقتران. تحقق من الرمز الظاهر على التلفاز وأعد المحاولة.", "Pairing failed. Check the code shown on the TV and try again.") }
            }
            ui = ui.copy(
                androidTvPairingStarted = false,
                androidTvAwaitingCode = false,
                status = userMessage,
                success = false
            )
        }
    }

    private fun saveAndroidTvConnection(host: String) {
        val found = ui.devices.firstOrNull { it.host == host && it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }
        val device = ReceiverDevice(found?.name ?: "Android TV ($host)", host, 6466, ReceiverDevice.ANDROID_TV_REMOTE)
        val saved = SavedReceiver(device.name, host, 6466, "", ReceiverDevice.ANDROID_TV_REMOTE)
        val profiles = (listOf(saved) + ui.profiles.filterNot { it.host == host && it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }).take(20)
        prefs.edit().putString("pair.host", host).putString("pair.code", "").putString("pair.name", device.name)
            .putInt("pair.port", 6466).putString("pair.protocol", ReceiverDevice.ANDROID_TV_REMOTE)
            .putString("profiles", gson.toJson(profiles)).apply()
        ui = ui.copy(
            paired = device,
            pairCode = "",
            profiles = profiles,
            pairingRequested = false,
            androidTvPairingStarted = false,
            androidTvAwaitingCode = false,
            status = loc(ui.language, "تم الاتصال بـ ${device.name} عبر Android TV Remote.", "Connected to ${device.name} with Android TV Remote."),
            success = true
        )
    }

    private fun discoverDevices() {
        if (ui.discovering) return
        ui = ui.copy(discovering = true, status = loc(ui.language, "جارٍ البحث عن Android TV وتطبيق الرسيفر على شبكة Wi-Fi…", "Searching Wi-Fi for Android TVs and receiver companion apps…"), success = null)
        lifecycleScope.launch {
            try {
                val devices = LanClient.discover(this@MainActivity)
                ui = ui.copy(
                    discovering = false,
                    devices = devices,
                    status = if (devices.isEmpty()) {
                        loc(ui.language, "لم يُعثر على خدمة Android TV Remote أو تطبيق الرسيفر. تأكد من Wi-Fi نفسها؛ بعض الأجهزة لا تعلن خدمة الريموت.", "No Android TV Remote service or companion app found. Check the same Wi-Fi; some devices do not advertise the remote service.")
                    } else {
                        val nativeCount = devices.count { it.protocol == ReceiverDevice.ANDROID_TV_REMOTE }
                        val companionCount = devices.size - nativeCount
                        loc(ui.language, "عُثر على $nativeCount تلفاز Android TV و$companionCount تطبيق رسيفر. اختر مسار الاتصال المناسب.", "Found $nativeCount Android TV device(s) and $companionCount companion app(s). Choose the matching connection path.")
                    },
                    success = devices.isNotEmpty()
                )
            } catch (e: Exception) {
                ui = ui.copy(discovering = false, status = friendlyError(e), success = false)
            }
        }
    }

    private fun pairDevice(host: String, code: String) {
        if (ui.pairingProtocol == ReceiverDevice.ANDROID_TV_REMOTE) {
            submitAndroidTvPairingCode(code)
            return
        }
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || !code.matches(Regex("\\d{7}"))) {
            ui = ui.copy(status = loc(ui.language, "أدخل عنوان IP وكوداً من 7 أرقام.", "Enter an IP address and a 7-digit code."), success = false)
            return
        }
        val known = ui.devices.firstOrNull { it.host == cleanHost && it.protocol == ReceiverDevice.RECEIVER_CONTROL }
            ?: ReceiverDevice("رسيفر $cleanHost", cleanHost)
        ui = ui.copy(status = loc(ui.language, "جارٍ التحقق من كود الاقتران…", "Checking pairing code…"), success = null)
        lifecycleScope.launch {
            try {
                val reply = LanClient.pair(known, code)
                if (!reply.ok) throw IllegalStateException(reply.message.ifBlank { loc(ui.language, "رفض الرسيفر الاقتران.", "The receiver rejected pairing.") })
                val named = known.copy(name = reply.raw.get("name")?.asString ?: known.name)
                val saved = SavedReceiver(named.name, named.host, named.port, code, ReceiverDevice.RECEIVER_CONTROL)
                val profiles = (listOf(saved) + ui.profiles.filterNot { it.host == saved.host && it.protocol == saved.protocol }).take(20)
                prefs.edit().putString("pair.host", named.host).putString("pair.code", code)
                    .putString("pair.name", named.name).putInt("pair.port", named.port).putString("pair.protocol", ReceiverDevice.RECEIVER_CONTROL)
                    .putString("profiles", gson.toJson(profiles)).apply()
            ui = ui.copy(paired = named.copy(protocol = ReceiverDevice.RECEIVER_CONTROL), pairCode = code, profiles = profiles, pairingHost = "", pairingRequested = false, pairingCodeInput = "", status = loc(ui.language, "تم الاقتران مع ${named.name} بنجاح.", "Paired with ${named.name}."), success = true)
            } catch (e: Exception) {
                ui = ui.copy(status = friendlyError(e), success = false)
            }
        }
    }

    private fun handleQrScan(value: String) {
        try {
            val qr = JsonParser.parseString(value).asJsonObject
            require(qr.get("type")?.asString == "ReceiverControlPairing") { loc(ui.language, "رمز QR ليس رمز اقتران ReceiverControl", "This QR code is not a ReceiverControl pairing code.") }
            val host = qr.get("ip")?.asString.orEmpty()
            require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) { loc(ui.language, "عنوان IP غير صالح في الرمز", "The QR code contains an invalid IP address.") }
            val port = qr.get("port")?.asInt ?: LanClient.COMMAND_PORT
            require(port == LanClient.COMMAND_PORT) { loc(ui.language, "منفذ الخدمة غير مدعوم", "The service port is not supported.") }
            val code = qr.get("code")?.asString.orEmpty()
            require(code.matches(Regex("\\d{7}"))) { loc(ui.language, "كود الاقتران في الرمز غير صالح", "The QR code contains an invalid pairing code.") }
            val name = qr.get("name")?.asString ?: "Android Receiver"
            val device = ReceiverDevice(name, host, port, ReceiverDevice.RECEIVER_CONTROL)
            ui = ui.copy(showQrScanner = false, devices = (listOf(device) + ui.devices.filterNot { it.host == host }))
            pairDevice(host, code)
        } catch (e: Exception) {
            ui = ui.copy(showQrScanner = false, status = e.message ?: loc(ui.language, "تعذر قراءة رمز الاقتران.", "Could not read pairing code."), success = false)
        }
    }

    private fun sendRemoteKey(key: String) {
        lifecycleScope.launch { runCatching { sendRemoteKeyNow(key) }.onFailure { ui = ui.copy(status = friendlyError(it), success = false) } }
    }

    private suspend fun sendRemoteKeyNow(key: String) {
        val host = ui.adbHost.trim()
        if (host.isNotBlank()) {
            withContext(Dispatchers.IO) { adb.shell(host, ui.adbPort.toIntOrNull() ?: 5555, keyToAdbCommand(key)) }
            ui = ui.copy(adbConnected = true, status = loc(ui.language, "تم إرسال الأمر.", "Command sent."), success = true)
            return
        }
        val receiver = ui.paired
        if (receiver == null) throw IllegalStateException(loc(ui.language, "اربط الرسيفر أو فعّل ADB الشبكي أولاً.", "Connect to a receiver or enable network ADB first."))
        if (receiver.protocol == ReceiverDevice.ANDROID_TV_REMOTE) {
            withContext(Dispatchers.IO) { androidTvRemote.sendKey(receiver.host, key) }
            ui = ui.copy(status = loc(ui.language, "تم إرسال الأمر إلى Android TV.", "Command sent to Android TV."), success = true)
            return
        }
        if (ui.pairCode.length != 7) throw IllegalStateException(loc(ui.language, "أعد اقتران تطبيق الرسيفر بكود من 7 أرقام.", "Pair the receiver app again with its 7-digit code."))
        val reply = LanClient.command(receiver, ui.pairCode, "KEY", key)
        if (!reply.ok) throw IllegalStateException(reply.message.ifBlank { loc(ui.language, "رفض الرسيفر الأمر.", "The receiver rejected the command.") })
        ui = ui.copy(status = reply.message.ifBlank { loc(ui.language, "تم إرسال الأمر.", "Command sent.") }, success = true)
    }

    private fun selectProfile(profile: SavedReceiver) {
        prefs.edit().putString("pair.host", profile.host).putString("pair.code", profile.code)
            .putString("pair.name", profile.name).putInt("pair.port", profile.port).putString("pair.protocol", profile.protocol).apply()
        val device = ReceiverDevice(profile.name, profile.host, profile.port, profile.protocol)
        ui = ui.copy(paired = device, pairCode = profile.code, pairingProtocol = profile.protocol,
            status = loc(ui.language, "تم اختيار ${profile.name}. جارٍ الاتصال…", "Selected ${profile.name}. Connecting…"), success = null)
        if (profile.protocol == ReceiverDevice.ANDROID_TV_REMOTE) connectSavedAndroidTv(profile.host)
    }

    private fun deleteProfile(profile: SavedReceiver) {
        val profiles = ui.profiles.filterNot { it.host == profile.host && it.protocol == profile.protocol }
        if (ui.paired?.host == profile.host && ui.paired?.protocol == profile.protocol) {
            prefs.edit().remove("pair.host").remove("pair.code").remove("pair.name").remove("pair.protocol").apply()
            ui = ui.copy(profiles = profiles, paired = null, pairCode = "", status = loc(ui.language, "حُذف ملف الاتصال.", "Connection profile removed."), success = true)
        } else ui = ui.copy(profiles = profiles)
        prefs.edit().putString("profiles", gson.toJson(profiles)).apply()
    }

    private fun saveMacro(id: String?, name: String, keys: List<String>) {
        val allowed = setOf("POWER", "HOME", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "VOLUME_UP", "VOLUME_DOWN", "MUTE", "RECENTS")
        val safeKeys = keys.filter { it in allowed }.take(20)
        if (name.isBlank() || safeKeys.isEmpty()) {
            ui = ui.copy(status = loc(ui.language, "أدخل اسماً وخطوة تحكم واحدة على الأقل.", "Enter a name and at least one valid control step."), success = false)
            return
        }
        val macro = RemoteMacro(id ?: java.util.UUID.randomUUID().toString(), name.trim(), safeKeys)
        val macros = if (ui.macros.any { it.id == macro.id }) ui.macros.map { if (it.id == macro.id) macro else it } else listOf(macro) + ui.macros
        prefs.edit().putString("macros", gson.toJson(macros)).apply()
        ui = ui.copy(macros = macros, status = loc(ui.language, "تم حفظ الاختصار.", "Macro saved."), success = true)
    }

    private fun deleteMacro(id: String) {
        val macros = ui.macros.filterNot { it.id == id }
        prefs.edit().putString("macros", gson.toJson(macros)).apply()
        ui = ui.copy(macros = macros, status = loc(ui.language, "تم حذف الاختصار.", "Macro deleted."), success = true)
    }

    private fun runMacro(macro: RemoteMacro) {
        lifecycleScope.launch {
            try {
                ui = ui.copy(status = loc(ui.language, "جارٍ تنفيذ ${macro.name}…", "Running ${macro.name}…"), success = null)
                macro.keys.forEach { key -> sendRemoteKeyNow(key); delay(600) }
                ui = ui.copy(status = loc(ui.language, "اكتمل ${macro.name}.", "${macro.name} completed."), success = true)
            } catch (e: Exception) {
                ui = ui.copy(status = friendlyError(e), success = false)
            }
        }
    }

    private fun connectAdb() {
        val host = ui.adbHost.trim()
        val port = ui.adbPort.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            ui = ui.copy(status = "أدخل عنوان IP ومنفذاً صالحاً.", success = false)
            return
        }
        ui = ui.copy(status = loc(ui.language, "جارٍ الاتصال عبر ADB TCP… وافق على بصمة المفتاح عند ظهورها على الرسيفر.", "Connecting over ADB TCP… approve the key prompt on the receiver."), success = null)
        lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { adb.shell(host, port, "getprop ro.product.model") }
                prefs.edit().putString("adb.host", host).putInt("adb.port", port).apply()
                ui = ui.copy(adbConnected = true, status = loc(ui.language, "اتصال ADB ناجح${model.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""}.", "ADB connected${model.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""}."), success = true)
            } catch (e: Exception) {
                ui = ui.copy(adbConnected = false, status = friendlyError(e), success = false)
            }
        }
    }

    private fun changeTheme(theme: String) {
        ui = ui.copy(theme = theme)
        prefs.edit().putString("theme", theme).apply()
    }

    private fun changeLanguage(language: String) {
        ui = ui.copy(language = language, status = loc(language, "تم تغيير اللغة إلى العربية.", "Language changed to English."))
        prefs.edit().putString("language", language).apply()
    }

    private fun runAdbCommand(command: String) {
        val host = ui.adbHost.trim()
        val port = ui.adbPort.toIntOrNull() ?: 5555
        if (host.isBlank()) {
            ui = ui.copy(status = loc(ui.language, "أدخل IP الرسيفر واتصل عبر ADB أولاً.", "Enter the receiver IP and connect ADB first."), success = false)
            return
        }
        if (command.isBlank()) return
        ui = ui.copy(adbCommand = command, status = loc(ui.language, "جارٍ تنفيذ الأمر…", "Running command…"), success = null)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { adb.shell(host, port, command) }
                ui = ui.copy(adbConnected = true, status = result.ifBlank { loc(ui.language, "اكتمل الأمر.", "Command completed.") }, success = true)
            } catch (e: Exception) {
                ui = ui.copy(adbConnected = false, status = friendlyError(e), success = false)
            }
        }
    }

    private fun editFavorite(index: Int, name: String, command: String) {
        if (index !in ui.favorites.indices) return
        val updated = ui.favorites.toMutableList().also { it[index] = FavoriteCommand(name, command) }
        ui = ui.copy(favorites = updated)
        prefs.edit().putString("adb.favorites", gson.toJson(updated)).apply()
    }

    private fun refreshApps() {
        if (ui.loadingApps) return
        ui = ui.copy(loadingApps = true, status = loc(ui.language, "جارٍ قراءة التطبيقات المثبتة…", "Loading installed apps…"), success = null)
        lifecycleScope.launch {
            try {
                val apps = if (ui.adbHost.isNotBlank()) {
                    val output = withContext(Dispatchers.IO) { adb.shell(ui.adbHost, ui.adbPort.toIntOrNull() ?: 5555, "pm list packages --user 0") }
                    output.lineSequence().mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
                        .map { ReceiverApp(it.substringAfterLast('.'), it) }.toList()
                } else {
                    val receiver = ui.paired ?: throw IllegalStateException(loc(ui.language, "اربط الرسيفر بكود أو اتصل عبر ADB أولاً", "Pair the receiver or connect through ADB first."))
                    if (receiver.protocol == ReceiverDevice.ANDROID_TV_REMOTE) throw IllegalStateException(loc(ui.language, "عرض التطبيقات يحتاج تطبيق Android TV responsiveness أو ADB. اتصال Android TV المباشر يدعم أزرار الريموت.", "App management needs Android TV responsiveness or ADB. Direct Android TV pairing supports remote buttons."))
                    val reply = LanClient.command(receiver, ui.pairCode, "APPS")
                    if (!reply.ok) throw IllegalStateException(reply.message)
                    reply.raw.getAsJsonArray("apps").map { it.asJsonObject }.map {
                        ReceiverApp(it.get("name").asString, it.get("package").asString)
                    }
                }
                ui = ui.copy(apps = apps, loadingApps = false, status = loc(ui.language, "تم جلب ${apps.size} تطبيقاً.", "Loaded ${apps.size} app(s)."), success = true)
            } catch (e: Exception) {
                ui = ui.copy(loadingApps = false, status = friendlyError(e), success = false)
            }
        }
    }

    private fun launchApp(app: ReceiverApp) {
        if (!app.packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return
        if (ui.adbHost.isNotBlank()) runAdbCommand("monkey -p ${app.packageName} 1")
        else bridgeAction("LAUNCH", app.packageName)
    }

    private fun uninstallApp(app: ReceiverApp) {
        if (!app.packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return
        if (ui.adbHost.isNotBlank()) runAdbCommand("pm uninstall ${app.packageName}")
        else bridgeAction("UNINSTALL", app.packageName)
    }

    private fun bridgeAction(action: String, value: String) {
        val receiver = ui.paired
        if (receiver == null) {
            ui = ui.copy(status = loc(ui.language, "لا يوجد اتصال بالرسيفر.", "No receiver connection."), success = false)
            return
        }
        if (receiver.protocol == ReceiverDevice.ANDROID_TV_REMOTE && ui.adbHost.isBlank()) {
            ui = ui.copy(status = loc(ui.language, "هذا الأمر يحتاج تطبيق Android TV responsiveness على الرسيفر أو اتصال ADB. الريموت الأساسي يعمل بالاتصال المباشر.", "This feature needs Android TV responsiveness on the receiver or ADB. Basic remote buttons work through direct pairing."), success = false)
            return
        }
        lifecycleScope.launch {
            try {
                val reply = LanClient.command(receiver, ui.pairCode, action, value)
                ui = ui.copy(status = reply.message, success = reply.ok)
            } catch (e: Exception) {
                ui = ui.copy(status = friendlyError(e), success = false)
            }
        }
    }

    private fun sendCustomJson(text: String) {
        val receiver = ui.paired
        if (receiver == null) {
            ui = ui.copy(status = loc(ui.language, "اقرن الرسيفر أولاً لإرسال JSON.", "Pair a receiver before sending JSON."), success = false)
            return
        }
        if (receiver.protocol == ReceiverDevice.ANDROID_TV_REMOTE && ui.adbHost.isBlank()) {
            ui = ui.copy(status = loc(ui.language, "إرسال JSON يحتاج تطبيق Android TV responsiveness أو اتصال ADB.", "Sending JSON needs Android TV responsiveness or ADB."), success = false)
            return
        }
        lifecycleScope.launch {
            try {
                val response = LanClient.custom(receiver, ui.pairCode, text)
                ui = ui.copy(status = response.message.ifBlank { response.raw.toString() }, success = response.ok)
            } catch (e: Exception) {
                ui = ui.copy(status = friendlyError(e), success = false)
            }
        }
    }

    private fun sendFile(uri: Uri) {
        val resolver = contentResolver
        val name = queryDisplayName(resolver, uri) ?: "media_${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val size = querySize(resolver, uri)
        if (size < 0) {
            ui = ui.copy(status = loc(ui.language, "تعذر معرفة حجم الملف. اختر الملف من مدير الملفات ثم أعد المحاولة.", "Could not read file size. Choose it from a file manager and try again."), success = false)
            return
        }
        val host = ui.adbHost.trim()
        val port = ui.adbPort.toIntOrNull() ?: 5555
        val receiver = ui.paired
        if (host.isBlank() && receiver == null) {
            ui = ui.copy(status = loc(ui.language, "اربط الرسيفر أو اتصل عبر ADB قبل إرسال الملفات.", "Pair the receiver or connect ADB before sending files."), success = false)
            return
        }
        if (host.isBlank() && receiver?.protocol == ReceiverDevice.ANDROID_TV_REMOTE) {
            ui = ui.copy(status = loc(ui.language, "إرسال الملفات يحتاج تطبيق Android TV responsiveness على الرسيفر أو اتصال ADB.", "File transfer needs Android TV responsiveness on the receiver or ADB."), success = false)
            return
        }
        ui = ui.copy(status = loc(ui.language, "جارٍ إرسال $name (${formatBytes(size)})…", "Sending $name (${formatBytes(size)})…"), success = null)
        lifecycleScope.launch {
            try {
                if (host.isNotBlank()) {
                    withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { adb.push(host, port, name, it, size) } ?: error("تعذر فتح الملف") }
                    ui = ui.copy(status = loc(ui.language, "تم نسخ $name إلى مجلد Download في الرسيفر.", "Copied $name to the receiver's Download folder."), success = true)
                } else {
                    val reply = LanClient.sendFile(receiver!!, ui.pairCode, name, mime, size) { resolver.openInputStream(uri) }
                    if (!reply.ok) throw IllegalStateException(reply.message)
                    ui = ui.copy(status = reply.message.ifBlank { loc(ui.language, "تم إرسال الملف إلى الرسيفر.", "File sent to receiver.") }, success = true)
                }
            } catch (e: Exception) {
                ui = ui.copy(status = friendlyError(e), success = false)
            }
        }
    }

    private fun castUrl(rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            ui = ui.copy(status = loc(ui.language, "أدخل رابط HTTP أو HTTPS صالحاً.", "Enter a valid HTTP or HTTPS URL."), success = false)
            return
        }
        if (ui.adbHost.isNotBlank()) runAdbCommand("am start -a android.intent.action.VIEW -d ${shellQuote(uri.toString())}")
        else bridgeAction("OPEN_URL", uri.toString())
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun refreshScreen() {
        if (ui.screenBusy) return
        val receiver = ui.paired
        val code = ui.pairCode
        if (receiver?.protocol == ReceiverDevice.ANDROID_TV_REMOTE) {
            ui = ui.copy(status = loc(ui.language, "عرض الشاشة يحتاج تطبيق Android TV responsiveness أو ADB؛ الاتصال المباشر يدعم الريموت فقط.", "Screen viewing needs Android TV responsiveness or ADB; direct pairing supports remote control only."), success = false)
            return
        }
        if (receiver == null || code.length != 7) {
            ui = ui.copy(status = loc(ui.language, "اربط تطبيق الرسيفر أولاً لعرض الشاشة بدون ADB.", "Pair the receiver app first to view its screen without ADB."), success = false)
            return
        }
        ui = ui.copy(screenBusy = true)
        lifecycleScope.launch {
            try {
                val response = LanClient.command(receiver, code, "SCREENSHOT")
                if (!response.ok) throw IllegalStateException(response.message)
                val encoded = response.raw.get("image")?.asString
                    ?: throw IOException("لم تصل صورة من تطبيق الرسيفر")
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val bitmap = withContext(Dispatchers.Default) { decodeScreenPreview(bytes) }
                    ?: throw IOException("تعذر فك صورة شاشة الرسيفر")
                ui = ui.copy(screenPreview = bitmap, screenBusy = false, success = true)
            } catch (e: Exception) {
                ui = ui.copy(screenBusy = false, status = friendlyError(e), success = false)
            }
        }
    }

    private fun decodeScreenPreview(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options()
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 1280 || bounds.outHeight / sampleSize > 720) {
            sampleSize *= 2
        }
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ui.screenPreview != null) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    private fun keyToAdbCommand(key: String): String {
        val digitCode = key.removePrefix("DIGIT_").singleOrNull()?.digitToIntOrNull()?.let { 7 + it }
        val code = digitCode ?: when (key) {
            "POWER" -> 26; "HOME" -> 3; "BACK" -> 4; "UP" -> 19; "DOWN" -> 20
            "LEFT" -> 21; "RIGHT" -> 22; "ENTER" -> 66; "VOLUME_UP" -> 24
            "VOLUME_DOWN" -> 25; "MUTE" -> 164; "RECENTS" -> 187
            "CHANNEL_UP" -> 166; "CHANNEL_DOWN" -> 167; "DELETE" -> 67
            else -> throw IllegalArgumentException("${loc(ui.language, "زر غير مدعوم", "Unsupported remote key")}: $key")
        }
        return "input keyevent $code"
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is ConnectException -> loc(ui.language, "رفض الجهاز الاتصال. تأكد أن خادم التطبيق يعمل أو أن ADB TCP مفعّل على المنفذ الصحيح.", "The device refused the connection. Check that its server is running or ADB TCP uses the selected port.")
        is SocketTimeoutException -> loc(ui.language, "انتهت مهلة الاتصال. تحقق من الشبكة وكود الاقتران، ثم أعد المحاولة.", "Connection timed out. Check the network and pairing code, then try again.")
        is java.net.UnknownHostException -> loc(ui.language, "تعذر العثور على عنوان الجهاز. تحقق من IP.", "Could not resolve the device address. Check the IP.")
        is SecurityException -> loc(ui.language, "لم يُسمح بالوصول إلى الشبكة المحلية. راجع أذونات التطبيق.", "Local network access was denied. Check app permissions.")
        else -> error.message?.takeIf(String::isNotBlank) ?: loc(ui.language, "تعذر الاتصال بالرسيفر.", "Could not connect to the receiver.")
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
