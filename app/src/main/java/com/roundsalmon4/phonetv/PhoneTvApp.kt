package com.roundsalmon4.phonetv

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sideloaded TV apps never report crashes, so persist any uncaught exception
 * to the pairing screen for the next launch. The MainActivity surfaces it.
 */
class PhoneTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})"
                val text = "$time\n$device\nthread=${thread.name}\n${writer}"
                Log.e(TAG, "Crash:\n$text")
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("crash_time", System.currentTimeMillis())
                    .putString("crash_text", text)
                    .apply()
            } catch (_: Exception) {
                // Never hide the original failure
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        const val PREFS = "crash_report"
        private const val TAG = "PhoneTV"
    }
}