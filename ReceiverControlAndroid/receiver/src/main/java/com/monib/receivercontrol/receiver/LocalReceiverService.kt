package com.monib.receivercontrol.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalReceiverService : Service() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pairingCode = ""
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var tcpServer: ServerSocket? = null
    private val failedPairings = ConcurrentHashMap<String, Pair<Int, Long>>()

    override fun onCreate() {
        super.onCreate()
        pairingCode = ReceiverPairing.getOrCreateCode(this)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SERVER_RUNNING_KEY, true).apply()
        running = true
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch { discoveryLoop() }
        scope.launch { commandLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        udpSocket?.close()
        tcpServer?.close()
        running = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SERVER_RUNNING_KEY, false).apply()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun discoveryLoop() {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                soTimeout = 1000
            }
        } catch (_: Exception) { stopSelf(); return }
        udpSocket = socket
        val buffer = ByteArray(2048)
        while (true) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val request = JsonParser.parseString(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)).asJsonObject
                if (request.get("type")?.asString != "DISCOVER") continue
                val response = JsonObject().apply {
                    addProperty("type", "RECEIVER")
                    addProperty("name", Build.MODEL.orEmpty().ifBlank { "Android Receiver" })
                    addProperty("port", COMMAND_PORT)
                }.toString().toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: java.net.SocketTimeoutException) {
                // Recheck cancellation and service state.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (socket.isClosed) break
            }
        }
    }

    private suspend fun commandLoop() {
        val server = try { ServerSocket(COMMAND_PORT) } catch (_: Exception) { stopSelf(); return }
        tcpServer = server
        while (true) {
            try {
                val socket = server.accept()
                scope.launch { handleClient(socket) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (server.isClosed) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(client.getOutputStream())
            try {
                val headerSize = input.readInt()
                require(headerSize in 1..MAX_HEADER_SIZE) { "رأس الطلب غير صالح" }
                val headerBytes = ByteArray(headerSize)
                input.readFully(headerBytes)
                val request = JsonParser.parseString(String(headerBytes, Charsets.UTF_8)).asJsonObject
                val type = request.get("type")?.asString.orEmpty()
                val peer = client.inetAddress.hostAddress ?: "unknown"
                val attempt = failedPairings[peer]
                if (attempt != null && attempt.second > System.currentTimeMillis()) {
                    reply(output, error("محاولات كثيرة. أعد المحاولة بعد دقيقة."))
                    return
                }
                if (request.get("code")?.asString != pairingCode) {
                    val count = if (attempt == null || attempt.second > 0L) 1 else attempt.first + 1
                    failedPairings[peer] = count to if (count >= 5) System.currentTimeMillis() + 60_000 else 0L
                    reply(output, error("كود الاقتران غير صحيح"))
                    return
                }
                failedPairings.remove(peer)
                when (type) {
                    "PAIR" -> reply(output, JsonObject().apply { addProperty("ok", true); addProperty("name", Build.MODEL) })
                    "FILE" -> receiveFile(input, output, request)
                    "COMMAND" -> if (request.get("action")?.asString == "SCREENSHOT") captureScreen(output) else reply(output, execute(request))
                    else -> reply(output, error("نوع الطلب غير معروف"))
                }
            } catch (e: Exception) {
                runCatching { reply(output, error(e.message ?: "تعذر تنفيذ الطلب")) }
            }
        }
    }

    private fun captureScreen(output: DataOutputStream) {
        if (Build.VERSION.SDK_INT < 30) {
            reply(output, error("عرض الشاشة يحتاج Android 11 أو أحدث على الرسيفر"))
            return
        }
        val latch = CountDownLatch(1)
        var image: ByteArray? = null
        val started = ReceiverAccessibilityService.captureScreenshot { bytes ->
            image = bytes
            latch.countDown()
        }
        if (!started) {
            reply(output, error("فعّل خدمة التحكم المساعد في إعدادات إمكانية الوصول على الرسيفر"))
            return
        }
        if (!latch.await(8, TimeUnit.SECONDS)) {
            reply(output, error("انتهت مهلة التقاط الشاشة من الرسيفر"))
            return
        }
        val bytes = image ?: run {
            reply(output, error("تعذر التقاط الشاشة. تأكد من تفعيل خدمة التحكم المساعد."))
            return
        }
        reply(output, JsonObject().apply {
            addProperty("ok", true)
            addProperty("message", "تم التقاط الشاشة")
            addProperty("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })
    }

    private fun receiveFile(input: DataInputStream, output: DataOutputStream, request: JsonObject) {
        val name = safeFileName(request.get("name")?.asString.orEmpty())
        val size = request.get("size")?.asLong ?: -1L
        require(size in 0..MAX_FILE_SIZE) { "حجم الملف غير مسموح" }
        val directory = File(getExternalFilesDir("Download") ?: filesDir, "ReceiverControl").apply { mkdirs() }
        val destination = File(directory, name)
        destination.outputStream().buffered().use { stream ->
            val buffer = ByteArray(32 * 1024)
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw java.io.EOFException("انقطع نقل الملف")
                stream.write(buffer, 0, read)
                remaining -= read
            }
        }
        reply(output, JsonObject().apply {
            addProperty("ok", true)
            addProperty("message", "تم استلام الملف")
            addProperty("name", destination.name)
            addProperty("path", destination.absolutePath)
        })
    }

    private fun execute(request: JsonObject): JsonObject {
        val action = request.get("action")?.asString.orEmpty()
        val value = request.get("value")?.asString.orEmpty()
        return try {
            when (action) {
                "KEY" -> executeKey(value)
                "APPS" -> listApps()
                "LAUNCH" -> launchApp(value)
                "UNINSTALL" -> uninstallApp(value)
                "OPEN_URL" -> openUrl(value)
                "INFO" -> JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("name", Build.MODEL)
                    addProperty("android", Build.VERSION.RELEASE)
                    addProperty("accessibility", ReceiverAccessibilityService.isEnabled())
                }
                "CUSTOM" -> {
                    val custom = JsonParser.parseString(request.get("json")?.asString.orEmpty()).asJsonObject
                    when (custom.get("action")?.asString) {
                        "KEY" -> executeKey(custom.get("value")?.asString.orEmpty())
                        "INFO" -> execute(JsonObject().apply { addProperty("action", "INFO") })
                        "APPS" -> listApps()
                        "LAUNCH" -> launchApp(custom.get("package")?.asString.orEmpty())
                        "UNINSTALL" -> uninstallApp(custom.get("package")?.asString.orEmpty())
                        "OPEN_URL" -> openUrl(custom.get("url")?.asString.orEmpty())
                        else -> error("الأمر المخصص غير مدعوم من تطبيق الرسيفر")
                    }
                }
                else -> error("الأمر غير معروف")
            }
        } catch (e: Exception) {
            error(e.message ?: "تعذر تنفيذ الأمر")
        }
    }

    private fun executeKey(key: String): JsonObject {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        when (key) {
            "HOME", "BACK", "LOCK", "RECENTS" -> {
                if (!ReceiverAccessibilityService.perform(key)) {
                    return error("فعّل خدمة التحكم المساعد في شاشة الرسيفر لتنفيذ أزرار النظام")
                }
            }
            "VOLUME_UP" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "VOLUME_DOWN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "MUTE" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            "POWER" -> {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                if (!power.isInteractive) {
                    @Suppress("DEPRECATION")
                    power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ReceiverControl:Wake").acquire(1500)
                } else if (!ReceiverAccessibilityService.perform("LOCK")) {
                    return error("لتشغيل/إطفاء الشاشة فعّل خدمة التحكم المساعد على الرسيفر")
                }
            }
            "UP", "DOWN", "LEFT", "RIGHT", "ENTER" -> {
                if (!ReceiverAccessibilityService.dispatchTouch(key)) {
                    return error("فعّل خدمة التحكم المساعد لتنفيذ إيماءات التنقل والاختيار")
                }
            }
            "CHANNEL_UP", "CHANNEL_DOWN", "DELETE" -> return error("هذا الزر يحتاج اتصال ADB على الرسيفر")
            else -> return error(
                if (key.matches(Regex("DIGIT_[0-9]"))) "إرسال أرقام القنوات يحتاج اتصال ADB على الرسيفر"
                else "زر غير مدعوم"
            )
        }
        return JsonObject().apply { addProperty("ok", true); addProperty("message", "تم تنفيذ الأمر") }
    }

    private fun listApps(): JsonObject {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcher, 0)
        }
        val apps = resolved.map { info ->
            JsonObject().apply {
                addProperty("name", info.loadLabel(packageManager).toString())
                addProperty("package", info.activityInfo.packageName)
            }
        }.distinctBy { it.get("package").asString }.sortedBy { it.get("name").asString.lowercase() }
        return JsonObject().apply {
            addProperty("ok", true)
            add("apps", gson.toJsonTree(apps))
        }
    }

    private fun launchApp(packageName: String): JsonObject {
        require(packageName.matches(PACKAGE_NAME)) { "اسم الحزمة غير صالح" }
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return error("لا يوجد نشاط تشغيل لهذا التطبيق")
        return try {
            startActivity(intent)
            JsonObject().apply { addProperty("ok", true); addProperty("message", "تم طلب فتح التطبيق") }
        } catch (_: Exception) {
            error("منع Android فتح التطبيق في الخلفية. افتح تطبيق الرسيفر أولاً أو استخدم ADB.")
        }
    }

    private fun uninstallApp(packageName: String): JsonObject {
        require(packageName.matches(PACKAGE_NAME)) { "اسم الحزمة غير صالح" }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            JsonObject().apply { addProperty("ok", true); addProperty("message", "تأكيد الحذف مطلوب على الرسيفر") }
        } catch (_: Exception) {
            error("تعذر فتح شاشة إلغاء التثبيت من الخلفية؛ استخدم ADB أو افتح التطبيق على الرسيفر")
        }
    }

    private fun openUrl(value: String): JsonObject {
        val uri = Uri.parse(value)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "الرابط يجب أن يبدأ بـ HTTP أو HTTPS" }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            JsonObject().apply { addProperty("ok", true); addProperty("message", "تم طلب فتح الرابط") }
        } catch (_: Exception) {
            error("منع Android فتح الرابط في الخلفية. افتح تطبيق الرسيفر أولاً أو استخدم ADB.")
        }
    }

    private fun reply(output: DataOutputStream, json: JsonObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun error(message: String) = JsonObject().apply { addProperty("ok", false); addProperty("message", message) }
    private fun safeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trim('.')
        return cleaned.take(150).ifBlank { "received_file" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "خادم Android TV responsiveness", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Android TV responsiveness يعمل")
            .setContentText("جاهز للاتصال على الشبكة المحلية")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val DISCOVERY_PORT = 39000
        const val COMMAND_PORT = 39001
        const val ACTION_START = "com.monib.receivercontrol.receiver.START_SERVER"
        const val ACTION_STOP = "com.monib.receivercontrol.receiver.STOP_SERVER"
        const val PREFS = "receiver_control"
        const val CODE_KEY = "pairing_code"
        private const val CHANNEL_ID = "receiver_server"
        private const val NOTIFICATION_ID = 77
        private const val MAX_HEADER_SIZE = 128 * 1024
        private const val MAX_FILE_SIZE = 512L * 1024 * 1024
        const val SERVER_RUNNING_KEY = "server_running"
        @Volatile var running: Boolean = false
            private set
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

        fun start(context: Context) {
            val intent = Intent(context, LocalReceiverService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, LocalReceiverService::class.java))
    }
}
