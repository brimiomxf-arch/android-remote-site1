package com.monib.receivercontrol.controller

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Minimal ADB transport client for legacy ADB-over-TCP receivers (normally port 5555). */
class AdbShellClient(private val keyStoreAlias: String = "receiver-control-adb-v1") {
    fun shell(host: String, port: Int, command: String): String =
        String(openService(host, port, "shell:$command\u0000"), Charsets.UTF_8).trimEnd('\u0000', '\r', '\n')

    fun shellBytes(host: String, port: Int, command: String): ByteArray =
        openService(host, port, "shell:$command\u0000")

    fun push(host: String, port: Int, remoteName: String, input: java.io.InputStream, size: Long) {
        require(remoteName.matches(Regex("[\\p{L}\\p{N}._ -]{1,160}"))) { "اسم الملف غير صالح" }
        val (socket, inputStream, outputStream) = connected(host, port)
        socket.use {
            inputStream.use { adbIn ->
                outputStream.use { adbOut ->
                    val remoteId = open(adbIn, adbOut, "sync:\u0000")
                    val safePath = "/sdcard/Download/$remoteName,0644".toByteArray(Charsets.UTF_8)
                    sendSyncRecord(adbIn, adbOut, remoteId, "SEND", safePath)

                    val chunk = ByteArray(3500)
                    var remaining = size
                    while (remaining > 0) {
                        val count = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                        if (count < 0) throw EOFException("انتهى الملف قبل اكتمال الإرسال")
                        val record = ByteArrayOutputStream(count + 8)
                        writeAscii(record, "DATA")
                        writeLeInt(record, count)
                        record.write(chunk, 0, count)
                        sendServiceData(adbIn, adbOut, remoteId, record.toByteArray())
                        remaining -= count
                    }
                    val done = ByteArrayOutputStream(8)
                    writeAscii(done, "DONE")
                    writeLeInt(done, (System.currentTimeMillis() / 1000L).toInt())
                    sendServiceData(adbIn, adbOut, remoteId, done.toByteArray())
                    val response = readServiceResponse(adbIn, adbOut, maxBytes = 4096)
                    val tag = response.take(4).toByteArray().toString(Charsets.US_ASCII)
                    if (tag == "FAIL") {
                        val length = response.copyOfRange(4, 8).toLeInt()
                        throw IllegalStateException(response.copyOfRange(8, minOf(8 + length, response.size)).toString(Charsets.UTF_8))
                    }
                    if (tag != "OKAY") throw IllegalStateException("رد ADB غير متوقع أثناء نقل الملف")
                    sendPacket(adbOut, "CLSE", LOCAL_ID, remoteId, byteArrayOf())
                }
            }
        }
    }

    private fun openService(host: String, port: Int, service: String): ByteArray {
        val (socket, inputStream, outputStream) = connected(host, port)
        socket.use {
            inputStream.use { adbIn ->
                outputStream.use { adbOut ->
                    open(adbIn, adbOut, service)
                    return readServiceResponse(adbIn, adbOut, maxBytes = 32 * 1024 * 1024)
                }
            }
        }
    }

    private fun connected(host: String, port: Int): Triple<Socket, DataInputStream, DataOutputStream> {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 4500)
        socket.soTimeout = 45_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        try {
            authenticate(input, output)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return Triple(socket, input, output)
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream) {
        val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(output, "CNXN", ADB_VERSION, 4096, banner)
        var authStep = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < deadline) {
            val packet = readPacket(input)
            when (packet.command) {
                cmd("CNXN") -> return
                cmd("AUTH") -> {
                    if (packet.arg0 != AUTH_TYPE_TOKEN || packet.data.size != 20) {
                        throw IllegalStateException("تفاوض ADB غير مدعوم على هذا المنفذ")
                    }
                    val keys = getOrCreateKeyPair()
                    when (authStep++) {
                        0 -> {
                            val signer = Signature.getInstance("SHA1withRSA")
                            signer.initSign(keys.private)
                            signer.update(packet.data)
                            sendPacket(output, "AUTH", AUTH_TYPE_SIGNATURE, 0, signer.sign())
                        }
                        1 -> sendPacket(output, "AUTH", AUTH_TYPE_RSAPUBLICKEY, 0, adbPublicKey(keys.public as RSAPublicKey))
                        else -> Thread.sleep(150)
                    }
                }
                cmd("CLSE") -> throw IllegalStateException("رفض الرسيفر اتصال ADB")
            }
        }
        throw java.net.SocketTimeoutException("انتهت مهلة مصادقة ADB. وافق على بصمة المفتاح في الرسيفر ثم أعد المحاولة.")
    }

    private fun open(input: DataInputStream, output: DataOutputStream, service: String): Int {
        sendPacket(output, "OPEN", LOCAL_ID, 0, service.toByteArray(Charsets.UTF_8))
        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                cmd("OKAY") -> return packet.arg0
                cmd("CLSE") -> throw IllegalStateException(packet.data.toString(Charsets.UTF_8).ifBlank { "رفض الرسيفر خدمة ADB" })
                cmd("WRTE") -> sendPacket(output, "OKAY", LOCAL_ID, packet.arg0, byteArrayOf())
            }
        }
    }

    private fun readServiceResponse(input: DataInputStream, output: DataOutputStream, maxBytes: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < maxBytes) {
            val packet = readPacket(input)
            when (packet.command) {
                cmd("WRTE") -> {
                    bytes.write(packet.data)
                    sendPacket(output, "OKAY", LOCAL_ID, packet.arg0, byteArrayOf())
                }
                cmd("CLSE") -> {
                    sendPacket(output, "CLSE", LOCAL_ID, packet.arg0, byteArrayOf())
                    return bytes.toByteArray()
                }
                cmd("OKAY") -> Unit
                else -> throw IllegalStateException("رد ADB غير متوقع")
            }
        }
        throw IllegalStateException("تجاوز خرج الأمر الحد المسموح")
    }

    private fun sendSyncRecord(input: DataInputStream, output: DataOutputStream, remoteId: Int, tag: String, data: ByteArray) {
        val record = ByteArrayOutputStream(data.size + 8)
        writeAscii(record, tag)
        writeLeInt(record, data.size)
        record.write(data)
        sendServiceData(input, output, remoteId, record.toByteArray())
    }

    private fun sendServiceData(input: DataInputStream, output: DataOutputStream, remoteId: Int, data: ByteArray) {
        sendPacket(output, "WRTE", LOCAL_ID, remoteId, data)
        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                cmd("OKAY") -> return
                cmd("WRTE") -> sendPacket(output, "OKAY", LOCAL_ID, packet.arg0, byteArrayOf())
                cmd("CLSE") -> throw IllegalStateException("انقطع نقل الملف بواسطة الرسيفر")
            }
        }
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val publicKey = store.getCertificate(keyStoreAlias)?.publicKey
        val privateKey = store.getKey(keyStoreAlias, null) as? java.security.PrivateKey
        if (publicKey != null && privateKey != null) return KeyPair(publicKey, privateKey)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(keyStoreAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val modulus = key.modulus
        val r = BigInteger.ONE.shiftLeft(2048)
        val n0 = wordAtLittleEndian(modulus, 0).toLong() and 0xffff_ffffL
        val inverse = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32))
        val n0inv = BigInteger.ONE.shiftLeft(32).subtract(inverse).and(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)).toLong().toInt()
        val encoded = ByteArrayOutputStream(524)
        writeLeInt(encoded, 64)
        writeLeInt(encoded, n0inv)
        writeLittleWords(encoded, modulus)
        writeLittleWords(encoded, r.multiply(r).mod(modulus))
        writeLeInt(encoded, key.publicExponent.toInt())
        return (Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP) + " ReceiverControl@android\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun writeLittleWords(out: ByteArrayOutputStream, value: BigInteger) {
        for (i in 0 until 64) writeLeInt(out, wordAtLittleEndian(value, i))
    }

    private fun wordAtLittleEndian(value: BigInteger, index: Int): Int {
        val raw = value.toByteArray()
        val littleIndex = index * 4
        var word = 0
        for (i in 0..3) {
            val fromEnd = littleIndex + i
            val byte = if (fromEnd < raw.size) raw[raw.lastIndex - fromEnd].toInt() and 0xff else 0
            word = word or (byte shl (i * 8))
        }
        return word
    }

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun readPacket(input: DataInputStream): Packet {
        val command = readLeInt(input)
        val arg0 = readLeInt(input)
        val arg1 = readLeInt(input)
        val length = readLeInt(input)
        val checksum = readLeInt(input)
        val magic = readLeInt(input)
        if (length !in 0..MAX_PACKET_SIZE || magic != (command xor -1)) throw IllegalStateException("حزمة ADB غير صالحة")
        val data = ByteArray(length)
        input.readFully(data)
        if (data.fold(0L) { sum, b -> sum + (b.toInt() and 0xff) }.toInt() != checksum) {
            throw IllegalStateException("فشل التحقق من حزمة ADB")
        }
        return Packet(command, arg0, arg1, data)
    }

    private fun sendPacket(output: DataOutputStream, name: String, arg0: Int, arg1: Int, data: ByteArray) {
        val command = cmd(name)
        writeLeInt(output, command)
        writeLeInt(output, arg0)
        writeLeInt(output, arg1)
        writeLeInt(output, data.size)
        writeLeInt(output, data.fold(0L) { sum, b -> sum + (b.toInt() and 0xff) }.toInt())
        writeLeInt(output, command xor -1)
        output.write(data)
        output.flush()
    }

    private fun cmd(value: String): Int {
        val b = value.toByteArray(Charsets.US_ASCII)
        require(b.size == 4)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
    }

    private fun writeAscii(out: ByteArrayOutputStream, text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff)
    }
    private fun writeLeInt(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff)
        out.writeByte((value ushr 16) and 0xff); out.writeByte((value ushr 24) and 0xff)
    }
    private fun readLeInt(input: DataInputStream): Int =
        input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or
            (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 24)

    private fun ByteArray.toLeInt(): Int = (this[0].toInt() and 0xff) or
        ((this[1].toInt() and 0xff) shl 8) or ((this[2].toInt() and 0xff) shl 16) or
        ((this[3].toInt() and 0xff) shl 24)

    companion object {
        private const val ADB_VERSION = 0x01000001
        private const val AUTH_TYPE_TOKEN = 1
        private const val AUTH_TYPE_SIGNATURE = 2
        private const val AUTH_TYPE_RSAPUBLICKEY = 3
        private const val LOCAL_ID = 1
        private const val MAX_PACKET_SIZE = 4 * 1024 * 1024
    }
}
