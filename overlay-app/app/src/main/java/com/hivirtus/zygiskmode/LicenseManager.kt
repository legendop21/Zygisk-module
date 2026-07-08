package com.hivirtus.zygiskmode

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LicenseManager {

    private const val PREFS = "hivirtus_license_vip"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_ACTIVATED = "activated_at"
    private const val MASTER_SECRET = "HIVIRTUS_ZYGISK_VIP_97d6_LIQDY"

    fun isLicensed(context: Context): Boolean {
        val key = getSavedKey(context) ?: return false
        return validateKey(key)
    }

    fun activate(context: Context, key: String): Boolean {
        val normalized = key.trim().uppercase()
        if (!validateKey(normalized)) return false
        prefs(context).edit()
            .putString(KEY_LICENSE, normalized)
            .putLong(KEY_ACTIVATED, System.currentTimeMillis())
            .apply()
        return true
    }

    fun getSavedKey(context: Context): String? =
        prefs(context).getString(KEY_LICENSE, null)

    /** Format: HIVIRTUS-XXXX-XXXX-XXXX (last block = HMAC check) */
    fun validateKey(key: String): Boolean {
        val parts = key.trim().uppercase().split("-")
        if (parts.size != 4 || parts[0] != "HIVIRTUS") return false
        if (!parts[1].matches(Regex("[A-F0-9]{4}")) ) return false
        if (!parts[2].matches(Regex("[A-F0-9]{4}")) ) return false
        if (!parts[3].matches(Regex("[A-F0-9]{4}")) ) return false
        val expected = checksum(parts[1] + parts[2])
        return parts[3] == expected
    }

    /** Use same logic in Telegram bot / generate_key.sh */
    fun generateKey(): String {
        val a = randomBlock()
        val b = randomBlock()
        val c = checksum(a + b)
        return "HIVIRTUS-$a-$b-$c"
    }

    private fun checksum(payload: String): String =
        hmacSha256(MASTER_SECRET, payload).take(4)

    private fun randomBlock(): String =
        hmacSha256(MASTER_SECRET, System.nanoTime().toString() + Math.random())
            .take(4)

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
