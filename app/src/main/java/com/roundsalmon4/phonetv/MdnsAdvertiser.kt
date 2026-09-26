package com.roundsalmon4.phonetv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * Advertises this TV as a PhoneTube cast receiver over mDNS (Network Service
 * Discovery), so a PhoneTube device on the same network can find it without
 * typing an IP. The advertisement carries the WebSocket port, so discovery
 * resolves straight to the address used for a cast.
 */
class MdnsAdvertiser(private val context: Context, private val serverPort: Int) {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start() {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS advertised as ${info.serviceName} on port ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS advertise failed (code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregister failed (code $errorCode)")
            }
        }
        val service = NsdServiceInfo().apply {
            serviceName = buildName()
            serviceType = SERVICE_TYPE
            this.port = serverPort
        }
        Log.i(TAG, "advertising PhoneTV on port $serverPort ($SERVICE_TYPE)")
        try {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            registrationListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "registerService failed", e)
        }
    }

    fun stop() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService failed", e)
        }
        registrationListener = null
        nsdManager = null
    }

    private fun buildName(): String {
        val model = "${Build.MANUFACTURER}-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-")
        val suffix = UUID.randomUUID().toString().take(6)
        return "PhoneTV-$model-$suffix"
    }

    companion object {
        private const val TAG = "MdnsAdvertiser"
        const val SERVICE_TYPE = "_phonetv._tcp."
    }
}