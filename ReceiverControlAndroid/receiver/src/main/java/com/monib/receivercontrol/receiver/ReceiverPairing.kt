package com.monib.receivercontrol.receiver

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object ReceiverPairing {
    fun getOrCreateCode(context: Context): String {
        val prefs = context.getSharedPreferences(LocalReceiverService.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(LocalReceiverService.CODE_KEY, null) ?:
            (1..7).joinToString("") { SecureRandom().nextInt(10).toString() }
                .also { prefs.edit().putString(LocalReceiverService.CODE_KEY, it).apply() }
    }

    fun lanIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 1 else 0 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    fun qrPayload(context: Context): String? {
        val ip = lanIpv4Address() ?: return null
        return qrPayloadFrom(ip, getOrCreateCode(context))
    }

    fun qrPayloadFrom(ip: String?, pairingCode: String): String? {
        if (ip.isNullOrBlank()) return null
        val json = JsonObject().apply {
            addProperty("type", "ReceiverControlPairing")
            addProperty("ip", ip)
            addProperty("port", LocalReceiverService.COMMAND_PORT)
            addProperty("code", pairingCode)
            addProperty("name", android.os.Build.MODEL.orEmpty().ifBlank { "Android Receiver" })
        }
        return json.toString()
    }

    fun qrBitmap(payload: String, size: Int = 360): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }.getOrNull()
}
