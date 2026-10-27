package com.monib.receivercontrol.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Discovers Android TV Remote v2 endpoints. This does not require our receiver companion app. */
class AndroidTvDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun discover(timeoutMillis: Long = 5_000): List<ReceiverDevice> =
        withContext(Dispatchers.Main.immediate) {
            val found = ConcurrentHashMap<String, ReceiverDevice>()
            val lock = wifiManager.createMulticastLock("AndroidTvRemoteDiscovery")
            runCatching { lock.setReferenceCounted(false); lock.acquire() }

            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val pending = ArrayDeque<NsdServiceInfo>()
                val queued = mutableSetOf<String>()
                var resolving = false
                var stopped = false
                lateinit var listener: NsdManager.DiscoveryListener

                fun finish() {
                    if (stopped) return
                    stopped = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    if (lock.isHeld) runCatching { lock.release() }
                    if (continuation.isActive) continuation.resume(found.values.sortedBy { it.name.lowercase() })
                }

                fun resolveNext() {
                    if (stopped || resolving || pending.isEmpty()) return
                    val service = pending.removeFirst()
                    resolving = true
                    try {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving = false
                                resolveNext()
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress.orEmpty()
                                if (host.isNotBlank() && serviceInfo.port == PAIRING_PORT) {
                                    val device = ReceiverDevice(
                                        name = serviceInfo.serviceName.ifBlank { "Android TV" },
                                        host = host,
                                        port = serviceInfo.port,
                                        protocol = ReceiverDevice.ANDROID_TV_REMOTE
                                    )
                                    found[host] = device
                                }
                                resolving = false
                                resolveNext()
                            }
                        })
                    } catch (_: Exception) {
                        resolving = false
                        resolveNext()
                    }
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.orEmpty().trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                        val key = serviceInfo.serviceName + "|" + serviceInfo.serviceType
                        if (queued.add(key)) {
                            pending.addLast(serviceInfo)
                            resolveNext()
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish()
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish()
                    }
                }

                continuation.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    if (lock.isHeld) runCatching { lock.release() }
                }

                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    handler.postDelayed({ finish() }, timeoutMillis)
                } catch (_: Exception) {
                    finish()
                }
            }
        }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
        const val PAIRING_PORT = 6467
    }
}
