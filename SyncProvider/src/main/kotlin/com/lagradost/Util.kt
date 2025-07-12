package com.anhdaden

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

fun getDeviceId(packageName: String, context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (!androidId.isNullOrEmpty()) {
        return md5(packageName + androidId)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            val serialNumber = Build.getSerial()
            if (!serialNumber.isNullOrEmpty()) {
                return md5(packageName + serialNumber)
            }
        } catch (e: SecurityException) {
        }
    } else {
        val serialNumber = Build.SERIAL
        if (!serialNumber.isNullOrEmpty() && serialNumber != "unknown") {
            return md5(packageName + serialNumber)
        }
    }

    val deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
    return md5(packageName + UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString())
}

fun md5(input: String): String {
    val digest = MessageDigest.getInstance("MD5")
    val bytes = digest.digest(input.toByteArray())
    val sb = StringBuilder()
    for (byte in bytes) {
        sb.append(String.format("%02x", byte))
    }
    return sb.toString()
}