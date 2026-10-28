package com.monib.receivercontrol.controller

import android.content.Context
import com.out.androidtvremote.androidLib.AndroidRemoteContext
import com.out.androidtvremote.androidLib.pairing.PairingListener
import com.out.androidtvremote.androidLib.pairing.PairingSession
import com.out.androidtvremote.androidLib.remote.RemoteSession
import com.out.androidtvremote.androidLib.remote.Remotemessage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native Android TV Remote v2 pairing/control. It does not need LocalReceiverService on the TV. */
class AndroidTvRemoteClient(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AndroidTvRemote").apply { isDaemon = true }
    }

    @Volatile private var pairingSession: PairingSession? = null
    @Volatile private var remoteSession: RemoteSession? = null
    @Volatile private var activeHost: String? = null

    init {
        AndroidRemoteContext.getInstance().keyStoreFile = File(appContext.filesDir, KEYSTORE_NAME)
        AndroidRemoteContext.getInstance().clientName = "Android remote"
    }

    interface Listener {
        fun onPairingCodeRequested()
        fun onPairingAccepted() = Unit
        fun onConnected()
        fun onDisconnected() = Unit
        fun onError(message: String)
    }

    fun startPairing(host: String, listener: Listener) {
        closeSessions()
        val pairing = PairingSession()
        pairingSession = pairing
        executor.execute {
            try {
                pairing.pair(host, PAIRING_PORT, object : PairingListener {
                    override fun onSessionCreated() = Unit
                    override fun onPerformInputDeviceRole() = Unit
                    override fun onPerformOutputDeviceRole(gamma: ByteArray) = Unit
                    override fun onSecretRequested() = listener.onPairingCodeRequested()
                    override fun onSessionEnded() = Unit
                    override fun onError(message: String) = listener.onError(message)
                    override fun onPaired() = listener.onPairingAccepted()
                    override fun onLog(message: String) = Unit
                })
                runCatching { pairing.abort() }
                pairingSession = null
                connectRemote(host, listener)
            } catch (error: Exception) {
                pairingSession = null
                listener.onError(error.message ?: "Android TV pairing failed")
            }
        }
    }

    fun providePairingCode(code: String) {
        val pending = pairingSession ?: throw IllegalStateException("ابدأ الاقتران أولاً")
        require(code.matches(Regex("[0-9A-Fa-f]{6}"))) { "رمز Android TV يجب أن يتكوّن من 6 أحرف أو أرقام سداسية عشرية." }
        try {
            pending.provideSecret(code.uppercase())
        } catch (error: Exception) {
            pairingSession = null
            runCatching { pending.abort() }
            throw IllegalArgumentException("رمز الاقتران غير صحيح أو انتهت صلاحيته. ابدأ اقترانًا جديدًا وأدخل الرمز الظاهر على التلفاز.", error)
        }
    }

    fun reconnect(host: String, listener: Listener) {
        closeSessions()
        executor.execute { connectRemote(host, listener) }
    }

    fun sendKey(host: String, key: String) {
        val session = remoteSession
        if (activeHost != host || session == null || !session.isSocketAlive()) {
            throw IllegalStateException("اتصال Android TV غير جاهز. أعد اختيار الجهاز أو أعد الاقتران.")
        }
        session.sendCommand(toRemoteKey(key), Remotemessage.RemoteDirection.SHORT)
        if (!session.isSocketAlive()) throw IllegalStateException("انقطع اتصال Android TV؛ أعد الاتصال ثم جرّب مجدداً.")
    }

    fun close() {
        closeSessions()
        executor.shutdownNow()
    }

    private fun connectRemote(host: String, listener: Listener) {
        val connectedReported = AtomicBoolean(false)
        val failedReported = AtomicBoolean(false)
        val session = RemoteSession(host, REMOTE_PORT, object : RemoteSession.RemoteSessionListener {
            override fun onConnected() {
                activeHost = host
                if (connectedReported.compareAndSet(false, true)) listener.onConnected()
            }

            override fun onSslError() {
                failOnce("تعذّر إنشاء TLS مع التلفاز. أعد الاقتران من شاشة Android TV.")
            }

            override fun onDisconnected() {
                activeHost = null
                listener.onDisconnected()
            }

            override fun onImeShow(text: String, fieldCounter: Int) = Unit

            override fun onError(message: String) {
                failOnce(message.ifBlank { "رفض التلفاز اتصال الريموت." })
            }

            private fun failOnce(message: String) {
                if (failedReported.compareAndSet(false, true)) listener.onError(message)
            }
        })
        remoteSession = session
        activeHost = null
        try {
            session.connect()
            if (session.isSocketAlive()) {
                activeHost = host
                if (connectedReported.compareAndSet(false, true)) listener.onConnected()
            } else if (failedReported.compareAndSet(false, true)) {
                listener.onError("تعذّر الوصول إلى خدمة Android TV Remote على المنفذ 6466. تأكد أن التلفاز يدعمها وأن الهاتف على شبكة Wi-Fi نفسها.")
            }
        } catch (error: Exception) {
            if (failedReported.compareAndSet(false, true)) listener.onError(error.message ?: "Android TV remote connection failed")
        }
    }

    private fun closeSessions() {
        activeHost = null
        runCatching { pairingSession?.abort() }
        pairingSession = null
        runCatching { remoteSession?.abort() }
        remoteSession = null
    }

    private fun toRemoteKey(key: String): Remotemessage.RemoteKeyCode {
        val name = when (key.uppercase()) {
            "UP" -> "KEYCODE_DPAD_UP"
            "DOWN" -> "KEYCODE_DPAD_DOWN"
            "LEFT" -> "KEYCODE_DPAD_LEFT"
            "RIGHT" -> "KEYCODE_DPAD_RIGHT"
            "ENTER", "OK", "SELECT" -> "KEYCODE_DPAD_CENTER"
            "RECENTS" -> "KEYCODE_APP_SWITCH"
            "MUTE" -> "KEYCODE_MUTE"
            "CHANNEL_UP" -> "KEYCODE_CHANNEL_UP"
            "CHANNEL_DOWN" -> "KEYCODE_CHANNEL_DOWN"
            else -> "KEYCODE_${key.uppercase()}"
        }
        return runCatching { Remotemessage.RemoteKeyCode.valueOf(name) }
            .getOrElse { throw IllegalArgumentException("زر غير مدعوم في Android TV Remote: $key") }
    }

    private companion object {
        const val KEYSTORE_NAME = "androidtv-remote.keystore"
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
    }
}
