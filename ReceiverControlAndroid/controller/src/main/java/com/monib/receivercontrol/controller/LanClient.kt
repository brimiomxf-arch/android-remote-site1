package com.monib.receivercontrol.controller

import android.content.Context
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class ReceiverDevice(
    val name: String,
    val host: String,
    val port: Int = LanClient.COMMAND_PORT,
    val protocol: String = RECEIVER_CONTROL
) {
    companion object {
        const val RECEIVER_CONTROL = "receivercontrol"
        const val ANDROID_TV_REMOTE = "androidtvremote"
    }
}
data class BridgeReply(val ok: Boolean, val message: String, val raw: JsonObject)

object LanClient {
    private const val DISCOVERY_PORT = 39000
    const val COMMAND_PORT = 39001
    private const val MAX_FRAME = 2 * 1024 * 1024
    private val gson = Gson()

    suspend fun discover(context: Context): List<ReceiverDevice> = coroutineScope {
        val companion = async(Dispatchers.IO) { discoverReceiverControl(context) }
        val androidTv = async(Dispatchers.IO) { AndroidTvDiscovery(context).discover() }
        (companion.await() + androidTv.await()).distinctBy { "${it.protocol}:${it.host}" }
    }

    private suspend fun discoverReceiverControl(context: Context): List<ReceiverDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, ReceiverDevice>()
        val payload = "{\"type\":\"DISCOVER\"}".toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 350
            val targets = buildList {
                directedBroadcast(context)?.let(::add)
                add(InetAddress.getByName("255.255.255.255"))
            }.distinctBy { it.hostAddress }
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)) }
            }

            val deadline = System.currentTimeMillis() + 2800
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = JsonParser.parseString(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)).asJsonObject
                    if (json.get("type")?.asString == "RECEIVER") {
                        val host = packet.address.hostAddress ?: continue
                        val port = json.get("port")?.asInt ?: COMMAND_PORT
                        found[host] = ReceiverDevice(json.get("name")?.asString ?: "Android Receiver", host, port, ReceiverDevice.RECEIVER_CONTROL)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Continue until the discovery window has elapsed.
                } catch (_: Exception) {
                    // Ignore malformed packets and keep collecting valid receiver replies.
                }
            }
        }
        found.values.toList()
    }

    suspend fun pair(device: ReceiverDevice, code: String): BridgeReply = request(device, code, JsonObject().apply { addProperty("type", "PAIR") })

    suspend fun command(device: ReceiverDevice, code: String, action: String, value: String = ""): BridgeReply = request(
        device, code, JsonObject().apply {
            addProperty("type", "COMMAND")
            addProperty("action", action)
            addProperty("value", value)
        }
    )

    suspend fun custom(device: ReceiverDevice, code: String, jsonText: String): BridgeReply = request(
        device, code, JsonObject().apply {
            addProperty("type", "COMMAND")
            addProperty("action", "CUSTOM")
            addProperty("json", jsonText)
        }
    )

    suspend fun sendFile(device: ReceiverDevice, code: String, name: String, mime: String, size: Long, open: () -> java.io.InputStream?): BridgeReply = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 200) { "اسم الملف غير صالح" }
        require(size in 0..(512L * 1024 * 1024)) { "حجم الملف يتجاوز الحد 512 ميغابايت" }
        val socket = Socket()
        socket.connect(InetSocketAddress(device.host, device.port), 5000)
        socket.soTimeout = 20_000
        socket.use { client ->
            val output = DataOutputStream(client.getOutputStream())
            val input = DataInputStream(client.getInputStream())
            val header = JsonObject().apply {
                addProperty("type", "FILE")
                addProperty("code", code)
                addProperty("name", name)
                addProperty("mime", mime)
                addProperty("size", size)
            }.toString().toByteArray(Charsets.UTF_8)
            output.writeInt(header.size)
            output.write(header)
            output.flush()
            val stream = open() ?: throw IllegalStateException("تعذر فتح الملف المحدد")
            stream.use { source ->
                val buffer = ByteArray(32 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw java.io.EOFException("انتهى الملف قبل اكتمال الإرسال")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.flush()
            }
            readReply(input)
        }
    }

    private suspend fun request(device: ReceiverDevice, code: String, request: JsonObject): BridgeReply = withContext(Dispatchers.IO) {
        request.addProperty("code", code)
        val socket = Socket()
        socket.connect(InetSocketAddress(device.host, device.port), 4500)
        socket.soTimeout = 8000
        socket.use { client ->
            val output = DataOutputStream(client.getOutputStream())
            val input = DataInputStream(client.getInputStream())
            val bytes = gson.toJson(request).toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
            readReply(input)
        }
    }

    private fun readReply(input: DataInputStream): BridgeReply {
        val length = input.readInt()
        if (length !in 1..MAX_FRAME) throw IllegalStateException("رد غير صالح من الرسيفر")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        return BridgeReply(json.get("ok")?.asBoolean == true, json.get("message")?.asString.orEmpty(), json)
    }

    @Suppress("DEPRECATION")
    private fun directedBroadcast(context: Context): InetAddress? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = manager.dhcpInfo ?: return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        InetAddress.getByAddress(byteArrayOf(
            (broadcast and 0xff).toByte(),
            (broadcast shr 8 and 0xff).toByte(),
            (broadcast shr 16 and 0xff).toByte(),
            (broadcast shr 24 and 0xff).toByte()
        ))
    }.getOrNull()
}
