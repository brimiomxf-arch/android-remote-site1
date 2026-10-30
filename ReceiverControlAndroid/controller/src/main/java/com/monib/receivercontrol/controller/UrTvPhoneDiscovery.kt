package com.monib.receivercontrol.controller

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Finds UrTV's phone page on a TV where the owner has already enabled phone control. */
internal object UrTvPhoneDiscovery {
    private const val FIRST_PORT = 49152
    private const val LAST_PORT = 65535
    private const val WORKERS = 64
    private const val CONNECT_TIMEOUT_MS = 80
    private const val RESPONSE_TIMEOUT_MS = 350
    private const val MAX_RESPONSE_BYTES = 16 * 1024
    private const val PHONE_TITLE = "<title>تحكم من الجوال</title>"

    suspend fun findPageUrl(hostInput: String): String? = withContext(Dispatchers.IO) {
        val (host, address) = parsePrivateIpv4(hostInput)
        val ports = Channel<Int>(Channel.UNLIMITED)
        val result = CompletableDeferred<Int?>()
        val workers = List(WORKERS) {
            launch(Dispatchers.IO) {
                for (port in ports) {
                    if (result.isCompleted) break
                    if (isUrTvPhonePage(address, host, port)) {
                        result.complete(port)
                        break
                    }
                }
            }
        }

        try {
            for (port in FIRST_PORT..LAST_PORT) {
                if (result.isCompleted) break
                ports.send(port)
            }
            ports.close()
            workers.joinAll()
            if (!result.isCompleted) result.complete(null)
            result.await()?.let { port -> "http://$host:$port/p" }
        } finally {
            ports.cancel()
            workers.forEach { it.cancel() }
        }
    }

    fun isUrTvPhoneUrl(uri: Uri): Boolean {
        if (uri.scheme != "http" || uri.path != "/p" || uri.port !in FIRST_PORT..LAST_PORT) return false
        return runCatching { parsePrivateIpv4(uri.host.orEmpty()) }.isSuccess
    }

    private fun parsePrivateIpv4(input: String): Pair<String, Inet4Address> {
        val ascii = buildString {
            input.trim().forEach { character ->
                val digit = Character.digit(character, 10)
                append(if (digit in 0..9) ('0'.code + digit).toChar() else character)
            }
        }
        val parts = ascii.split('.')
        require(parts.size == 4) { "أدخل عنوان IPv4 مثل 192.168.1.20" }
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
        require(octets.all { it != null }) { "عنوان IP غير صالح" }
        val bytes = ByteArray(4) { index -> octets[index]!!.toByte() }
        val address = InetAddress.getByAddress(bytes) as Inet4Address
        require(address.isSiteLocalAddress) { "أدخل عنوان IP الخاص بالتلفاز على شبكة Wi-Fi المحلية" }
        return address.hostAddress.orEmpty() to address
    }

    private fun isUrTvPhonePage(address: InetAddress, host: String, port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = RESPONSE_TIMEOUT_MS
            socket.getOutputStream().apply {
                write("GET /p HTTP/1.1\r\nHost: $host:$port\r\nAccept: text/html\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val input = socket.getInputStream()
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (response.size() < MAX_RESPONSE_BYTES) {
                val remaining = MAX_RESPONSE_BYTES - response.size()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                response.write(buffer, 0, count)
                val text = response.toString(Charsets.UTF_8.name())
                if (text.contains("\r\n\r\n") && text.contains(PHONE_TITLE)) {
                    return text.startsWith("HTTP/1.1 200") || text.startsWith("HTTP/1.0 200")
                }
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
